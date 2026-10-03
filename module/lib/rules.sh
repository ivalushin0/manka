# Manka module: kernel capabilities and iptables rules of the bypass. Sourced by manka.sh (POSIX sh), uses its paths and settings.

# ---------------------------------------------------------------- capabilities

probe_caps() {
	HAS_MP=0; HAS_CB=0; HAS_NFQ=0; HAS_NAT6=0; HAS_LEN=0
	ipt -t mangle -N MANKA_PROBE 2>/dev/null
	ipt -t mangle -F MANKA_PROBE 2>/dev/null
	ipt -t mangle -A MANKA_PROBE -p tcp -m multiport --dports 1,2 -j RETURN 2>/dev/null && HAS_MP=1
	ipt -t mangle -A MANKA_PROBE -m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes 1:2 -j RETURN 2>/dev/null && HAS_CB=1
	ipt -t mangle -A MANKA_PROBE -p tcp --dport 1 -j NFQUEUE --queue-num 1 --queue-bypass 2>/dev/null && HAS_NFQ=1
	ipt -t mangle -A MANKA_PROBE -m length --length 81:65535 -j RETURN 2>/dev/null && HAS_LEN=1
	ipt -t mangle -F MANKA_PROBE 2>/dev/null
	ipt -t mangle -X MANKA_PROBE 2>/dev/null
	ip6t -t nat -L OUTPUT -n >/dev/null 2>&1 && HAS_NAT6=1
	echo "HAS_MP=$HAS_MP HAS_CB=$HAS_CB HAS_NFQ=$HAS_NFQ HAS_NAT6=$HAS_NAT6 HAS_LEN=$HAS_LEN" > "$RUN/caps"
}

load_caps() {
	if [ -f "$RUN/caps" ]; then
		for _kv in $(cat "$RUN/caps"); do eval "$_kv"; done
	else
		probe_caps
	fi
}

# ---------------------------------------------------------------- iptables helpers

# chain_init CMD TABLE CHAIN PARENT: create/flush CHAIN and jump to it from PARENT (first rule)
chain_init() {
	$1 -t "$2" -N "$3" 2>/dev/null
	$1 -t "$2" -F "$3" || return 1
	$1 -t "$2" -C "$4" -j "$3" 2>/dev/null || $1 -t "$2" -I "$4" 1 -j "$3"
}

# chain_del CMD TABLE CHAIN PARENT
chain_del() {
	while $1 -t "$2" -D "$4" -j "$3" 2>/dev/null; do :; done
	$1 -t "$2" -F "$3" 2>/dev/null
	$1 -t "$2" -X "$3" 2>/dev/null
}

# add_ports CMD TABLE CHAIN PROTO dports|sports PORTS RULE...
# PORTS: comma separated, ranges as a:b. Falls back to one rule per port without multiport.
add_ports() {
	_cmd=$1; _tbl=$2; _ch=$3; _proto=$4; _dir=$5; _ports=$6
	shift 6
	[ -z "$_ports" ] && return 0
	if [ "$HAS_MP" = 1 ]; then
		$_cmd -t "$_tbl" -A "$_ch" -p "$_proto" -m multiport "--$_dir" "$_ports" "$@" && return 0
	fi
	_one=--dport
	[ "$_dir" = sports ] && _one=--sport
	for _p in $(echo "$_ports" | tr ',' ' '); do
		$_cmd -t "$_tbl" -A "$_ch" -p "$_proto" $_one "$_p" "$@"
	done
}

# skip_private CMD TABLE CHAIN dst|src
skip_private() {
	_nets=$PRIVATE4
	[ "$1" = ip6t ] && _nets=$PRIVATE6
	_flag=-d
	[ "$4" = src ] && _flag=-s
	for _n in $_nets; do
		$1 -t "$2" -A "$3" $_flag "$_n" -j RETURN
	done
}

families() {
	if [ "$IPV6" = 1 ]; then echo "ipt ip6t"; else echo "ipt"; fi
}

# uids the bypass never touches: root daemons, the network stack and the apps that go through
# Xray (a strategy's fake packets would land inside their proxied connections)
skip_uids() {
	echo "$SYS_UIDS"
	[ "$PROXY" = 1 ] && echo "$PROXY_UIDS"
}

# app_gate CMD TABLE CHAIN SUBCHAIN: sends the traffic of the right apps from CHAIN to SUBCHAIN.
#   APPS_MODE=exclude: every app except APP_UIDS    APPS_MODE=only: only APP_UIDS
app_gate() {
	$1 -t "$2" -N "$4" 2>/dev/null
	$1 -t "$2" -F "$4"
	if [ "$APPS_MODE" = only ]; then
		for _u in $APP_UIDS; do
			$1 -t "$2" -A "$3" -m owner --uid-owner "$_u" -j "$4"
		done
	else
		for _u in $APP_UIDS; do
			$1 -t "$2" -A "$3" -m owner --uid-owner "$_u" -j RETURN
		done
		$1 -t "$2" -A "$3" -j "$4"
	fi
}

# nfq_out CMD CHAIN: outgoing NFQUEUE rules (uses _jq, PKT_* and caps)
nfq_out() {
	if [ "$HAS_CB" = 1 ]; then
		add_ports $1 mangle $2 tcp dports "$TCP_PORTS" -m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes "1:$PKT_OUT" $_jq
		add_ports $1 mangle $2 udp dports "$UDP_PORTS" -m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes "1:$PKT_OUT_UDP" $_jq
	else
		# No connbytes in this kernel (typical for GKI). Keep the userspace load low anyway:
		# SYN and packets that carry data go to nfqws, pure ACKs of downloads do not.
		add_ports $1 mangle $2 tcp dports "$TCP_PORTS" --tcp-flags SYN,ACK,FIN,RST SYN $_jq
		if [ "$HAS_LEN" = 1 ]; then
			add_ports $1 mangle $2 tcp dports "$TCP_PORTS" -m length --length 81:65535 $_jq
		else
			add_ports $1 mangle $2 tcp dports "$TCP_PORTS" $_jq
		fi
		add_ports $1 mangle $2 udp dports "$UDP_PORTS" $_jq
	fi
}

# setup_nfq QNUM main|test [UID] : NFQUEUE rules for zapret / zapret2
setup_nfq() {
	_q=$1; _mode=$2; _tuid=$3
	if [ "$_mode" = test ]; then _o=MANKA_TOUT; _oq=MANKA_TOUT; _i=MANKA_TIN; else _o=MANKA_OUT; _oq=MANKA_OUTQ; _i=MANKA_IN; fi
	_jq="-j NFQUEUE --queue-num $_q --queue-bypass"
	for _c in $(families); do
		chain_init $_c mangle $_o OUTPUT || continue
		chain_init $_c mangle $_i PREROUTING
		$_c -t mangle -A $_o -o lo -j RETURN
		$_c -t mangle -A $_o -m mark --mark 0x40000000/0x40000000 -j RETURN
		skip_private $_c mangle $_o dst
		if [ "$_mode" = test ]; then
			$_c -t mangle -A $_o -m owner ! --uid-owner "$_tuid" -j RETURN
		else
			# Xray's direct connections (proxied apps, sites not sent to the server) are marked:
			# they get the bypass like any app although Xray runs as root
			$_c -t mangle -N $_oq 2>/dev/null
			$_c -t mangle -A $_o -m mark --mark $DIRECT_MARK/$DIRECT_MARK -j $_oq
			for _u in $(skip_uids); do $_c -t mangle -A $_o -m owner --uid-owner $_u -j RETURN; done
			app_gate $_c mangle $_o $_oq
		fi
		nfq_out $_c $_oq
		# devices on the phone's hotspot: their traffic is forwarded, not sent by an app
		if [ "$_mode" = main ] && [ "$HOTSPOT" = 1 ] && chain_init $_c mangle MANKA_FWD FORWARD; then
			$_c -t mangle -A MANKA_FWD -m mark --mark 0x40000000/0x40000000 -j RETURN
			skip_private $_c mangle MANKA_FWD dst
			nfq_out $_c MANKA_FWD
		fi
		$_c -t mangle -A $_i -i lo -j RETURN
		skip_private $_c mangle $_i src
		if [ "$HAS_CB" = 1 ]; then
			add_ports $_c mangle $_i tcp sports "$TCP_PORTS" -m connbytes --connbytes-dir=reply --connbytes-mode=packets --connbytes "1:$PKT_IN" $_jq
			add_ports $_c mangle $_i udp sports "$UDP_PORTS" -m connbytes --connbytes-dir=reply --connbytes-mode=packets --connbytes "1:$PKT_IN" $_jq
		else
			add_ports $_c mangle $_i tcp sports "$TCP_PORTS" --tcp-flags SYN,ACK SYN,ACK $_jq
		fi
	done
}

setup_byedpi_rules() {
	for _c in $(families); do
		_port=$BYEDPI_PORT
		if [ $_c = ip6t ]; then
			[ "$HAS_NAT6" = 1 ] || continue
			_port=$((BYEDPI_PORT + 1))
		fi
		chain_init $_c nat MANKA_NAT OUTPUT || continue
		$_c -t nat -A MANKA_NAT -o lo -j RETURN
		skip_private $_c nat MANKA_NAT dst
		$_c -t nat -N MANKA_NATQ 2>/dev/null
		$_c -t nat -A MANKA_NAT -m mark --mark $DIRECT_MARK/$DIRECT_MARK -j MANKA_NATQ
		# ciadpi itself (and other root daemons) must not be looped back into ciadpi
		for _u in $(skip_uids); do $_c -t nat -A MANKA_NAT -m owner --uid-owner $_u -j RETURN; done
		app_gate $_c nat MANKA_NAT MANKA_NATQ
		add_ports $_c nat MANKA_NATQ tcp dports "$BYEDPI_PORTS" -j REDIRECT --to-ports "$_port"
	done
	# hotspot clients (IPv4): ciadpi also listens on 0.0.0.0, the guard keeps it off the LAN
	if [ "$HOTSPOT" = 1 ] && chain_init ipt nat MANKA_NATP PREROUTING; then
		# no addrtype match in some kernels: then connections to the phone itself are redirected too
		ipt -t nat -A MANKA_NATP -m addrtype --dst-type LOCAL -j RETURN 2>/dev/null
		skip_private ipt nat MANKA_NATP dst
		add_ports ipt nat MANKA_NATP tcp dports "$BYEDPI_PORTS" -j REDIRECT --to-ports "$BYEDPI_PORT"
	fi
}

# QUIC block and, for ByeDPI when ip6tables has no nat table, a TCP reset for IPv6
# so apps fall back to IPv4 (which goes through ciadpi) instead of bypassing it.
setup_filter() {
	for _c in $(families); do
		_v6reset=0
		[ $_c = ip6t ] && [ "$ENGINE" = byedpi ] && [ "$HAS_NAT6" != 1 ] && _v6reset=1
		[ "$BLOCK_QUIC" = 1 ] || [ $_v6reset = 1 ] || continue
		chain_init $_c filter MANKA_FLT OUTPUT || continue
		$_c -t filter -A MANKA_FLT -o lo -j RETURN
		app_gate $_c filter MANKA_FLT MANKA_FLTQ
		[ "$BLOCK_QUIC" = 1 ] && $_c -t filter -A MANKA_FLTQ -p udp --dport 443 -j REJECT
		if [ $_v6reset = 1 ]; then
			skip_private $_c filter MANKA_FLTQ dst
			for _u in $(skip_uids); do $_c -t filter -A MANKA_FLTQ -m owner --uid-owner $_u -j RETURN; done
			add_ports $_c filter MANKA_FLTQ tcp dports "$BYEDPI_PORTS" -j REJECT --reject-with tcp-reset
		fi
		# the same for hotspot clients
		if [ "$HOTSPOT" = 1 ] && chain_init $_c filter MANKA_FFLT FORWARD; then
			[ "$BLOCK_QUIC" = 1 ] && $_c -t filter -A MANKA_FFLT -p udp --dport 443 -j REJECT
			if [ $_v6reset = 1 ]; then
				skip_private $_c filter MANKA_FFLT dst
				add_ports $_c filter MANKA_FFLT tcp dports "$BYEDPI_PORTS" -j REJECT --reject-with tcp-reset
			fi
		fi
	done
}

# Hotspot mode: ciadpi / dnsproxy listen on all addresses for the redirected hotspot traffic.
# Only connections that were redirected here (or come from the phone itself) may reach them.
setup_guard() {
	chain_del ipt filter MANKA_GUARD INPUT
	[ "$HOTSPOT" = 1 ] || return 0
	chain_init ipt filter MANKA_GUARD INPUT || return 0
	ipt -t filter -A MANKA_GUARD -i lo -j RETURN
	ipt -t filter -A MANKA_GUARD -m conntrack --ctstate DNAT -j RETURN || log "no conntrack match, hotspot redirects are refused"
	ipt -t filter -A MANKA_GUARD -p tcp --dport "$BYEDPI_PORT" -j DROP
	ipt -t filter -A MANKA_GUARD -p tcp --dport "$DNS_PORT" -j DROP
	ipt -t filter -A MANKA_GUARD -p udp --dport "$DNS_PORT" -j DROP
	return 0
}

remove_main_rules() {
	for _c in ipt ip6t; do
		chain_del $_c mangle MANKA_OUT OUTPUT
		chain_del $_c mangle MANKA_IN PREROUTING
		chain_del $_c mangle MANKA_FWD FORWARD
		chain_del $_c nat MANKA_NAT OUTPUT
		chain_del $_c nat MANKA_NATP PREROUTING
		chain_del $_c filter MANKA_FLT OUTPUT
		chain_del $_c filter MANKA_FFLT FORWARD
		# gated sub-chains, no longer referenced now
		for _sub in mangle:MANKA_OUTQ nat:MANKA_NATQ filter:MANKA_FLTQ; do
			$_c -t "${_sub%%:*}" -F "${_sub#*:}" 2>/dev/null
			$_c -t "${_sub%%:*}" -X "${_sub#*:}" 2>/dev/null
		done
	done
}

remove_test_rules() {
	for _c in ipt ip6t; do
		chain_del $_c mangle MANKA_TOUT OUTPUT
		chain_del $_c mangle MANKA_TIN PREROUTING
	done
}

rules_ok() {
	case "$ENGINE" in
		zapret|zapret2) ipt -t mangle -C OUTPUT -j MANKA_OUT 2>/dev/null ;;
		byedpi) ipt -t nat -C OUTPUT -j MANKA_NAT 2>/dev/null ;;
		*) return 0 ;;
	esac
}
