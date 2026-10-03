# Manka module: DNS while the bypass is on. Sourced by manka.sh (POSIX sh), uses its paths and settings.

# DNS while bypass is on. The ISP resolver (and often plain DNS to public servers, which some
# ISPs intercept) answers blocked domains with a stub or nothing, then no strategy helps.
#   DNS_MODE=doh    local dnsproxy on 127.0.0.1:DNS_PORT forwarding to DNS-over-HTTPS (DNS_DOH),
#                   falls back to plain DNS_SERVER if dnsproxy does not start
#   DNS_MODE=plain  port 53 is sent to DNS_SERVER
#   DNS_MODE=system untouched
# IPv6 DNS is refused so the resolver uses IPv4. "Automatic" private DNS (DoT to the network's
# resolver) is covered too; a resolver picked by name (strict mode) is left alone.
# Waits (up to 15 s) until dnsproxy has its UDP port open. Redirecting DNS to a port nobody
# listens on yet makes Android mark the resolver broken, and it then stays unused for up to 30 min.
dns_listening() {
	_hex=$(printf ':%04X ' "$DNS_PORT")
	_i=0
	while [ $_i -lt 60 ]; do
		cat /proc/net/udp /proc/net/udp6 2>/dev/null | grep -q "$_hex" && return 0
		is_running dns || return 1
		sleep 0.25
		_i=$((_i + 1))
	done
	return 1
}

start_dnsproxy() {
	[ -x "$BIN/dnsproxy" ] || { log "dnsproxy missing, reinstall the module"; return 1; }
	_lis=127.0.0.1
	[ "$HOTSPOT" = 1 ] && _lis=0.0.0.0
	_conf="$_lis $DNS_PORT $DNS_DOH"
	if is_running dns && [ "$(cat "$RUN/dns.conf" 2>/dev/null)" = "$_conf" ]; then
		return 0
	fi
	set --
	for _u in $DNS_DOH; do set -- "$@" -u "$_u"; done
	[ $# -gt 0 ] || return 1
	# Go reads CA certificates from here (the APEX copy is the up-to-date one on Android 14+)
	_cd=
	for _d in /apex/com.android.conscrypt/cacerts /system/etc/security/cacerts; do
		[ -d "$_d" ] && _cd="$_cd:$_d"
	done
	export SSL_CERT_DIR="${_cd#:}"
	# the Go runtime keeps far more memory than a DNS forwarder needs (190 MB seen)
	export GOMEMLIMIT=48MiB GOGC=50
	# no hosts file: Android applies it before a query leaves the phone, and ad-block hosts files
	# (hundreds of thousands of lines) cost dnsproxy ~250 MB and seconds of CPU at every start
	start_daemon dns "$BIN/dnsproxy" "" -l "$_lis" -p "$DNS_PORT" --cache --cache-optimistic --timeout=5s \
		--hosts-file-enabled=false "$@"
	if [ "$(await_daemon dns | tail -n1)" = ok ] && dns_listening; then
		echo "$_conf" > "$RUN/dns.conf"
		return 0
	fi
	log "dnsproxy did not start: $(tail -n 3 "$LOGDIR/dns.log" 2>/dev/null | tr '\n' ' ')"
	stop_daemon dns
	rm -f "$RUN/dns.conf"
	return 1
}

setup_dns() {
	remove_dns
	_to=
	if [ "$DNS_MODE" = doh ] && start_dnsproxy; then
		_to=local
	else
		[ "$DNS_MODE" = doh ] || stop_dns
		[ "$DNS_MODE" = system ] || case "$DNS_SERVER" in *.*.*.*) _to=$DNS_SERVER ;; esac
	fi
	[ -n "$_to" ] || return 0
	_dot=0
	[ "$(settings get global private_dns_mode 2>/dev/null)" = hostname ] || _dot=1
	chain_init ipt nat MANKA_DNS OUTPUT || return 0
	ipt -t nat -A MANKA_DNS -d 127.0.0.0/8 -j RETURN
	if [ "$_to" = local ]; then
		ipt -t nat -A MANKA_DNS -p udp --dport 53 -j REDIRECT --to-ports "$DNS_PORT"
		ipt -t nat -A MANKA_DNS -p tcp --dport 53 -j REDIRECT --to-ports "$DNS_PORT"
		# DoT to the network's resolver is refused, the system then asks port 53 (redirected above)
		if [ $_dot = 1 ] && chain_init ipt filter MANKA_DNSF OUTPUT; then
			ipt -t filter -A MANKA_DNSF -p tcp --dport 853 -j REJECT --reject-with tcp-reset
		fi
	else
		ipt -t nat -A MANKA_DNS -d "$_to" -j RETURN
		ipt -t nat -A MANKA_DNS -p udp --dport 53 -j DNAT --to-destination "$_to:53"
		ipt -t nat -A MANKA_DNS -p tcp --dport 53 -j DNAT --to-destination "$_to:53"
		[ $_dot = 1 ] && ipt -t nat -A MANKA_DNS -p tcp --dport 853 -j DNAT --to-destination "$_to:853"
	fi
	if chain_init ip6t filter MANKA_DNS6 OUTPUT; then
		ip6t -t filter -A MANKA_DNS6 -o lo -j RETURN
		ip6t -t filter -A MANKA_DNS6 -p udp --dport 53 -j REJECT
		ip6t -t filter -A MANKA_DNS6 -p tcp --dport 53 -j REJECT --reject-with tcp-reset
		[ $_dot = 1 ] && ip6t -t filter -A MANKA_DNS6 -p tcp --dport 853 -j REJECT --reject-with tcp-reset
	fi
	# hotspot clients that ask a DNS server on the internet directly (the phone's own resolver is
	# already covered above, it runs on the phone)
	if [ "$HOTSPOT" = 1 ] && chain_init ipt nat MANKA_DNSP PREROUTING; then
		# without addrtype, DNS sent to the phone's own resolver address is redirected too, which is fine
		ipt -t nat -A MANKA_DNSP -m addrtype --dst-type LOCAL -j RETURN 2>/dev/null
		if [ "$_to" = local ]; then
			ipt -t nat -A MANKA_DNSP -p udp --dport 53 -j REDIRECT --to-ports "$DNS_PORT"
			ipt -t nat -A MANKA_DNSP -p tcp --dport 53 -j REDIRECT --to-ports "$DNS_PORT"
		else
			ipt -t nat -A MANKA_DNSP -d "$_to" -j RETURN
			ipt -t nat -A MANKA_DNSP -p udp --dport 53 -j DNAT --to-destination "$_to:53"
			ipt -t nat -A MANKA_DNSP -p tcp --dport 53 -j DNAT --to-destination "$_to:53"
		fi
		# hotspot clients asking over IPv6 are refused, they retry over IPv4 (redirected above)
		if chain_init ip6t filter MANKA_DNSF6 FORWARD; then
			ip6t -t filter -A MANKA_DNSF6 -p udp --dport 53 -j REJECT
			ip6t -t filter -A MANKA_DNSF6 -p tcp --dport 53 -j REJECT --reject-with tcp-reset
		fi
	fi
	return 0
}

remove_dns() {
	chain_del ip6t filter MANKA_DNSF6 FORWARD
	chain_del ipt nat MANKA_DNSP PREROUTING
	chain_del ipt nat MANKA_DNS OUTPUT
	chain_del ipt filter MANKA_DNSF OUTPUT
	chain_del ip6t filter MANKA_DNS6 OUTPUT
}

stop_dns() {
	remove_dns
	stop_daemon dns
	rm -f "$RUN/dns.conf" "$RUN/dns.failed"
}
