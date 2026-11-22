#!/usr/bin/env bash
# Builds the vendored TG WS Proxy core (vendor/tgws) as a static binary for every module ABI.
#   scripts/build-tgws.sh <out_dir>   -> <out_dir>/libs/<abi>/tgwsproxy
# Needs: go (the version from vendor/tgws/go.mod). No NDK: the cgo bridge of the original Android
# library is not used, the module runs the same Go runtime as a console binary.
set -euo pipefail

OUT=$(realpath -m "${1:-build/tgws}")
SRC=$(cd "$(dirname "$0")/.." && pwd)/vendor/tgws
mkdir -p "$OUT/libs/arm64-v8a" "$OUT/libs/armeabi-v7a"

cd "$SRC"
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags '-s -w' -o "$OUT/libs/arm64-v8a/tgwsproxy" .
CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 go build -trimpath -ldflags '-s -w' -o "$OUT/libs/armeabi-v7a/tgwsproxy" .

ls -l "$OUT/libs"/*/tgwsproxy
