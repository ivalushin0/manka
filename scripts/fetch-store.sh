#!/usr/bin/env bash
# Snapshots the CDPIUI store (index + zapret config kits) into the APK assets,
# so Manka works out of the box without network access to GitHub.
#   scripts/fetch-store.sh <assets_dir>
# Needs: gh (GH_TOKEN), jq, unzip, curl.
set -euo pipefail

ASSETS=$(realpath -m "${1:-app/src/main/assets}")
STORE="$ASSETS/store"
WORK=$(realpath -m "${WORK_DIR:-build/work}/store")

rm -rf "$STORE" "$WORK"
mkdir -p "$STORE/index" "$STORE/kits" "$WORK"

curl -fsSL -o "$WORK/store.zip" https://github.com/Storik4pro/CDPIUI-Store/archive/refs/heads/main.zip
unzip -q "$WORK/store.zip" -d "$WORK"
SRC=$(find "$WORK" -maxdepth 1 -type d -name 'CDPIUI-Store-*' | head -n1)

# index: every init.json, description and string table
(cd "$SRC" && find . -type f \( -name '*.json' -o -name '*.md' \) -print0 | while IFS= read -r -d '' f; do
	mkdir -p "$STORE/index/$(dirname "$f")"
	cp "$f" "$STORE/index/$f"
done)

# config kits: latest release asset of every "configlist" item
for init in "$SRC"/Configs/*/init.json; do
	type=$(jq -r '.type // empty' "$init" 2>/dev/null || true)
	[ "$type" = configlist ] || continue
	id=$(jq -r '.store_id' "$init")
	link=$(jq -r '.version_control_link' "$init")
	repo=$(echo "$link" | sed -E 's#https://github.com/##; s#/+$##')
	echo "== kit $id from $repo"
	tag=$(gh release view --repo "$repo" --json tagName --jq .tagName 2>/dev/null || true)
	if [ -z "$tag" ]; then
		echo "   no release, skipped"
		continue
	fi
	asset=$(gh release view "$tag" --repo "$repo" --json assets --jq '[.assets[] | select(.name | endswith(".zip"))][0].name')
	if [ -z "$asset" ] || [ "$asset" = null ]; then
		echo "   no zip asset, skipped"
		continue
	fi
	gh release download "$tag" --repo "$repo" --pattern "$asset" --output "$STORE/kits/$id.zip" --clobber
	echo "$tag" > "$STORE/kits/$id.version"
done

date -u +%Y-%m-%dT%H:%M:%SZ > "$STORE/snapshot.txt"
du -sh "$STORE"
