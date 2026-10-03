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

# The kits are made for the Windows CDPI UI: Windows programs and drivers, test tools, backups and
# notes come along (one kit: a 10 MB backup of its host list). Presets name every file they use
# literally, so a file no preset mentions is dropped; the same rule as KitCleaner in the app.
clean_kit() { # src.zip dst.zip
	local tmp="$WORK/kit-clean" refs="$WORK/kit-refs.txt" before after
	rm -rf "$tmp"; mkdir -p "$tmp"
	unzip -q "$1" -d "$tmp"
	find "$tmp" -type f -iname '*.json' -exec cat {} + | tr 'A-Z' 'a-z' > "$refs"
	before=$(du -sk "$tmp" | cut -f1)
	find "$tmp" -type f | while IFS= read -r f; do
		local rel=${f#"$tmp"/} name ext dirs junk=0
		name=$(basename "$f"); ext=$(echo "${name##*.}" | tr 'A-Z' 'a-z')
		dirs="/$(dirname "$rel" | tr 'A-Z' 'a-z')/"
		case "$dirs" in */bak/*|*/backup/*|*/backups/*|*/utils/*|*"/test results/"*) junk=1 ;; esac
		case "$ext" in sys|exe|dll|ps1|bat|cmd|vbs|lnk|reg|msi) junk=1 ;; esac
		if [ $junk = 0 ]; then
			case "$name" in
				*.json|*.JSON|*.md|*.MD|LICENSE*|license*|README*|readme*) ;;
				*) grep -qiF -- "$name" "$refs" || junk=1 ;;
			esac
		fi
		[ $junk = 1 ] && rm -f "$f"
	done
	find "$tmp" -depth -type d -empty -delete
	after=$(du -sk "$tmp" | cut -f1)
	echo "   cleaned: ${before} KB -> ${after} KB"
	rm -f "$2"
	(cd "$tmp" && zip -qr9 "$2" .)
}

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
	gh release download "$tag" --repo "$repo" --pattern "$asset" --output "$WORK/$id.zip" --clobber
	clean_kit "$WORK/$id.zip" "$STORE/kits/$id.zip"
	echo "$tag" > "$STORE/kits/$id.version"
done

date -u +%Y-%m-%dT%H:%M:%SZ > "$STORE/snapshot.txt"
du -sh "$STORE"
