# Manka module: proxy for apps (Xray). Sourced by manka.sh (POSIX sh), uses its paths and settings.

# ---------------------------------------------------------------- proxy for apps (Xray)
# TCP of PROXY_UIDS is redirected to Xray, which sends the chosen domains to the user's server (a
# foreign exit address, e.g. for services closed to Russia) and the rest out directly. Their QUIC
# and IPv6 TCP are refused so the apps fall back to TCP over IPv4; other UDP (calls of a watch behind
# its companion app) and DNS stay as they are. Works with the bypass on or off, like TG WS Proxy.

remove_proxy_rules() {
	chain_del ipt nat MANKA_PRX OUTPUT
	chain_del ipt filter MANKA_PRXF OUTPUT
	chain_del ip6t filter MANKA_PRXF OUTPUT
	chain_del ipt filter MANKA_PRXG INPUT
}

setup_proxy_rules() {
	remove_proxy_rules
	chain_init ipt nat MANKA_PRX OUTPUT || return 1
	ipt -t nat -A MANKA_PRX -o lo -j RETURN
	skip_private ipt nat MANKA_PRX dst
	for _c in ipt ip6t; do
		chain_init $_c filter MANKA_PRXF OUTPUT || continue
		$_c -t filter -A MANKA_PRXF -o lo -j RETURN
		skip_private $_c filter MANKA_PRXF dst
	done
	for _u in $PROXY_UIDS; do
		ipt -t nat -A MANKA_PRX -p tcp -m owner --uid-owner "$_u" -j REDIRECT --to-ports "$PROXY_PORT"
		ipt -t filter -A MANKA_PRXF -p udp --dport 443 -m owner --uid-owner "$_u" -j REJECT
		ip6t -t filter -A MANKA_PRXF -p tcp -m owner --uid-owner "$_u" -j REJECT --reject-with tcp-reset
		ip6t -t filter -A MANKA_PRXF -p udp --dport 443 -m owner --uid-owner "$_u" -j REJECT
	done
	# only redirected connections may use Xray, other apps cannot reach the server through its port
	if chain_init ipt filter MANKA_PRXG INPUT; then
		ipt -t filter -A MANKA_PRXG -p tcp --dport "$PROXY_PORT" -m conntrack ! --ctstate DNAT -j DROP 2>/dev/null ||
			log "no conntrack match, the proxy port is not guarded"
	fi
}

proxy_sum() { echo "$(md5sum < "$DATA/proxy.json" 2>/dev/null)"; }

stop_proxy() {
	remove_proxy_rules
	stop_daemon proxy
	rm -f "$RUN/proxy.sum" "$RUN/proxy.failed"
}

# (re)starts Xray when needed and sets up the redirect; rules only while Xray runs, otherwise
# the apps would lose the network altogether
start_proxy() {
	if [ "$PROXY" != 1 ] || [ -z "$PROXY_UIDS" ] || [ ! -f "$DATA/proxy.json" ]; then
		stop_proxy
		return 0
	fi
	if [ ! -x "$BIN/xray" ]; then
		log "proxy: no Xray for this CPU (64-bit ARM only)"
		stop_proxy
		echo 1 > "$RUN/proxy.failed"
		return 1
	fi
	if ! is_running proxy || [ "$(proxy_sum)" != "$(cat "$RUN/proxy.sum" 2>/dev/null)" ]; then
		remove_proxy_rules
		_cd=
		for _d in /apex/com.android.conscrypt/cacerts /system/etc/security/cacerts; do
			[ -d "$_d" ] && _cd="$_cd:$_d"
		done
		export SSL_CERT_DIR="${_cd#:}"
		export GOMEMLIMIT=64MiB GOGC=50
		start_daemon proxy "$BIN/xray" "" run -c "$DATA/proxy.json"
		if [ "$(await_daemon proxy | tail -n1)" != ok ]; then
			log "proxy did not start: $(tail -n 3 "$LOGDIR/proxy.log" 2>/dev/null | tr '\n' ' ')"
			stop_daemon proxy
			echo 1 > "$RUN/proxy.failed"
			return 1
		fi
		proxy_sum > "$RUN/proxy.sum"
		log "proxy started for uids $PROXY_UIDS"
	fi
	setup_proxy_rules
}
