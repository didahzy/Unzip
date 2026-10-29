cmake_minimum_required(VERSION 3.22.1)
project(unrar_jni LANGUAGES CXX)

# Vendored UnRAR source (see vendor/unrar/VERSION.txt). The file list mirrors the
# `lib` target of unrarsrc's own makefile: OBJECTS + LIB_OBJ compiled with -DRARDLL,
# which also #ifdef's out the host binary's main() in rar.cpp. Host-only files
# (recvol.cpp, rs.cpp, the SSE variant blake2s_sse.cpp) are intentionally excluded.
set(UNRAR_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/unrar)
set(UNRAR_SOURCES
        ${UNRAR_DIR}/rar.cpp
        ${UNRAR_DIR}/strlist.cpp
        ${UNRAR_DIR}/strfn.cpp
        ${UNRAR_DIR}/pathfn.cpp
        ${UNRAR_DIR}/smallfn.cpp
        ${UNRAR_DIR}/global.cpp
        ${UNRAR_DIR}/file.cpp
        ${UNRAR_DIR}/filefn.cpp
        ${UNRAR_DIR}/filcreat.cpp
        ${UNRAR_DIR}/archive.cpp
        ${UNRAR_DIR}/arcread.cpp
        ${UNRAR_DIR}/unicode.cpp
        ${UNRAR_DIR}/system.cpp
        ${UNRAR_DIR}/crypt.cpp
        ${UNRAR_DIR}/crc.cpp
        ${UNRAR_DIR}/rawread.cpp
        ${UNRAR_DIR}/encname.cpp
        ${UNRAR_DIR}/resource.cpp
        ${UNRAR_DIR}/match.cpp
        ${UNRAR_DIR}/timefn.cpp
        ${UNRAR_DIR}/rdwrfn.cpp
        ${UNRAR_DIR}/consio.cpp
        ${UNRAR_DIR}/options.cpp
        ${UNRAR_DIR}/errhnd.cpp
        ${UNRAR_DIR}/rarvm.cpp
        ${UNRAR_DIR}/secpassword.cpp
        ${UNRAR_DIR}/rijndael.cpp
        ${UNRAR_DIR}/getbits.cpp
        ${UNRAR_DIR}/sha1.cpp
        ${UNRAR_DIR}/sha256.cpp
        ${UNRAR_DIR}/blake2s.cpp
        ${UNRAR_DIR}/hash.cpp
        ${UNRAR_DIR}/extinfo.cpp
        ${UNRAR_DIR}/extract.cpp
        ${UNRAR_DIR}/volume.cpp
        ${UNRAR_DIR}/list.cpp
        ${UNRAR_DIR}/find.cpp
        ${UNRAR_DIR}/unpack.cpp
        ${UNRAR_DIR}/headers.cpp
        ${UNRAR_DIR}/threadpool.cpp
        ${UNRAR_DIR}/rs16.cpp
        ${UNRAR_DIR}/cmddata.cpp
        ${UNRAR_DIR}/ui.cpp
        ${UNRAR_DIR}/largepage.cpp
        ${UNRAR_DIR}/filestr.cpp
        ${UNRAR_DIR}/scantree.cpp
        ${UNRAR_DIR}/dll.cpp
        ${UNRAR_DIR}/qopen.cpp)

add_library(unrar STATIC ${UNRAR_SOURCES})
target_compile_definitions(unrar PRIVATE
        RARDLL          # library build; excludes host main() and console UI
        RAR_SMP         # multi-threaded RAR5 unpacking (threadpool, needs pthread)
        _UNIX
        _FILE_OFFSET_BITS=64
        _LARGEFILE_SOURCE)
target_compile_options(unrar PRIVATE
        -Wno-switch
        -Wno-logical-op-parentheses
        -Wno-dangling-else)
set_target_properties(unrar PROPERTIES
        CXX_STANDARD 11
        POSITION_INDEPENDENT_CODE ON)

add_library(unrar_jni SHARED unrar_jni.cpp)
target_include_directories(unrar_jni PRIVATE ${UNRAR_DIR})
# dll.hpp only defines its UNIX type shims (UINT/LPARAM/HANDLE/...) under _UNIX.
target_compile_definitions(unrar_jni PRIVATE _UNIX)
target_link_libraries(unrar_jni PRIVATE unrar log)
set_target_properties(unrar_jni PROPERTIES CXX_STANDARD 17)
