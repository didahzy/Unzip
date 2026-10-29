#!/usr/bin/env bash
# Generates the real RAR fixtures for :archive-rar connectedAndroidTest.
#
# Output: archive-rar/src/androidTest/resources/fixtures/
#   plain-rar4.rar            RAR4, no password, nested dirs + Chinese names + empty file
#   pw-rar4.rar               RAR4, file encryption, password pw1234
#   pw-rar5.rar               RAR5, file encryption, password pw1234
#   header-pw-rar5.rar        RAR5, -hp (encrypted headers), password pw1234
#   multi-rar5.part1..3.rar   RAR5, 3 volumes (payload sha256 -> multi-rar5.sha256)
#   中文-rar5.rar              RAR5, Chinese file/dir names, nested
#
# Requires the official `rar` CLI (trial version can create archives).
# NOTE: the `brew install rar` cask is disabled by Homebrew (Gatekeeper),
# and rar 7.x can no longer CREATE RAR4 archives (-ma4 removed).
#   RAR_BIN   rar >= 5 capable of RAR5  (macOS arm: rarmacos-arm-*.tar.gz from
#                                      https://www.rarlab.com/download.htm)
#   RAR4_BIN  rar 5.x/6.x capable of -ma4 (e.g. rarosx-6.0.2.tar.gz, x64
#             binary, runs under Rosetta on Apple Silicon)
# Example:
#   RAR_BIN=/path/rar7/rar RAR4_BIN=/path/rar6/rar ./scripts/gen-rar-fixtures.sh
# Both default to the first `rar` found in PATH; RAR4 capability is probed.
#
# Text payloads are deterministic and byte-identical to the constants in
# RarExtractorConnectedTest (shared convention: lines end with \n).
# The multi-volume payload is incompressible random bytes; its sha256 is
# written to multi-rar5.sha256 for on-device verification.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$REPO_ROOT/archive-rar/src/androidTest/resources/fixtures"
PASSWORD="pw1234"

RAR_BIN="${RAR_BIN:-$(command -v rar || true)}"
if [[ -z "$RAR_BIN" ]]; then
  echo "error: 'rar' not found in PATH; set RAR_BIN (see header comment)" >&2
  exit 1
fi
RAR4_BIN="${RAR4_BIN:-$RAR_BIN}"

# Probe RAR4 creation capability (-ma4 was dropped in rar 7).
probe_dir="$(mktemp -d "${TMPDIR:-/tmp}/unseal-rar-probe.XXXXXX")"
trap 'rm -rf "$STAGE" "$probe_dir"' EXIT
printf 'probe\n' > "$probe_dir/p.txt"
"$RAR4_BIN" a -idq -ma4 "$probe_dir/p4.rar" "$probe_dir/p.txt" >/dev/null 2>&1
if [[ "$(head -c 7 "$probe_dir/p4.rar" | od -An -tx1 | tr -d ' \n')" != "526172211a0700" ]]; then
  echo "error: RAR4_BIN=$RAR4_BIN cannot create RAR4 archives (needs rar 5.x/6.x with -ma4)" >&2
  exit 1
fi

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/unseal-rar-fixtures.XXXXXX")"

mkdir -p "$OUT_DIR"
rm -f "$OUT_DIR"/*.rar "$OUT_DIR"/multi-rar5.sha256

# ---------- staged content ----------

# plain-rar5: round-trip + 0-byte entry
mkdir -p "$STAGE/plain5/docs/nested"
printf 'UNSEAL plain rar5 alpha content\n' > "$STAGE/plain5/alpha.txt"
printf 'UNSEAL plain rar5 beta, plain second line.\n' > "$STAGE/plain5/docs/beta.txt"
printf 'UNSEAL 中文文档内容：嵌套目录条目。\n' > "$STAGE/plain5/docs/nested/说明.txt"
: > "$STAGE/plain5/empty.txt" # 0-byte entry must still produce a file

# plain-rar4: legacy format, unicode names via RAR4 encoding
mkdir -p "$STAGE/plain4/旧目录"
printf 'UNSEAL plain rar4 legacy content\n' > "$STAGE/plain4/legacy.txt"
printf 'UNSEAL 旧版格式里的中文文件。\n' > "$STAGE/plain4/旧目录/文档.txt"

# pw-rar4 / pw-rar5 / header-pw-rar5: same small content, different protection
mkdir -p "$STAGE/pw/secret dir"
printf 'UNSEAL encrypted secret payload\n' > "$STAGE/pw/secret.txt"
printf 'UNSEAL 加密条目：密码正确才能读到。\n' > "$STAGE/pw/secret dir/机密.txt"

# multi-rar5: incompressible payload to force volume spanning
mkdir -p "$STAGE/multi"
head -c 8100 /dev/urandom > "$STAGE/multi/payload.bin"
printf 'UNSEAL multi-volume first volume text\n' > "$STAGE/multi/first.txt"

# 中文-rar5: Chinese names everywhere
mkdir -p "$STAGE/cn/文档/子目录" "$STAGE/cn/空目录"
printf '第一篇：中文文件名与目录。\n' > "$STAGE/cn/文档/第一篇.txt"
printf '第二篇：嵌套子目录中的文件。\n' > "$STAGE/cn/文档/子目录/第二篇.txt"
printf 'README with Chinese neighbours.\n' > "$STAGE/cn/说明.md"
: > "$STAGE/cn/空目录/.keep"

# ---------- create archives (trial rar may create archives) ----------
# Each archive is created from *inside* the staged root with -ep1 so entries
# carry clean relative names (no staging prefix).

pack() { # pack <rar-bin> <archive> <staged-root> [extra-switches...]
  local bin="$1" archive="$2" root="$3"
  shift 3
  (cd "$root" && "$bin" a -idq -r -ep1 "$@" "$archive" '*') >/dev/null
}

pack "$RAR4_BIN" "$OUT_DIR/plain-rar4.rar" "$STAGE/plain4" -ma4
pack "$RAR_BIN" "$OUT_DIR/plain-rar5.rar" "$STAGE/plain5"
pack "$RAR4_BIN" "$OUT_DIR/pw-rar4.rar" "$STAGE/pw" -ma4 -p"$PASSWORD"
pack "$RAR_BIN" "$OUT_DIR/pw-rar5.rar" "$STAGE/pw" -p"$PASSWORD"
pack "$RAR_BIN" "$OUT_DIR/header-pw-rar5.rar" "$STAGE/pw" -hp"$PASSWORD"
pack "$RAR_BIN" "$OUT_DIR/中文-rar5.rar" "$STAGE/cn"
# multi-volume: -v3k creates multi-rar5.partN.rar
(cd "$STAGE/multi" && "$RAR_BIN" a -idq -r -ep1 -v3k "$OUT_DIR/multi-rar5.rar" '*') >/dev/null

shasum -a 256 "$STAGE/multi/payload.bin" | awk '{print $1}' > "$OUT_DIR/multi-rar5.sha256"

# ---------- report ----------

echo "generated fixtures in $OUT_DIR:"
ls -l "$OUT_DIR" | awk 'NR>1 {printf "  %-28s %6d bytes\n", $NF, $5}'
echo "payload.bin sha256: $(cat "$OUT_DIR/multi-rar5.sha256")"

for f in "$OUT_DIR"/*.rar; do
  size=$(stat -f%z "$f")
  if (( size > 10240 )); then
    echo "error: $f is ${size} bytes (limit 10240)" >&2
    exit 1
  fi
done
echo "all volumes within the 10KB limit"
