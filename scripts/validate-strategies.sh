#!/usr/bin/env bash
# Runs every built-in strategy through the real engines (Linux x86_64 builds):
# nfqws / nfqws2 with --dry-run, ciadpi for one second. Catches typos and options the
# engines do not know before they reach a phone.
#   scripts/validate-strategies.sh <dir with zapret.txt zapret2.txt byedpi.txt>
# Needs: gh (GH_TOKEN), unzip, gcc, sudo.
set -uo pipefail

LISTS=$(realpath "${1:-app/build/strategies}")
WORK=$(realpath -m build/validate)
rm -rf "$WORK"; mkdir -p "$WORK"

fetch() { # repo bin -> path of the linux x86_64 binary
	local repo=$1 bin=$2 name=${1#*/} tag
	tag=$(gh release view --repo "$repo" --json tagName --jq .tagName)
	gh release download "$tag" --repo "$repo" --pattern "$name-$tag.zip" --dir "$WORK/$name" >&2
	unzip -q "$WORK/$name/$name-$tag.zip" -d "$WORK/$name/src"
	find "$WORK/$name/src" -path "*/binaries/linux-x86_64/$bin" -type f | head -n1
}

NFQWS=$(fetch bol-van/zapret nfqws)
NFQWS2=$(fetch bol-van/zapret2 nfqws2)
chmod +x "$NFQWS" "$NFQWS2"

# the same layout strategies expect on the phone
sudo mkdir -p /data/adb/manka/files/fake /data/adb/manka/files/lua /data/adb/manka/lists
for z in zapret zapret2; do
	f=$(find "$WORK/$z/src" -path '*/files/fake' -type d | head -n1)
	[ -n "$f" ] && sudo cp -f "$f"/*.bin /data/adb/manka/files/fake/
done
LUA=$(dirname "$(find "$WORK/zapret2/src" -name zapret-lib.lua -path '*/lua/*' | head -n1)")
sudo cp -f "$LUA"/*.lua /data/adb/manka/files/lua/
echo example.com | sudo tee /data/adb/manka/lists/manka-exclude.txt >/dev/null

git clone -q --depth 1 https://github.com/hufrea/byedpi.git "$WORK/byedpi"
make -s -C "$WORK/byedpi"
CIADPI=$WORK/byedpi/ciadpi

fail=0
check() { # engine file
	local engine=$1 file=$2 n=0 bad=0
	while IFS= read -r line || [ -n "$line" ]; do
		[ -z "$line" ] && continue
		n=$((n + 1))
		IFS=$'\x1f' read -r -a args <<< "$line"
		case $engine in
			zapret) out=$(sudo "$NFQWS" --dry-run --qnum=1 --uid=0:0 "${args[@]}" 2>&1); rc=$? ;;
			zapret2) out=$(sudo "$NFQWS2" --dry-run --qnum=1 --uid=0:0 \
				--lua-init=@/data/adb/manka/files/lua/zapret-lib.lua \
				--lua-init=@/data/adb/manka/files/lua/zapret-antidpi.lua \
				--lua-init=@/data/adb/manka/files/lua/zapret-auto.lua "${args[@]}" 2>&1); rc=$? ;;
			byedpi) out=$(timeout 1 "$CIADPI" -i 127.0.0.1 -p 19999 "${args[@]}" 2>&1); rc=$?
				[ $rc = 124 ] && rc=0 ;;
		esac
		if [ $rc != 0 ]; then
			bad=$((bad + 1))
			echo "::error::$engine strategy failed (rc=$rc): ${args[*]}"
			echo "$out" | tail -n 5
		fi
	done < "$file"
	echo "== $engine: $((n - bad))/$n strategies accepted"
	[ $bad = 0 ] || fail=1
}

check zapret "$LISTS/zapret.txt"
check zapret2 "$LISTS/zapret2.txt"
check zapret2 "$LISTS/zapret2-legacy.txt"
check byedpi "$LISTS/byedpi.txt"

# ByeByeDPI list is downloaded by the app at run time; report problems without failing the build
hard_fail=$fail
if curl -fsSL https://raw.githubusercontent.com/romanvht/ByeByeDPI/master/app/src/main/assets/proxytest_strategies.list -o "$WORK/bbd.raw"; then
	grep -E '^-' "$WORK/bbd.raw" | sed 's/{sni}/www.google.com/g' | tr ' ' '\037' > "$WORK/byebyedpi.txt"
	check byedpi "$WORK/byebyedpi.txt"
fi
exit $hard_fail
