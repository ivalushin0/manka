# Manka module: TG WS Proxy. Sourced by manka.sh (POSIX sh), uses its paths and settings.
#
# The proxy is the Go core of the TgWsProxy app (vendor/tgws): Cloudflare proxy over plaintext
# ws:// on port 80 with an ordered route policy. On this network the ISP blocks Telegram's own
# WebSocket front by SNI and address, and the old Rust port could not fall back at all.
#
# The app writes two files: args/tgws.secret (the MTProto secret) and args/tgws.runtime (the runtime
# tokens of the core: DC map, @connection_mode=cf_only, @cfproxy_no_tls=1, route policy, cached CF
# domains). The proxy runs as the shell user, whose traffic the bypass covers — as root (SYS_UIDS)
# the DPI cuts its TLS, and as an app it would lose the network in the background.
TGWS_UID=2000
TGWS_RUNTIME=/data/local/tmp/manka

# Resolvers the core tries in order: the module's dnsproxy, the plain server of the same provider,
# then public ones. dnsproxy may not be up yet right after boot or a network change, and its DoH
# upstream can time out on a mobile network; a lookup failure must not leave Telegram without a
# route (dns.sh excludes this uid from the DNS redirect, so a fallback really reaches that server).
tgws_dns_args() {
	# Yandex's plain DNS first among the fallbacks: public DoH/plain endpoints of Google and
	# Cloudflare are often cut on Russian mobile networks, this one is not
	_d="--dns 127.0.0.1:$DNS_PORT --dns 77.88.8.8:53"
	case "$DNS_SERVER" in
		""|77.88.8.8) ;;
		*.*.*.*) _d="$_d --dns $DNS_SERVER:53" ;;
	esac
	[ "$DNS_SERVER" = 8.8.8.8 ] || _d="$_d --dns 8.8.8.8:53"
	echo "$_d"
}

tgws_sum() {
	echo "$(md5sum < "$ARGS/tgws.runtime" 2>/dev/null) $(md5sum < "$ARGS/tgws.secret" 2>/dev/null) $(md5sum < "$BIN/tgwsproxy" 2>/dev/null) $TGWS_PORT $(tgws_dns_args)"
}

tgws_direct() {
	# the core handles the routes itself; keep the marker for the app's older status field
	echo 1
}

# Copies the proxy where the shell user can execute it, only when it changed (a module update).
tgws_prepare() {
	mkdir -p "$TGWS_RUNTIME"
	[ "$(md5sum < "$BIN/tgwsproxy" 2>/dev/null)" = "$(md5sum < "$TGWS_RUNTIME/tgwsproxy" 2>/dev/null)" ] && return 0
	cp -f "$BIN/tgwsproxy" "$TGWS_RUNTIME/tgwsproxy" && chmod 0755 "$TGWS_RUNTIME/tgwsproxy"
}

start_tgws() {
	tgws_prepare
	_secret=$(cat "$ARGS/tgws.secret" 2>/dev/null)
	_runtime=$(cat "$ARGS/tgws.runtime" 2>/dev/null)
	if [ ! -x "$TGWS_RUNTIME/tgwsproxy" ] || [ -z "$_secret" ] || [ -z "$_runtime" ]; then
		log "tgws: no binary/secret/runtime (open Manka and apply the settings)"
		echo 1 > "$RUN/tgws.failed"
		return 1
	fi
	cp -f "$ARGS/tgws.runtime" "$RUN/tgws.runtime" 2>/dev/null
	echo 1 > "$RUN/tgws.direct"
	set -- --mtproto --host 127.0.0.1 --port "$TGWS_PORT" --secret "$_secret" --runtime "$_runtime"
	for _d in $(tgws_dns_args); do set -- "$@" "$_d"; done
	start_daemon tgws "$MODDIR/tgws-run.sh" "" "$@"
	tgws_sum > "$RUN/tgws.sum"
	log "tgwsproxy started on 127.0.0.1:$TGWS_PORT as uid $TGWS_UID"
}

# A listening port alone is not health: the core keeps its own CF domain pool and may have every
# domain in cooldown while the module is green. Report the process and what its log says about routes.
tgws_probe() {
	is_running tgws || { echo "fail: process is not running"; return 0; }
	if ! ss -tln 2>/dev/null | grep -q ":$TGWS_PORT "; then
		echo "fail: port $TGWS_PORT is not listening"
		return 0
	fi
	_p=$(cat "$RUN/tgws.pid" 2>/dev/null)
	_up=0
	if [ -n "$_p" ] && [ -r "/proc/$_p/stat" ]; then
		# starttime is field 22 of /proc/pid/stat (20 after the comm field), in 100 Hz ticks on Android
		_start=$(sed 's/.*) //' "/proc/$_p/stat" | cut -d' ' -f20)
		_now=$(cut -d' ' -f1 /proc/uptime | cut -d. -f1)
		[ -n "$_start" ] && [ -n "$_now" ] && _up=$(( _now - _start / 100 ))
		[ "$_up" -ge 0 ] 2>/dev/null || _up=0
	fi
	_ok=$(grep -c 'reason=connected' "$LOGDIR/tgws.log" 2>/dev/null)
	_bad=$(grep -c 'reason=cfproxy_unavailable' "$LOGDIR/tgws.log" 2>/dev/null)
	echo "ok: pid $_p, port $TGWS_PORT, uptime ${_up}s"
	echo "routes: connected=${_ok:-0}, unavailable=${_bad:-0}"
	_last=$(grep 'CF domain cooldown' "$LOGDIR/tgws.log" 2>/dev/null | tail -n 1)
	[ -n "$_last" ] && echo "last cooldown: ${_last#*cooldown }"
}

# The proxy keeps its sockets of the previous network, so it is restarted when the network changes;
# a probe result alone never restarts it.
tgws_restart() {
	stop_daemon tgws
	[ "$TGWS" = 1 ] && start_tgws
	rm -f "$RUN/tgws.failed"
}

tgws_net_ok() {
	_src=$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.* src \([^ ]*\).*/\1/p' | head -n1)
	[ -n "$_src" ] || return 0
	_p=$(cat "$RUN/tgws.pid" 2>/dev/null)
	[ -n "$_p" ] || return 0
	_socks=$(ss -tnp 2>/dev/null | grep "pid=$_p," | awk '$4 ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:/ { print $4 }')
	[ -n "$_socks" ] || return 0
	echo "$_socks" | grep -q "^$_src:" && return 0
	return 1
}

tgws_check() {
	[ "$TGWS" = 1 ] && is_running tgws || return 0
	if ! tgws_net_ok; then
		log "telegram: proxy sockets are on the old network, restarting"
		tgws_restart
	fi
}
