#!/usr/bin/env bash
# Builds Xray with only the parts Manka's "proxy for apps" uses: client protocols of the user's
# key, the redirect inbound, DNS and routing. The official build also carries the API commander,
# observatory, metrics, reverse proxy, server inbounds and the TOML / YAML / remote config loaders.
#   scripts/build-xray.sh <tag|latest> <goos> <goarch> <out>
# Needs: go (the version in Xray's go.mod), git, gh.
set -euo pipefail

tag=$1; goos=$2; goarch=$3; out=$(realpath -m "$4")
[ "$tag" = latest ] && tag=$(gh release view --repo XTLS/Xray-core --json tagName --jq .tagName)
src=$(realpath -m "${WORK_DIR:-build/work}/xray-src")
if [ "$(cat "$src/.manka-tag" 2>/dev/null)" != "$tag" ]; then
	rm -rf "$src"
	git clone -q --depth 1 --branch "$tag" https://github.com/XTLS/Xray-core.git "$src"
	echo "$tag" > "$src/.manka-tag"
fi

cat > "$src/main/distro/all/all.go" <<'EOF'
package all

// Manka build: what a client behind a transparent redirect needs (see scripts/build-xray.sh).
import (
	_ "github.com/xtls/xray-core/app/dispatcher"
	_ "github.com/xtls/xray-core/app/proxyman/inbound"
	_ "github.com/xtls/xray-core/app/proxyman/outbound"

	_ "github.com/xtls/xray-core/app/dns"
	_ "github.com/xtls/xray-core/app/log"
	_ "github.com/xtls/xray-core/app/policy"
	_ "github.com/xtls/xray-core/app/router"
	_ "github.com/xtls/xray-core/app/stats"

	_ "github.com/xtls/xray-core/transport/internet/tagged/taggedimpl"

	_ "github.com/xtls/xray-core/proxy/blackhole"
	_ "github.com/xtls/xray-core/proxy/dns"
	_ "github.com/xtls/xray-core/proxy/dokodemo"
	_ "github.com/xtls/xray-core/proxy/freedom"
	_ "github.com/xtls/xray-core/proxy/shadowsocks"
	_ "github.com/xtls/xray-core/proxy/trojan"
	_ "github.com/xtls/xray-core/proxy/vless/outbound"
	_ "github.com/xtls/xray-core/proxy/vmess/outbound"

	_ "github.com/xtls/xray-core/transport/internet/grpc"
	_ "github.com/xtls/xray-core/transport/internet/httpupgrade"
	_ "github.com/xtls/xray-core/transport/internet/reality"
	_ "github.com/xtls/xray-core/transport/internet/splithttp"
	_ "github.com/xtls/xray-core/transport/internet/tcp"
	_ "github.com/xtls/xray-core/transport/internet/tls"
	_ "github.com/xtls/xray-core/transport/internet/udp"
	_ "github.com/xtls/xray-core/transport/internet/websocket"

	_ "github.com/xtls/xray-core/transport/internet/headers/http"
	_ "github.com/xtls/xray-core/transport/internet/headers/noop"

	_ "github.com/xtls/xray-core/main/json"
)
EOF

(cd "$src" && CGO_ENABLED=0 GOOS=$goos GOARCH=$goarch \
	go build -trimpath -buildvcs=false -ldflags "-s -w -buildid=" -o "$out" ./main)
echo "xray $tag $goos/$goarch: $(stat -c %s "$out") bytes"
