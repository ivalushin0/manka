#!/usr/bin/env bash
# Checks the Xray configs built by ProxyConfigTest with the real Xray (Linux x86_64 build of it).
#   scripts/validate-proxy.sh <dir with *.json>
# Needs: gh (GH_TOKEN), unzip, go.
set -uo pipefail

DIR=$(realpath "${1:-app/build/proxy}")
WORK=$(realpath -m build/validate-proxy)
rm -rf "$WORK"; mkdir -p "$WORK"
tag=$(gh release view --repo XTLS/Xray-core --json tagName --jq .tagName)
# the same slim build the phone gets (scripts/build-xray.sh), the official one if it fails there too
if ! { command -v go >/dev/null && WORK_DIR="$WORK" bash "$(dirname "$0")/build-xray.sh" "$tag" linux amd64 "$WORK/xray"; }; then
	gh release download "$tag" --repo XTLS/Xray-core --pattern "Xray-linux-64.zip" --dir "$WORK"
	unzip -q "$WORK/Xray-linux-64.zip" xray -d "$WORK"
fi
chmod +x "$WORK/xray"
"$WORK/xray" version | head -n1

fail=0
n=0
for f in "$DIR"/*.json; do
	[ -f "$f" ] || continue
	n=$((n + 1))
	if out=$("$WORK/xray" run -test -c "$f" 2>&1); then
		echo "ok   $(basename "$f")"
	else
		echo "FAIL $(basename "$f")"
		echo "$out" | tail -n 5
		fail=1
	fi
done
[ $n -gt 0 ] || { echo "no configs in $DIR"; exit 1; }
exit $fail
