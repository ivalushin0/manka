# tgwsproxy (vendor)

The proxy runtime that serves Telegram in the module. A CLI build of the Go core from
[Regstar2/tgwsproxy-core](https://github.com/Regstar2/tgwsproxy-core) — the native runtime of the
`TgWsProxy` Android app ([Regstar2/tg-ws-proxy-android](https://github.com/Regstar2/tg-ws-proxy-android)),
based on [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).

- Upstream commit: `d288f6557eab5a621c9f63c34eb9d6fa72fcb4f9` (2026-11-20, "sync Android routing and
  CF Proxy WS/80 switch")
- License: GPL-3.0 (see `LICENSE` in this directory); it replaces the MIT license of Manka itself,
  because the module distributes this code.

## Why it is here

The Rust port (`tg-ws-proxy-rs`) cannot serve Telegram on networks where the ISP blocks the
WebSocket front by SNI and address: its `cf-only` mode accepts clients but never opens an upstream,
and its domain list is fetched from GitHub synchronously at start. This Go core is the engine of the
app that works on such a network: Cloudflare proxy over plaintext `ws://` on port 80, an ordered
route policy (`cf_proxy_ws → worker_ws → direct_ws → tcp_fallback`), AWG/WARP as a fallback, a cached
domain list.

## Patches against upstream

1. The cgo/JNA bridge (`import "C"`, `//export` functions) is removed: the module runs the core as a
   plain static binary, not through an `.so` + JNA.
2. The standalone `main()` gets a real MTProto mode: `--mtproto --secret <hex> --runtime '<tokens>'`.
   Upstream's `main()` only runs the SOCKS proxy.
3. The proxy resolves names through an ordered list of resolvers, given with `--dns host[:port]`
   (repeatable, comma-separated entries allowed): an Android build without cgo has no
   `/etc/resolv.conf`, so the pure-Go resolver dials `[::1]:53` and every lookup fails. The module
   passes its dnsproxy (`127.0.0.1:10853`) first and plain public servers as a fallback, because a
   DoH upstream may be blocked or not up yet after boot/network change; the next resolver is tried
   when the previous one fails. `net.DefaultResolver` is pointed at the first entry for AWG code.
4. `cachedUpstreamDNSCooldownSeconds` is 30 s instead of 6 hours (`tgwsroute/cfpool.go`). All of
   Manka's CF domains come from `@cf_cached_domains`, i.e. are "cached upstreams"; upstream then
   benches every one of them for six hours after a single DNS failure. A DNS hiccup while mobile
   data is coming up at boot therefore left Telegram with a green module and no route at all until
   the proxy was restarted. The normal progressive cooldown (30/120/300 s) still applies to real
   domain failures.

Build: `scripts/build-tgws.sh <out_dir>` (pure Go, `CGO_ENABLED=0`, `GOOS=linux`, arm64 + armv7).
