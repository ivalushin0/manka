# Manka module: TG WS Proxy. Sourced by manka.sh (POSIX sh), uses its paths and settings.

tgws_sum() { echo "$(md5sum < "$ARGS/tgws.args" 2>/dev/null) $TGWS_PORT"; }

# Telegram's direct front (the --dc-ip addresses) is blocked altogether on some networks; the proxy
# would then lose its connect timeout on it again and again before falling back. The addresses are
# checked (start, network change, every 30 min) and left out while unreachable: Cloudflare only.
tgws_dc_ips() {
	awk 'take { sub(/^[0-9]+:/, ""); print; take = 0; next } $0 == "--dc-ip" { take = 1 }' "$ARGS/tgws.args" 2>/dev/null | sort -u
}

# 1: a direct address answers, 0: none does, empty: none configured
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

start_tgws() {
	_d=$(tgws_direct)
	if [ "$_d" = 0 ]; then
		awk 'skip { skip = 0; next } $0 == "--dc-ip" { skip = 1; next } { print }' "$ARGS/tgws.args" > "$RUN/tgws.args"
	else
		cp -f "$ARGS/tgws.args" "$RUN/tgws.args"
	fi
	echo "$_d" > "$RUN/tgws.direct"
	start_daemon tgws "$BIN/tg-ws-proxy" "$RUN/tgws.args" --host 127.0.0.1 --port "$TGWS_PORT"
	tgws_sum > "$RUN/tgws.sum"
	case "$_d" in
		0) log "tg-ws-proxy started on 127.0.0.1:$TGWS_PORT, direct addresses blocked here: Cloudflare only" ;;
		*) log "tg-ws-proxy started on 127.0.0.1:$TGWS_PORT" ;;
	esac
}

# ok / fail: can the proxy reach Telegram now (its direct front, else the Cloudflare domains);
# for the service status in the app
tgws_probe() {
	is_running tgws || { echo fail; return 0; }
	if [ "$(cat "$RUN/tgws.direct" 2>/dev/null)" = 1 ]; then
		for _ip in $(tgws_dc_ips); do
			timeout 4 nc -z -w 3 "$_ip" 443 >/dev/null 2>&1 && { echo ok; return 0; }
		done
	fi
	# a separate instance checks the Cloudflare domains of the running configuration
	set --
	while IFS= read -r _a || [ -n "$_a" ]; do
		[ -n "$_a" ] && [ "$_a" != -q ] && set -- "$@" "$_a"
	done < "$RUN/tgws.args"
	_n=$(timeout 45 "$BIN/tg-ws-proxy" "$@" --host 127.0.0.1 --port 1451 --check 2>&1 | grep -c '\[OK')
	if [ "${_n:-0}" -gt 0 ]; then echo ok; else echo fail; fi
}

# restarts the proxy when its direct addresses became (un)reachable
tgws_check() {
	[ "$TGWS" = 1 ] && is_running tgws || return 0
	_d=$(tgws_direct)
	[ "$_d" = "$(cat "$RUN/tgws.direct" 2>/dev/null)" ] && return 0
	log "telegram direct addresses: $([ "$_d" = 1 ] && echo reachable || echo blocked)"
	stop_daemon tgws
	start_tgws
}
