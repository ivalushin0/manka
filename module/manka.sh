#!/system/bin/sh
# Manka control script. Runs as root; POSIX sh only (works under mksh and busybox ash).
#
#   manka.sh start | stop | restart | status | boot
#   manka.sh tgws-restart | engine-stop
#   manka.sh test-start <zapret|zapret2|byedpi> <argsfile> <uid>
#   manka.sh test-stop
#
# Engines read their strategy from $DATA/args/<name>.args (one argument per line),
# runtime settings come from $DATA/settings.conf (written by the Manka app).

SELF=$(readlink -f "$0")
MODDIR=${SELF%/*}
BIN=$MODDIR/bin
DATA=/data/adb/manka
FILES=$DATA/files
RUN=$DATA/run
LOGDIR=$DATA/logs
ARGS=$DATA/args
PROFILES=$DATA/profiles

mkdir -p "$RUN" "$LOGDIR" "$ARGS"

# ---- defaults, overridden by settings.conf ----
ENABLED=0
ENGINE=none
TGWS=0
TGWS_PORT=1443
QNUM=200
TEST_QNUM=210
TCP_PORTS=80,443
UDP_PORTS=
BLOCK_QUIC=1
IPV6=1
BYEDPI_PORT=10801
BYEDPI_TEST_PORT=10899
BYEDPI_PORTS=80,443
APPS_MODE=exclude
APP_UIDS=
DEBUG=0
# DNS while bypass is on, see setup_dns
DNS_MODE=
DNS_SERVER=
DNS_DOH=
DNS_PORT=5353
[ -f "$DATA/settings.conf" ] && . "$DATA/settings.conf"
# settings.conf from app versions before APPS_MODE
[ -z "$APP_UIDS" ] && [ -n "$EXCLUDE_UIDS" ] && APP_UIDS=$EXCLUDE_UIDS
# settings.conf from app versions before DNS_MODE
[ -z "$DNS_MODE" ] && { [ -n "$DNS_SERVER" ] && DNS_MODE=plain || DNS_MODE=system; }

PRIVATE4="10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 169.254.0.0/16 100.64.0.0/10 127.0.0.0/8"
PRIVATE6="fc00::/7 fe80::/10 ::1/128"

log() {
	_lf=$LOGDIR/manka.log
	if [ -f "$_lf" ] && [ "$(wc -c < "$_lf")" -gt 262144 ]; then
		mv -f "$_lf" "$_lf.old"
	fi
	echo "$(date '+%F %T') $*" >> "$_lf"
}

ipt() { iptables -w "$@"; }
ip6t() { ip6tables -w "$@"; }

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
			app_gate $_c mangle $_o $_oq
		fi
		$_c -t mangle -A $_i -i lo -j RETURN
		skip_private $_c mangle $_i src
		if [ "$HAS_CB" = 1 ]; then
			add_ports $_c mangle $_oq tcp dports "$TCP_PORTS" -m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes "1:$PKT_OUT" $_jq
			add_ports $_c mangle $_oq udp dports "$UDP_PORTS" -m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes "1:$PKT_OUT_UDP" $_jq
			add_ports $_c mangle $_i tcp sports "$TCP_PORTS" -m connbytes --connbytes-dir=reply --connbytes-mode=packets --connbytes "1:$PKT_IN" $_jq
			add_ports $_c mangle $_i udp sports "$UDP_PORTS" -m connbytes --connbytes-dir=reply --connbytes-mode=packets --connbytes "1:$PKT_IN" $_jq
		else
			# No connbytes in this kernel (typical for GKI). Keep the userspace load low anyway:
			# SYN and packets that carry data go to nfqws, pure ACKs of downloads do not.
			add_ports $_c mangle $_oq tcp dports "$TCP_PORTS" --tcp-flags SYN,ACK,FIN,RST SYN $_jq
			if [ "$HAS_LEN" = 1 ]; then
				add_ports $_c mangle $_oq tcp dports "$TCP_PORTS" -m length --length 81:65535 $_jq
			else
				add_ports $_c mangle $_oq tcp dports "$TCP_PORTS" $_jq
			fi
			add_ports $_c mangle $_oq udp dports "$UDP_PORTS" $_jq
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
		# ciadpi itself (and other root daemons) must not be looped back into ciadpi
		$_c -t nat -A MANKA_NAT -m owner --uid-owner 0 -j RETURN
		app_gate $_c nat MANKA_NAT MANKA_NATQ
		add_ports $_c nat MANKA_NATQ tcp dports "$BYEDPI_PORTS" -j REDIRECT --to-ports "$_port"
	done
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
			$_c -t filter -A MANKA_FLTQ -m owner --uid-owner 0 -j RETURN
			add_ports $_c filter MANKA_FLTQ tcp dports "$BYEDPI_PORTS" -j REJECT --reject-with tcp-reset
		fi
	done
}

remove_main_rules() {
	for _c in ipt ip6t; do
		chain_del $_c mangle MANKA_OUT OUTPUT
		chain_del $_c mangle MANKA_IN PREROUTING
		chain_del $_c nat MANKA_NAT OUTPUT
		chain_del $_c filter MANKA_FLT OUTPUT
		# gated sub-chains, no longer referenced now
		for _sub in mangle:MANKA_OUTQ nat:MANKA_NATQ filter:MANKA_FLTQ; do
			$_c -t "${_sub%%:*}" -F "${_sub#*:}" 2>/dev/null
			$_c -t "${_sub%%:*}" -X "${_sub#*:}" 2>/dev/null
		done
	done
}

# DNS while bypass is on. The ISP resolver (and often plain DNS to public servers, which some
# ISPs intercept) answers blocked domains with a stub or nothing, then no strategy helps.
#   DNS_MODE=doh    local dnsproxy on 127.0.0.1:DNS_PORT forwarding to DNS-over-HTTPS (DNS_DOH),
#                   falls back to plain DNS_SERVER if dnsproxy does not start
#   DNS_MODE=plain  port 53 is sent to DNS_SERVER
#   DNS_MODE=system untouched
# IPv6 DNS is refused so the resolver uses IPv4. "Automatic" private DNS (DoT to the network's
# resolver) is covered too; a resolver picked by name (strict mode) is left alone.
start_dnsproxy() {
	[ -x "$BIN/dnsproxy" ] || { log "dnsproxy missing, reinstall the module"; return 1; }
	_conf="$DNS_PORT $DNS_DOH"
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
	start_daemon dns "$BIN/dnsproxy" "" -l 127.0.0.1 -p "$DNS_PORT" --cache --cache-optimistic --timeout=5s "$@"
	if [ "$(await_daemon dns | tail -n1)" = ok ]; then
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
	return 0
}

remove_dns() {
	chain_del ipt nat MANKA_DNS OUTPUT
	chain_del ipt filter MANKA_DNSF OUTPUT
	chain_del ip6t filter MANKA_DNS6 OUTPUT
}

stop_dns() {
	remove_dns
	stop_daemon dns
	rm -f "$RUN/dns.conf" "$RUN/dns.failed"
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

# ---------------------------------------------------------------- daemons

pid_of() { [ -f "$RUN/$1.pid" ] && cat "$RUN/$1.pid"; }

is_running() {
	_p=$(pid_of "$1")
	[ -n "$_p" ] && kill -0 "$_p" 2>/dev/null
}

# supervise NAME BINARY ARGSFILE [EXTRA ARGS...] -- runs in its own session, restarts the daemon on crash
supervise() {
	_name=$1; _bin=$2; _af=$3
	shift 3
	echo $$ > "$RUN/$_name.sup"
	if [ -f "$_af" ]; then
		while IFS= read -r _a || [ -n "$_a" ]; do
			[ -n "$_a" ] && set -- "$@" "$_a"
		done < "$_af"
	fi
	# engines are quiet unless they fail, so stderr is always kept (last run only)
	_log=$LOGDIR/$_name.log
	: > "$_log"
	_fails=0
	while [ ! -f "$RUN/$_name.stop" ]; do
		_t0=$(date +%s)
		[ "$(wc -c < "$_log" 2>/dev/null || echo 0)" -gt 1048576 ] && : > "$_log"
		"$_bin" "$@" </dev/null >>"$_log" 2>&1 &
		echo $! > "$RUN/$_name.pid"
		wait $!
		_rc=$?
		[ -f "$RUN/$_name.stop" ] && break
		log "$_name exited with code $_rc"
		if [ "$_name" = test ]; then
			echo "$_rc" > "$RUN/$_name.failed"
			break
		fi
		if [ $(( $(date +%s) - _t0 )) -lt 15 ]; then _fails=$((_fails + 1)); else _fails=0; fi
		if [ $_fails -ge 5 ]; then
			log "$_name keeps crashing, giving up"
			echo "$_rc" > "$RUN/$_name.failed"
			break
		fi
		sleep $((_fails * 2 + 1))
	done
	rm -f "$RUN/$_name.pid" "$RUN/$_name.sup"
}

start_daemon() {
	stop_daemon "$1"
	rm -f "$RUN/$1.stop" "$RUN/$1.failed"
	if command -v setsid >/dev/null 2>&1; then
		setsid sh "$SELF" _supervise "$@" </dev/null >/dev/null 2>&1 &
	else
		nohup sh "$SELF" _supervise "$@" </dev/null >/dev/null 2>&1 &
	fi
}

stop_daemon() {
	touch "$RUN/$1.stop"
	[ -f "$RUN/$1.sup" ] && kill "$(cat "$RUN/$1.sup")" 2>/dev/null
	_p=$(pid_of "$1")
	if [ -n "$_p" ]; then
		kill "$_p" 2>/dev/null
		_i=0
		while [ $_i -lt 15 ] && kill -0 "$_p" 2>/dev/null; do
			sleep 0.2
			_i=$((_i + 1))
		done
		kill -9 "$_p" 2>/dev/null
	fi
	rm -f "$RUN/$1.pid" "$RUN/$1.sup"
}

# wait until daemon is up (or died); prints ok, or the daemon output and fail
daemon_failed() {
	tail -n 20 "$LOGDIR/$1.log" 2>/dev/null | sed "s/^/err: /"
	echo fail
	return 1
}

await_daemon() {
	_i=0
	while [ $_i -lt 10 ]; do
		[ -f "$RUN/$1.failed" ] && { daemon_failed "$1"; return 1; }
		is_running "$1" && break
		sleep 0.1
		_i=$((_i + 1))
	done
	sleep 0.3
	if is_running "$1"; then echo ok; else daemon_failed "$1"; fi
}

lua_init() {
	for _l in zapret-lib.lua zapret-antidpi.lua zapret-auto.lua; do
		if [ -f "$FILES/lua/$_l" ]; then
			echo "--lua-init=@$FILES/lua/$_l"
		else
			log "missing $FILES/lua/$_l, reinstall the module"
		fi
	done
}

# ---------------------------------------------------------------- network profiles
#   profiles/mobile.conf        mobile data
#   profiles/wifi.conf          any Wi-Fi without its own profile
#   profiles/wifi_<md5>.conf    one Wi-Fi network (md5 of the SSID, first 8 hex chars)
# Each .conf sets ENGINE / TCP_PORTS / UDP_PORTS, the strategy is in the matching .args.

# The network Android routes through: wifi | mobile | keep (VPN on top) | none
current_net() {
	_dev=$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.* dev \([^ ]*\).*/\1/p' | head -n1)
	[ -z "$_dev" ] && _dev=$(ip -6 route get 2606:4700:4700::1111 2>/dev/null | sed -n 's/.* dev \([^ ]*\).*/\1/p' | head -n1)
	case "$_dev" in
		wlan*|swlan*|wifi*|eth*|p2p*) echo wifi ;;
		tun*|ppp*|ipsec*|wg*) echo keep ;;
		"")
			if ip -4 addr show wlan0 2>/dev/null | grep -q 'inet '; then echo wifi; else echo none; fi
			;;
		*) echo mobile ;;
	esac
}

current_ssid() {
	_s=$(cmd wifi status 2>/dev/null | sed -n 's/.*SSID: "\([^"]*\)".*/\1/p' | head -n1)
	[ -z "$_s" ] && _s=$(dumpsys wifi 2>/dev/null | sed -n 's/.*mWifiInfo SSID: "\([^"]*\)".*/\1/p' | head -n1)
	case "$_s" in "<unknown ssid>") _s= ;; esac
	printf '%s' "$_s"
}

# own profile key of the current network (the last one while on VPN / offline)
current_key() {
	case "$(current_net)" in
		mobile) echo mobile ;;
		wifi)
			_s=$(current_ssid)
			if [ -n "$_s" ]; then
				echo "wifi_$(printf '%s' "$_s" | md5sum | cut -c1-8)"
			else
				echo wifi
			fi
			;;
		*) cat "$RUN/key" 2>/dev/null || echo wifi ;;
	esac
}

# select_profile KEY: loads the profile of KEY, or the common Wi-Fi profile
select_profile() {
	KEY=$1
	_k=$KEY
	[ -f "$PROFILES/$_k.conf" ] || _k=wifi
	if [ -f "$PROFILES/$_k.conf" ]; then
		. "$PROFILES/$_k.conf"
		PROFILE=$_k
		PROFILE_ARGS=$PROFILES/$_k.args
	else
		# settings written by an older app version
		PROFILE=legacy
		PROFILE_ARGS=$ARGS/$ENGINE.args
	fi
}

# ---------------------------------------------------------------- engines

start_engine() {
	case "$ENGINE" in
		zapret)
			PKT_OUT=6; PKT_OUT_UDP=6; PKT_IN=3
			start_daemon zapret "$BIN/nfqws" "$PROFILE_ARGS" "--qnum=$QNUM" --uid=0:0
			setup_nfq "$QNUM" main
			;;
		zapret2)
			PKT_OUT=20; PKT_OUT_UDP=5; PKT_IN=10
			# shellcheck disable=SC2046
			start_daemon zapret2 "$BIN/nfqws2" "$PROFILE_ARGS" "--qnum=$QNUM" --uid=0:0 $(lua_init)
			setup_nfq "$QNUM" main
			;;
		byedpi)
			start_daemon byedpi "$BIN/ciadpi" "$PROFILE_ARGS" -E -i 127.0.0.1 -p "$BYEDPI_PORT"
			if [ "$IPV6" = 1 ] && [ "$HAS_NAT6" = 1 ]; then
				start_daemon byedpi6 "$BIN/ciadpi" "$PROFILE_ARGS" -E -i ::1 -p "$((BYEDPI_PORT + 1))"
			fi
			setup_byedpi_rules
			;;
		*) return 0 ;;
	esac
	setup_filter
	echo "$KEY" > "$RUN/key"
	echo "$PROFILE" > "$RUN/profile"
	echo "$ENGINE" > "$RUN/engine"
	log "engine $ENGINE started, profile $PROFILE ($KEY)"
}

stop_engine() {
	for _n in zapret zapret2 byedpi byedpi6; do
		stop_daemon $_n
	done
	remove_main_rules
	rm -f "$RUN/engine"
}

start_tgws() {
	start_daemon tgws "$BIN/tg-ws-proxy" "$ARGS/tgws.args" --host 127.0.0.1 --port "$TGWS_PORT"
	log "tg-ws-proxy started on 127.0.0.1:$TGWS_PORT"
}


# Follows route changes (no polling) and switches the profile when the network changes.
netwatch() {
	_t0=$(date +%s)
	if ! ip monitor route 2>/dev/null | while read -r _line; do
		[ -f "$RUN/testing" ] && continue
		[ "$(current_key)" = "$(cat "$RUN/key" 2>/dev/null)" ] && continue
		# let the routing settle, then re-check
		sleep 2
		_k=$(current_key)
		[ "$_k" = "$(cat "$RUN/key" 2>/dev/null)" ] && continue
		log "network changed: $_k"
		sh "$SELF" net-apply </dev/null >/dev/null 2>&1
	done; then
		:
	fi
	# ip monitor ended after working for a while: let the supervisor restart it
	[ $(( $(date +%s) - _t0 )) -gt 60 ] && exit 1
	# ip monitor is unavailable: stay idle instead of being restarted in a loop
	log "ip monitor unavailable, automatic profile switching is off"
	while :; do sleep 86400; done
}

# ---------------------------------------------------------------- commands

cmd_start() {
	probe_caps
	rm -f "$RUN/testing"
	stop_engine
	if [ "$ENABLED" = 1 ]; then
		setup_dns
		select_profile "$(current_key)"
		start_engine
		is_running netwatch || start_daemon netwatch /system/bin/sh "" "$SELF" _netwatch
	else
		stop_dns
		stop_daemon netwatch
	fi
	if [ "$TGWS" = 1 ]; then
		is_running tgws || start_tgws
	else
		stop_daemon tgws
	fi
	cmd_status
}

# restart the engine only if the network (profile) changed
cmd_net_apply() {
	[ "$ENABLED" = 1 ] || return 0
	[ -f "$RUN/testing" ] && return 0
	load_caps
	_k=$(current_key)
	if [ "$_k" = "$(cat "$RUN/key" 2>/dev/null)" ] && [ -f "$RUN/engine" ] && is_running "$(cat "$RUN/engine")"; then
		return 0
	fi
	stop_engine
	select_profile "$_k"
	start_engine
}

cmd_stop() {
	stop_daemon netwatch
	stop_dns
	stop_engine
	remove_test_rules
	stop_daemon test
	stop_daemon tgws
	rm -f "$RUN/testing"
	log "stopped"
	cmd_status
}

cmd_status() {
	load_caps
	_running_engine=$(cat "$RUN/engine" 2>/dev/null)
	[ -n "$_running_engine" ] && ENGINE=$_running_engine
	echo "module_dir=$MODDIR"
	echo "module_version=$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null)"
	echo "enabled=$ENABLED"
	echo "engine=$ENGINE"
	echo "profile=$(cat "$RUN/profile" 2>/dev/null)"
	echo "key=$(cat "$RUN/key" 2>/dev/null)"
	_nt=$(current_net)
	echo "net_type=$_nt"
	[ "$_nt" = wifi ] && echo "ssid=$(current_ssid)"
	_er=0
	case "$ENGINE" in
		zapret|zapret2|byedpi) is_running "$ENGINE" && _er=1 ;;
	esac
	echo "engine_running=$_er"
	_ro=0
	rules_ok && _ro=1
	echo "rules_ok=$_ro"
	_tr=0
	is_running tgws && _tr=1
	echo "tgws=$TGWS"
	echo "tgws_running=$_tr"
	_nw=0
	is_running netwatch && _nw=1
	echo "netwatch_running=$_nw"
	_dr=0
	is_running dns && _dr=1
	echo "dns_mode=$DNS_MODE"
	echo "dns_running=$_dr"
	_failed=
	for _f in "$RUN"/*.failed; do
		[ -f "$_f" ] || continue
		_n=${_f##*/}
		_failed="$_failed ${_n%.failed}"
	done
	echo "failed=${_failed# }"
	cat "$RUN/caps" 2>/dev/null | tr ' ' '\n'
}

cmd_test_start() {
	_engine=$1; _af=$2; _uid=$3
	touch "$RUN/testing"
	setup_dns
	stop_engine
	stop_daemon test
	load_caps
	case "$_engine" in
		zapret)
			PKT_OUT=6; PKT_OUT_UDP=6; PKT_IN=3
			start_daemon test "$BIN/nfqws" "$_af" "--qnum=$TEST_QNUM" --uid=0:0
			TCP_PORTS=80,443; UDP_PORTS=; setup_nfq "$TEST_QNUM" test "$_uid"
			;;
		zapret2)
			PKT_OUT=20; PKT_OUT_UDP=5; PKT_IN=10
			# shellcheck disable=SC2046
			start_daemon test "$BIN/nfqws2" "$_af" "--qnum=$TEST_QNUM" --uid=0:0 $(lua_init)
			TCP_PORTS=80,443; UDP_PORTS=; setup_nfq "$TEST_QNUM" test "$_uid"
			;;
		byedpi)
			# plain SOCKS5 mode, the app talks to it directly
			remove_test_rules
			start_daemon test "$BIN/ciadpi" "$_af" -i 127.0.0.1 -p "$BYEDPI_TEST_PORT"
			;;
		*) echo fail; return 1 ;;
	esac
	await_daemon test
}

cmd_test_stop() {
	stop_daemon test
	remove_test_rules
	[ "$ENABLED" = 1 ] || stop_dns
	echo ok
}

cmd_boot() {
	log "boot"
	probe_caps
	rm -f "$RUN/testing" "$RUN/key" "$RUN/profile"
	if [ "$ENABLED" = 1 ]; then
		setup_dns
		select_profile "$(current_key)"
		start_engine
		start_daemon netwatch /system/bin/sh "" "$SELF" _netwatch
	fi
	[ "$TGWS" = 1 ] && start_tgws
}

case "$1" in
	start|restart|apply) cmd_start ;;
	stop) cmd_stop ;;
	engine-stop)
		# stop DPI bypass only (auto selection), TG WS Proxy keeps running
		touch "$RUN/testing"
		stop_daemon test
		remove_test_rules
		stop_engine
		echo ok
		;;
	net-apply) cmd_net_apply ;;
	status) cmd_status ;;
	boot) cmd_boot ;;
	tgws-restart)
		stop_daemon tgws
		[ "$TGWS" = 1 ] && start_tgws
		cmd_status
		;;
	test-start) shift; cmd_test_start "$@" ;;
	test-stop) cmd_test_stop ;;
	_supervise) shift; supervise "$@" ;;
	_netwatch) netwatch ;;
	*)
		echo "usage: $0 start|stop|restart|status|boot|net-apply|tgws-restart|test-start ENGINE ARGSFILE UID|test-stop"
		exit 1
		;;
esac
