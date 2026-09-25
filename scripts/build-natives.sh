#!/usr/bin/env bash
# Builds / fetches native binaries for the Manka module.
#   scripts/build-natives.sh <out_dir>
# Needs: ANDROID_NDK_HOME, gh (authenticated via GH_TOKEN), cargo + rustup, jq, unzip.
# Optional pins: ZAPRET_TAG, ZAPRET2_TAG, BYEDPI_TAG, TGWS_TAG (default: latest release).
set -euo pipefail

OUT=$(realpath -m "${1:-build/natives}")
WORK=$(realpath -m "${WORK_DIR:-build/work}")
API=26
ABIS=(arm64-v8a armeabi-v7a)

mkdir -p "$OUT" "$WORK"
for abi in "${ABIS[@]}"; do mkdir -p "$OUT/libs/$abi"; done
mkdir -p "$OUT/files/lua" "$OUT/files/fake"

latest_tag() {
	gh release view --repo "$1" --json tagName --jq .tagName
}

clang_target() {
	case "$1" in
		arm64-v8a) echo aarch64-linux-android ;;
		armeabi-v7a) echo armv7a-linux-androideabi ;;
	esac
}

zapret_dir() {
	case "$1" in
		arm64-v8a) echo android-arm64 ;;
		armeabi-v7a) echo android-arm ;;
	esac
}

versions_file="$OUT/versions.txt"
: > "$versions_file"

# ------------------------------------------------------------------ zapret / zapret2 (official android builds)
fetch_zapret() {
	local repo=$1 tag=$2 bin=$3 name=${1#*/}
	[ -n "$tag" ] || tag=$(latest_tag "$repo")
	echo "== $repo $tag"
	echo "$name=$tag" >> "$versions_file"
	local dir="$WORK/$name"
	rm -rf "$dir"; mkdir -p "$dir"
	gh release download "$tag" --repo "$repo" --pattern "$name-$tag.zip" --dir "$dir"
	unzip -q "$dir/$name-$tag.zip" -d "$dir/src"
	for abi in "${ABIS[@]}"; do
		local f
		f=$(find "$dir/src" -path "*/binaries/$(zapret_dir "$abi")/$bin" -type f | head -n1)
		[ -n "$f" ] || { echo "no $bin for $abi in $repo $tag"; exit 1; }
		install -m 755 "$f" "$OUT/libs/$abi/$bin"
	done
	# fake payloads (zapret2 wins on name clashes, it is fetched last)
	local fakes
	fakes=$(find "$dir/src" -path "*/files/fake" -type d | head -n1)
	[ -n "$fakes" ] && cp -f "$fakes"/*.bin "$OUT/files/fake/"
	if [ "$bin" = nfqws2 ]; then
		local lua
		lua=$(find "$dir/src" -name zapret-lib.lua -path '*/lua/*' | head -n1)
		[ -n "$lua" ] || { echo "no lua scripts in $repo"; exit 1; }
		cp -f "$(dirname "$lua")"/*.lua "$OUT/files/lua/"
	fi
}

# ------------------------------------------------------------------ byedpi (built with NDK)
build_byedpi() {
	local tag=${BYEDPI_TAG:-}
	[ -n "$tag" ] || tag=$(latest_tag hufrea/byedpi)
	echo "== hufrea/byedpi $tag"
	echo "byedpi=$tag" >> "$versions_file"
	local src="$WORK/byedpi"
	rm -rf "$src"
	git clone --depth 1 --branch "$tag" https://github.com/hufrea/byedpi.git "$src"
	local tc="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
	for abi in "${ABIS[@]}"; do
		make -C "$src" clean >/dev/null
		# CFLAGS/LDFLAGS go through the environment so the Makefile's own CFLAGS += still apply
		CFLAGS="-fPIE" LDFLAGS="-pie -s" make -C "$src" -j"$(nproc)" \
			CC="$tc/$(clang_target "$abi")$API-clang"
		install -m 755 "$src/ciadpi" "$OUT/libs/$abi/ciadpi"
	done
}

# ------------------------------------------------------------------ tg-ws-proxy-rs (built with cargo-ndk)
build_tgws() {
	local tag=${TGWS_TAG:-}
	[ -n "$tag" ] || tag=$(latest_tag valnesfjord/tg-ws-proxy-rs)
	echo "== valnesfjord/tg-ws-proxy-rs $tag"
	echo "tg-ws-proxy-rs=$tag" >> "$versions_file"
	local src="$WORK/tg-ws-proxy-rs"
	rm -rf "$src"
	git clone --depth 1 --branch "$tag" https://github.com/valnesfjord/tg-ws-proxy-rs.git "$src"
	(
		cd "$src"
		# the pinned toolchain from rust-toolchain.toml is installed on first use
		rustup show active-toolchain || rustup toolchain install
		rustup target add aarch64-linux-android armv7-linux-androideabi
		command -v cargo-ndk >/dev/null || cargo install cargo-ndk --locked
		CARGO_TARGET_DIR="$WORK/cargo-target" cargo ndk -t arm64-v8a -t armeabi-v7a --platform "$API" \
			build --release --locked --bin tg-ws-proxy
	)
	install -m 755 "$WORK/cargo-target/aarch64-linux-android/release/tg-ws-proxy" "$OUT/libs/arm64-v8a/tg-ws-proxy"
	install -m 755 "$WORK/cargo-target/armv7-linux-androideabi/release/tg-ws-proxy" "$OUT/libs/armeabi-v7a/tg-ws-proxy"
}

# ------------------------------------------------------------------ dnsproxy (official static Go builds, Apache-2.0)
fetch_dnsproxy() {
	local tag=${DNSPROXY_TAG:-}
	[ -n "$tag" ] || tag=$(latest_tag AdguardTeam/dnsproxy)
	echo "== AdguardTeam/dnsproxy $tag"
	echo "dnsproxy=$tag" >> "$versions_file"
	local dir="$WORK/dnsproxy"
	rm -rf "$dir"; mkdir -p "$dir"
	for abi in "${ABIS[@]}"; do
		local arch
		case "$abi" in
			arm64-v8a) arch=arm64 ;;
			armeabi-v7a) arch=arm7 ;;
		esac
		gh release download "$tag" --repo AdguardTeam/dnsproxy --pattern "dnsproxy-linux-$arch-$tag.tar.gz" --dir "$dir"
		mkdir -p "$dir/$arch"
		tar -xzf "$dir/dnsproxy-linux-$arch-$tag.tar.gz" -C "$dir/$arch"
		local f
		f=$(find "$dir/$arch" -name dnsproxy -type f | head -n1)
		[ -n "$f" ] || { echo "no dnsproxy for $abi"; exit 1; }
		install -m 755 "$f" "$OUT/libs/$abi/dnsproxy"
	done
}

fetch_zapret bol-van/zapret "${ZAPRET_TAG:-}" nfqws
fetch_zapret bol-van/zapret2 "${ZAPRET2_TAG:-}" nfqws2
build_byedpi
build_tgws
fetch_dnsproxy

echo "== result"
find "$OUT" -type f | sort
cat "$versions_file"
