# Manka module: TG WS Proxy. Sourced by manka.sh (POSIX sh), uses its paths and settings.

# The proxy runs as the shell user, not as root: root (SYS_UIDS) is kept out of the bypass, and
# then DPI cuts the TLS of the proxy to Telegram's own front and to the Cloudflare domains — the
# proxy retries, marks the address dead for a minute and Telegram barely connects. As uid 2000 its
# traffic gets the strategy and both paths work. tgws-run.sh does the switch; the binary has to
# live where that user can execute it (/data/adb is root-only).
TGWS_UID=2000
TGWS_RUNTIME=/data/local/tmp/manka

# The restart also covers an updated proxy binary: a module update replaces it.
tgws_sum() {
	echo "$(md5sum < "$ARGS/tgws.args" 2>/dev/null) $(md5sum < "$BIN/tg-ws-proxy" 2>/dev/null) $TGWS_PORT"
}

tgws_dc_ips() {
	awk 'take { sub(/^[0-9]+:/, ""); print; take = 0; next } $0 == "--dc-ip" { take = 1 }' "$ARGS/tgws.args" 2>/dev/null | sort -u
}

# 1: a direct address answers on TCP, 0: none does, empty: none configured. Only a hint for the
# label: the TCP handshake is allowed even where the WebSocket to the front is cut.
tgws_direct() {
	_ips=$(tgws_dc_ips)
	[ -n "$_ips" ] || return 0
	for _ip in $_ips; do
		for _i in 1 2; do
			timeout 3 nc -z -w 2 "$_ip" 443 >/dev/null 2>&1 && { echo 1; return 0; }
		done
	done
	echo 0
}

# Copies the proxy where the shell user can execute it, only when it changed (a module update).
tgws_prepare() {
	mkdir -p "$TGWS_RUNTIME"
	[ "$(md5sum < "$BIN/tg-ws-proxy" 2>/dev/null)" = "$(md5sum < "$TGWS_RUNTIME/tg-ws-proxy" 2>/dev/null)" ] && return 0
	cp -f "$BIN/tg-ws-proxy" "$TGWS_RUNTIME/tg-ws-proxy" && chmod 0755 "$TGWS_RUNTIME/tg-ws-proxy"
}

start_tgws() {
	tgws_prepare
	# the direct addresses stay in the arguments: the proxy falls back to Cloudflare by itself
	# (--ip-fail-cooldown), and removing them meant a restart that cut every connection
	cp -f "$ARGS/tgws.args" "$RUN/tgws.args"
	tgws_direct > "$RUN/tgws.direct"
	start_daemon tgws "$MODDIR/tgws-run.sh" "$RUN/tgws.args" --host 127.0.0.1 --port "$TGWS_PORT"
	tgws_sum > "$RUN/tgws.sum"
	log "tg-ws-proxy started on 127.0.0.1:$TGWS_PORT as uid $TGWS_UID"
}

# ok / fail: can the proxy reach Telegram now; for the service status in the app
tgws_probe() {
	is_running tgws || { echo fail; return 0; }
	tgws_prepare
	# a separate instance as the same user checks the Cloudflare domains of the running configuration
	set --
	while IFS= read -r _a || [ -n "$_a" ]; do
		[ -n "$_a" ] && [ "$_a" != -q ] && set -- "$@" "$_a"
	done < "$RUN/tgws.args"
	CMD=
	for _a in "$@"; do
		_a=$(printf '%s' "$_a" | sed "s/'/'\\\\''/g")
		CMD="$CMD '$_a'"
	done
	_n=$(timeout 45 su $TGWS_UID -G 3003 -c "exec $TGWS_RUNTIME/tg-ws-proxy$CMD --host 127.0.0.1 --port 1451 --check" 2>&1 | grep -c '\[OK')
	[ "${_n:-0}" -gt 0 ] && { echo ok; return 0; }
	# no Cloudflare domain answered: the direct front may still be the working path (--check does
	# not cover it), so a TCP answer on it counts
	for _ip in $(tgws_dc_ips); do
		timeout 4 nc -z -w 3 "$_ip" 443 >/dev/null 2>&1 && { echo ok; return 0; }
	done
	echo fail
}

# No restarts for a probe result any more: they cut every connection of the app, and the proxy
# handles a dead direct address itself. The probe only refreshes the label of the running settings.
tgws_check() {
	[ "$TGWS" = 1 ] && is_running tgws || return 0
	tgws_direct > "$RUN/tgws.direct"
}
