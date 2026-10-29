// JNI glue between ziyao.huang.unzip.archive.rar.RarNativeJni and the vendored
// UnRAR library (vendor/unrar, see VERSION.txt).
//
// Semantics required by the Java side (Task 5 review handoff):
//  - No native handles: open/list/extract each re-open the archive by path.
//    Enumeration order must be IDENTICAL across those calls. RAR_OM_LIST merges
//    multi-volume continuation headers into single entries, while RAR_OM_EXTRACT
//    returns every part header; extract() therefore skips RHDF_SPLITBEFORE
//    continuation headers WITHOUT counting an entry index, keeping the index
//    space in lockstep with list().
//  - Password CharArrays are copied into a C string at the JNI boundary and
//    used only for the duration of the call (RARSetPassword copies internally);
//    the Java-side buffer is never referenced across calls and is wiped after use.
//  - Entry names are checked on the C++ side as the last line of defence
//    against path traversal (no absolute paths, no ".." segments); offending
//    entries are skipped and counted, never extracted.
//
// Error codes observed against real fixtures (unrar 7.3.2):
//  - header-encrypted archive: open() succeeds, ROADF_ENCHEADERS flag set;
//    ReadHeader without password -> ERAR_MISSING_PASSWORD(22),
//    with wrong password -> ERAR_BAD_PASSWORD(24).
//  - RAR5 wrong file password -> ERAR_BAD_PASSWORD(24).
//  - RAR4 wrong file password -> ERAR_BAD_DATA(12) (no password check field in
//    the format); mapped to WRONG_PASSWORD when the failing entry is encrypted.
//  - 0-byte entries are created on disk by unrar itself (verified on host).

#include <jni.h>
#include "dll.hpp"

#include <android/log.h>

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <set>
#include <string>
#include <vector>

namespace {

constexpr const char *kTag = "unrar_jni";

// UnrarException.Reason ordinals (ziyao.huang.unzip.archive.rar.UnrarException).
constexpr int kReasonWrongPassword = 0;
constexpr int kReasonBadArchive = 1;
constexpr int kReasonIo = 2;

// unrar has global error-handler state, so native calls are serialized.
std::mutex g_unrar_mutex;

void LogW(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(ANDROID_LOG_WARN, kTag, fmt, ap);
    va_end(ap);
}

// ---------------------------------------------------------------------------
// String conversion (no allocation failure handling: JNI throws on OOM)
// ---------------------------------------------------------------------------

std::string JstringToUtf8(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// UTF-8 -> UTF-32 (Android wchar_t is 32-bit).
std::wstring Utf8ToWide(const std::string &s) {
    std::wstring out;
    out.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        uint32_t cp = 0;
        int extra = 0;
        if (c < 0x80) {
            cp = c;
        } else if ((c & 0xE0) == 0xC0) {
            cp = c & 0x1F;
            extra = 1;
        } else if ((c & 0xF0) == 0xE0) {
            cp = c & 0x0F;
            extra = 2;
        } else if ((c & 0xF8) == 0xF0) {
            cp = c & 0x07;
            extra = 3;
        } else {
            cp = 0xFFFD; // invalid byte, replacement
            extra = 0;
        }
        for (int k = 0; k < extra && i + 1 < s.size(); k++) {
            unsigned char next = static_cast<unsigned char>(s[++i]);
            if ((next & 0xC0) != 0x80) {
                cp = 0xFFFD;
                break;
            }
            cp = (cp << 6) | (next & 0x3F);
        }
        i++;
        out.push_back(static_cast<wchar_t>(cp));
    }
    return out;
}

// UTF-16 jchar sequence (with surrogate pairs) -> UTF-8; the immediate,
// self-owned copy of the password made at the JNI boundary.
std::string JcharsToUtf8(JNIEnv *env, jcharArray array) {
    std::string out;
    if (array == nullptr) return out;
    const jsize len = env->GetArrayLength(array);
    if (len == 0) return out;
    jchar *chars = env->GetCharArrayElements(array, nullptr);
    auto append = [&out](uint32_t cp) {
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    };
    for (jsize i = 0; i < len; i++) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            uint32_t lo = chars[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                i++;
            }
        }
        append(cp);
    }
    env->ReleaseCharArrayElements(array, chars, JNI_ABORT);
    return out;
}

// UTF-32 -> Java String (UTF-16, with surrogate pairs).
jstring WideToJstring(JNIEnv *env, const std::wstring &s) {
    std::vector<jchar> units;
    units.reserve(s.size());
    for (wchar_t wc : s) {
        uint32_t cp = static_cast<uint32_t>(wc);
        if (cp < 0x10000) {
            units.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            units.push_back(static_cast<jchar>(0xD800 | (cp >> 10)));
            units.push_back(static_cast<jchar>(0xDC00 | (cp & 0x3FF)));
        }
    }
    return env->NewString(units.data(), static_cast<jsize>(units.size()));
}

// ---------------------------------------------------------------------------
// Exceptions
// ---------------------------------------------------------------------------

// Thrown after a Java exception has been made pending; unwinds to the JNI
// entry point, which catches it and returns with the pending exception set.
struct NativeUnwind {};

[[noreturn]] void ThrowUnrar(JNIEnv *env, int reason, const char *fmt, ...) {
    char message[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(message, sizeof(message), fmt, ap);
    va_end(ap);

    if (env->ExceptionCheck() == JNI_TRUE) {
        throw NativeUnwind{}; // a pending exception already exists; keep it
    }
    jclass cls = env->FindClass("ziyao/huang/unzip/archive/rar/UnrarException");
    jclass reason_cls =
        env->FindClass("ziyao/huang/unzip/archive/rar/UnrarException$Reason");
    if (cls != nullptr && reason_cls != nullptr) {
        static const char *kReasonNames[3] = {"WRONG_PASSWORD", "BAD_ARCHIVE", "IO"};
        jfieldID reason_field = env->GetStaticFieldID(
            reason_cls, kReasonNames[reason],
            "Lziyao/huang/unzip/archive/rar/UnrarException$Reason;");
        jmethodID ctor =
            env->GetMethodID(cls, "<init>",
                             "(Ljava/lang/String;Lziyao/huang/unzip/archive/rar/UnrarException$Reason;)V");
        if (reason_field != nullptr && ctor != nullptr) {
            jobject reason_value = env->GetStaticObjectField(reason_cls, reason_field);
            jobject ex = env->NewObject(cls, ctor, env->NewStringUTF(message), reason_value);
            if (ex != nullptr && env->Throw(static_cast<jthrowable>(ex)) == 0) {
                throw NativeUnwind{};
            }
        }
    }
    // Only reachable when the exception machinery itself failed (class lookup
    // failure, OOM) with nothing pending; abort loudly instead of returning.
    env->FatalError("unrar_jni: failed to raise UnrarException");
}

// Map an unrar DLL error code. [entry_encrypted] refines ERAR_BAD_DATA:
// for an encrypted entry a checksum failure means a wrong password (the
// RAR4 format has no password-check field, so wrong passwords surface as CRC
// errors during decompression).
int MapCode(int code, bool entry_encrypted) {
    switch (code) {
        case ERAR_BAD_PASSWORD:
        case ERAR_MISSING_PASSWORD:
            return kReasonWrongPassword;
        case ERAR_BAD_ARCHIVE:
        case ERAR_UNKNOWN_FORMAT:
        case ERAR_EOPEN: // cannot open archive / a volume of the set is missing
            return kReasonBadArchive;
        case ERAR_BAD_DATA:
            return entry_encrypted ? kReasonWrongPassword : kReasonBadArchive;
        default:
            return kReasonIo;
    }
}

[[noreturn]] void ThrowMapped(JNIEnv *env, int code, bool entry_encrypted, const char *where) {
    ThrowUnrar(env, MapCode(code, entry_encrypted),
               "unrar %s failed: code=%d (%s)", where, code,
               code == ERAR_BAD_PASSWORD ? "bad password"
               : code == ERAR_MISSING_PASSWORD ? "missing password"
               : code == ERAR_BAD_DATA ? "bad data"
               : code == ERAR_BAD_ARCHIVE ? "bad archive"
               : code == ERAR_UNKNOWN_FORMAT ? "unknown format"
               : code == ERAR_EOPEN ? "cannot open"
               : "io");
}

// ---------------------------------------------------------------------------
// Entry name safety (backstop behind Java-side SafePaths)
// ---------------------------------------------------------------------------

// Mirrors Java-side SafePaths: reject empty names, leading separators and any
// ".." path segment. Everything else (including "." and empty segments) is left
// to Java, which applies the same rules before native is ever called.
bool EntryNameSafe(const std::wstring &name) {
    if (name.empty() || name[0] == L'/' || name[0] == L'\\') return false;
    size_t seg_start = 0;
    for (size_t i = 0; i <= name.size(); i++) {
        wchar_t c = i < name.size() ? name[i] : L'/';
        if (c == L'/' || c == L'\\') {
            if (i == seg_start + 1 && name[seg_start] == L'.' &&
                name[seg_start + 1] == L'.')
                return false; // ".." segment
            seg_start = i + 1;
        }
    }
    return true;
}

// ---------------------------------------------------------------------------
// Shared archive session
// ---------------------------------------------------------------------------

struct Session {
    HANDLE handle = nullptr;

    ~Session() {
        if (handle != nullptr) RARCloseArchive(handle);
    }
};

void OpenArchive(JNIEnv *env, const std::string &path_utf8, unsigned mode,
                 const std::string &password_utf8, Session *session,
                 unsigned *open_flags = nullptr) {
    std::wstring path_wide = Utf8ToWide(path_utf8);
    RAROpenArchiveDataEx data{};
    data.ArcName = const_cast<char *>(path_utf8.c_str());
    data.ArcNameW = const_cast<wchar_t *>(path_wide.c_str());
    data.OpenMode = mode;
    HANDLE handle = RAROpenArchiveEx(&data);
    if (handle == nullptr) {
        ThrowMapped(env, data.OpenResult != 0 ? data.OpenResult : ERAR_EOPEN,
                    false, "open");
    }
    session->handle = handle;
    if (open_flags != nullptr) *open_flags = data.Flags;
    // RARSetPassword copies the string internally; our copy dies with the call.
    if (!password_utf8.empty()) {
        RARSetPassword(handle, const_cast<char *>(password_utf8.c_str()));
    }
}

// Windows FILETIME (Mtime{Low,High}) -> unix seconds, or 0 when unknown.
int64_t FiletimeToEpochSeconds(uint32_t low, uint32_t high) {
    uint64_t raw = (static_cast<uint64_t>(high) << 32) | low;
    if (raw == 0) return 0;
    constexpr uint64_t kEpochDelta = 116444736000000000ULL;
    return raw > kEpochDelta
               ? static_cast<int64_t>((raw - kEpochDelta) / 10000000ULL)
               : 0;
}

std::wstring HeaderFileName(const RARHeaderDataEx &hd) {
    if (hd.FileNameW[0] != L'\0') return hd.FileNameW;
    // Fallback: ANSI/UTF-8 byte name for exotic RAR archives without unicode.
    return Utf8ToWide(hd.FileName);
}

// ---------------------------------------------------------------------------
// NativeRarEntry construction
// ---------------------------------------------------------------------------

// Lightweight per-entry data collected during enumeration; JNI objects are
// only created afterwards so local references never accumulate (a large
// archive could otherwise overflow the local reference table).
struct RawEntry {
    std::wstring name;
    bool is_dir;
    int64_t size;
    int64_t packed;
    bool encrypted;
    int64_t mtime_epoch; // 0 = unknown
};

RawEntry ToRawEntry(const RARHeaderDataEx &hd) {
    return RawEntry{
        HeaderFileName(hd),
        (hd.Flags & RHDF_DIRECTORY) != 0,
        (static_cast<int64_t>(hd.UnpSizeHigh) << 32) | hd.UnpSize,
        (static_cast<int64_t>(hd.PackSizeHigh) << 32) | hd.PackSize,
        (hd.Flags & RHDF_ENCRYPTED) != 0,
        FiletimeToEpochSeconds(hd.MtimeLow, hd.MtimeHigh),
    };
}

jobject MakeEntry(JNIEnv *env, const RawEntry &e) {
    jclass entry_cls = env->FindClass("ziyao/huang/unzip/archive/rar/NativeRarEntry");
    jmethodID ctor = env->GetMethodID(
        entry_cls, "<init>",
        "(Ljava/lang/String;ZJJZLjava/lang/Long;)V");
    jstring name = WideToJstring(env, e.name);
    jobject boxed = nullptr;
    if (e.mtime_epoch != 0) {
        jclass long_cls = env->FindClass("java/lang/Long");
        jmethodID value_of = env->GetStaticMethodID(long_cls, "valueOf", "(J)Ljava/lang/Long;");
        boxed = env->CallStaticObjectMethod(long_cls, value_of, e.mtime_epoch);
    }
    jobject entry = env->NewObject(entry_cls, ctor, name, e.is_dir ? JNI_TRUE : JNI_FALSE,
                                   e.size, e.packed, e.encrypted ? JNI_TRUE : JNI_FALSE, boxed);
    env->DeleteLocalRef(name);
    if (boxed != nullptr) env->DeleteLocalRef(boxed);
    return entry;
}

// Common enumeration step: advance the archive past the current header.
void SkipCurrent(JNIEnv *env, HANDLE handle, bool entry_encrypted, const char *where) {
    int code = RARProcessFile(handle, RAR_SKIP, nullptr, nullptr);
    if (code != ERAR_SUCCESS) ThrowMapped(env, code, entry_encrypted, where);
}

} // namespace

// ---------------------------------------------------------------------------
// JNI entry points (all called with the global mutex held)
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_ziyao_huang_unzip_archive_rar_RarNativeJni_nOpenHeaderEncrypted(
        JNIEnv *env, jobject, jstring path) {
    std::lock_guard<std::mutex> lock(g_unrar_mutex);
    try {
        std::string path_utf8 = JstringToUtf8(env, path);
        Session session;
        unsigned flags = 0;
        OpenArchive(env, path_utf8, RAR_OM_LIST, std::string(), &session, &flags);
        return (flags & ROADF_ENCHEADERS) ? JNI_TRUE : JNI_FALSE;
    } catch (NativeUnwind &) {
        return JNI_FALSE; // UnrarException pending
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_ziyao_huang_unzip_archive_rar_RarNativeJni_nList(
        JNIEnv *env, jobject, jstring path, jcharArray password) {
    std::lock_guard<std::mutex> lock(g_unrar_mutex);
    try {
        // Contract: the password CharArray must not outlive this call.
        std::string password_utf8 = JcharsToUtf8(env, password);
        std::string path_utf8 = JstringToUtf8(env, path);

        Session session;
        OpenArchive(env, path_utf8, RAR_OM_LIST, password_utf8, &session);
        std::memset(password_utf8.data(), 0, password_utf8.size()); // wipe our copy

        std::vector<RawEntry> raw;
        RARHeaderDataEx hd{};
        int code;
        while ((code = RARReadHeaderEx(session.handle, &hd)) == ERAR_SUCCESS) {
            raw.push_back(ToRawEntry(hd));
            SkipCurrent(env, session.handle, (hd.Flags & RHDF_ENCRYPTED) != 0, "list");
        }
        if (code != ERAR_END_ARCHIVE) {
            ThrowMapped(env, code, false, "list");
        }

        jclass entry_cls = env->FindClass("ziyao/huang/unzip/archive/rar/NativeRarEntry");
        jobjectArray result = env->NewObjectArray(
            static_cast<jsize>(raw.size()), entry_cls, nullptr);
        for (size_t i = 0; i < raw.size(); i++) {
            jobject entry = MakeEntry(env, raw[i]);
            env->SetObjectArrayElement(result, static_cast<jsize>(i), entry);
            env->DeleteLocalRef(entry);
        }
        return result;
    } catch (NativeUnwind &) {
        return nullptr; // UnrarException pending
    }
}

extern "C" JNIEXPORT void JNICALL
Java_ziyao_huang_unzip_archive_rar_RarNativeJni_nExtract(
        JNIEnv *env, jobject, jstring path, jstring out_dir, jcharArray password,
        jintArray wanted, jobject on_file_done) {
    std::lock_guard<std::mutex> lock(g_unrar_mutex);
    try {
        // Contract: the password CharArray must not outlive this call.
        std::string password_utf8 = JcharsToUtf8(env, password);
        std::string path_utf8 = JstringToUtf8(env, path);
        std::string out_dir_utf8 = JstringToUtf8(env, out_dir);
        std::wstring out_dir_wide = Utf8ToWide(out_dir_utf8);
        if (!out_dir_wide.empty() && out_dir_wide.back() != L'/')
            out_dir_wide.push_back(L'/');

        const jsize wanted_len = env->GetArrayLength(wanted);
        std::vector<jint> wanted_vec(wanted_len);
        if (wanted_len > 0) {
            env->GetIntArrayRegion(wanted, 0, wanted_len, wanted_vec.data());
        }
        std::set<jint> wanted_set(wanted_vec.begin(), wanted_vec.end());

        jmethodID invoke = nullptr;
        if (on_file_done != nullptr) {
            jclass fn1 = env->FindClass("kotlin/jvm/functions/Function1");
            invoke = env->GetMethodID(fn1, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
        }

        Session session;
        OpenArchive(env, path_utf8, RAR_OM_EXTRACT, password_utf8, &session);
        std::memset(password_utf8.data(), 0, password_utf8.size()); // wipe our copy

        int skipped_unsafe = 0;
        RARHeaderDataEx hd{};
        int code;
        int index = 0; // entry index as seen by nList (continuations not counted)
        while ((code = RARReadHeaderEx(session.handle, &hd)) == ERAR_SUCCESS) {
            const bool encrypted = (hd.Flags & RHDF_ENCRYPTED) != 0;
            if (hd.Flags & RHDF_SPLITBEFORE) {
                // Continuation part of a file split across volumes: LIST mode merges
                // these into the primary header, so skip without counting an index.
                SkipCurrent(env, session.handle, encrypted, "extract");
                continue;
            }
            if (wanted_set.count(index) != 0) {
                std::wstring name = HeaderFileName(hd);
                if (!EntryNameSafe(name)) {
                    // Path traversal attempt: last-line defence, skip and count.
                    LogW("skipping unsafe entry name in rar: index=%d", index);
                    skipped_unsafe++;
                    SkipCurrent(env, session.handle, encrypted, "extract");
                } else {
                    code = RARProcessFileW(session.handle, RAR_EXTRACT,
                                           out_dir_wide.data(), nullptr);
                    if (code != ERAR_SUCCESS) {
                        ThrowMapped(env, code, encrypted, "extract");
                    }
                    if (on_file_done != nullptr && invoke != nullptr) {
                        jclass integer_cls = env->FindClass("java/lang/Integer");
                        jmethodID value_of =
                            env->GetStaticMethodID(integer_cls, "valueOf", "(I)Ljava/lang/Integer;");
                        jobject boxed = env->CallStaticObjectMethod(integer_cls, value_of, index);
                        jobject ignored =
                            env->CallObjectMethod(on_file_done, invoke, boxed);
                        env->DeleteLocalRef(boxed);
                        if (ignored != nullptr) env->DeleteLocalRef(ignored);
                        if (env->ExceptionCheck() == JNI_TRUE) return;
                    }
                }
            } else {
                SkipCurrent(env, session.handle, encrypted, "extract");
            }
            index++;
        }
        if (code != ERAR_END_ARCHIVE) {
            ThrowMapped(env, code, false, "extract");
        }
        if (skipped_unsafe > 0) {
            LogW("extract finished with %d unsafe entries skipped", skipped_unsafe);
        }
    } catch (NativeUnwind &) {
        return; // UnrarException pending
    }
}
