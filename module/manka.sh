#!/system/bin/sh
# Manka control script. Runs as root; POSIX sh only (works under mksh and busybox ash).
#
#   manka.sh start | stop | restart | status | boot
#   manka.sh tgws-restart | tgws-check | engine-stop | net-apply
#   manka.sh test-start <zapret|zapret2|byedpi> <argsfile> <uid>
#   manka.sh test-stop
#
# Engines read their strategy from $DATA/args/<name>.args (one argument per line),
# runtime settings come from $DATA/settings.conf (written by the Manka app). The functions are
# in lib/ by topic: rules, dns, daemons, engine, tgws, proxy, meta, netwatch.

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
# bypass + DNS for devices on the phone's hotspot / USB / Bluetooth tethering
HOTSPOT=0
# DNS while bypass is on, see setup_dns
DNS_MODE=
DNS_SERVER=
DNS_DOH=
DNS_PORT=10853
# never bypassed: root daemons (TG WS Proxy, dnsproxy, ciadpi) need no bypass and a strategy only
# disturbs them; 1073 is the network stack, whose disturbed connectivity check leaves Wi-Fi
# "without internet" and the phone on mobile data
SYS_UIDS="0 1073"
# proxy for apps: PROXY_UIDS go through Xray ($DATA/proxy.json) to the user's own server
PROXY=0
PROXY_UIDS=
PROXY_PORT=10820
# WhatsApp: its chat servers in the old Meta ranges are blocked by address, the WhatsApp edges in
# 57.144.0.0/14 are not. META_FIX=1 sends the TCP of META_UIDS (the WhatsApp apps) to those ranges
# to a reachable edge (checked at start / network change) and refuses their Meta IPv6 (partly
# blocked, no IPv6 NAT here). The bypass is still needed, host names are blocked as well.
META_FIX=0
META_UIDS=
META_NETS="31.13.24.0/21 31.13.64.0/18 157.240.0.0/16 179.60.192.0/22 185.60.216.0/22 129.134.0.0/16 163.70.128.0/17 69.171.224.0/19 66.220.144.0/20 69.63.176.0/20 173.252.64.0/18 102.132.96.0/20 45.64.40.0/22 204.15.20.0/22"
META_EDGES="57.144.249.32 57.144.248.34 57.144.173.32 57.144.245.33 57.144.244.34 57.144.172.34 57.144.249.33"
META_NETS6="2a03:2880::/32"
# socket mark of Xray's direct connections (see setup_nfq); Android keeps the network id in the
# low 16 bits and zapret marks its own packets with 0x40000000
DIRECT_MARK=0x20000000
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

# the functions live in lib/, one file per topic
for _lib in "$MODDIR"/lib/*.sh; do . "$_lib"; done

# ---------------------------------------------------------------- commands

cmd_start() {
	probe_caps
	rm -f "$RUN/testing"
	if [ "$ENABLED" = 1 ]; then
		setup_dns
		select_profile "$(current_key)"
		if engine_current; then
			log "engine $ENGINE unchanged, kept running"
		else
			stop_engine
			start_engine
		fi
		setup_guard
		setup_meta
		start_daemon netwatch /system/bin/sh "" "$SELF" _netwatch
	else
		stop_engine
		stop_dns
		chain_del ipt filter MANKA_GUARD INPUT
		remove_meta
		stop_daemon netwatch
	fi
	if [ "$TGWS" = 1 ]; then
		# a running proxy keeps its connections, unless its settings changed (e.g. an app update)
		if ! is_running tgws; then
			start_tgws
		elif [ "$(tgws_sum)" != "$(cat "$RUN/tgws.sum" 2>/dev/null)" ]; then
			stop_daemon tgws
			start_tgws
		fi
	else
		stop_daemon tgws
	fi
	start_proxy
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
	# the reachable Meta edge and Telegram's direct addresses may differ on the new network
	setup_meta
	tgws_check
}

cmd_stop() {
	stop_daemon netwatch
	stop_dns
	chain_del ipt filter MANKA_GUARD INPUT
	remove_meta
	stop_engine
	remove_test_rules
	stop_daemon test
	stop_daemon tgws
	stop_proxy
	rm -f "$RUN/testing"
	log "stopped"
	cmd_status
}

# Daemon logs only shrink when a daemon restarts; a long-running chatty one (debug logs on)
# is cut here, the daemon keeps appending to the emptied file.
trim_logs() {
	for _f in "$LOGDIR"/*.log; do
		[ -f "$_f" ] || continue
		[ "$(wc -c < "$_f")" -gt 2097152 ] && : > "$_f"
	done
}

cmd_status() {
	load_caps
	trim_logs
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
	echo "tgws_direct=$(cat "$RUN/tgws.direct" 2>/dev/null)"
	_pr=0
	is_running proxy && ipt -t nat -C OUTPUT -j MANKA_PRX 2>/dev/null && _pr=1
	echo "proxy=$PROXY"
	echo "proxy_running=$_pr"
	[ -x "$BIN/xray" ] && echo proxy_available=1 || echo proxy_available=0
	_nw=0
	is_running netwatch && _nw=1
	echo "netwatch_running=$_nw"
	_dr=0
	is_running dns && _dr=1
	echo "dns_mode=$DNS_MODE"
	echo "dns_running=$_dr"
	echo "hotspot=$HOTSPOT"
	echo "meta_ip=$(cat "$RUN/meta_ip" 2>/dev/null)"
	[ -f "$RUN/testing" ] && echo testing=1
	# CPU share (x100, since the process started) and memory of every daemon
	_hz=$(getconf CLK_TCK 2>/dev/null || echo 100)
	_up=$(cut -d. -f1 /proc/uptime)
	for _n in zapret zapret2 byedpi byedpi6 tgws dns netwatch proxy; do
		_p=$(pid_of $_n)
		[ -n "$_p" ] && [ -r "/proc/$_p/stat" ] || continue
		_cs=$(sed 's/^.*) //' "/proc/$_p/stat" | awk -v hz="$_hz" -v up="$_up" '{ el = up * hz - $20; if (el < 1) el = 1; printf "%d", ($12 + $13) * 10000 / el }')
		echo "cpu_$_n=$_cs"
		echo "mem_$_n=$(awk '/^VmRSS:/ { print $2 }' "/proc/$_p/status")"
	done
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
		setup_guard
		setup_meta
		start_daemon netwatch /system/bin/sh "" "$SELF" _netwatch
	fi
	[ "$TGWS" = 1 ] && start_tgws
	start_proxy
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
	tgws-check) tgws_check ;;
	tgws-probe) tgws_probe ;;
	test-start) shift; cmd_test_start "$@" ;;
	test-stop) cmd_test_stop ;;
	_supervise) shift; supervise "$@" ;;
	_netwatch) netwatch ;;
	*)
		echo "usage: $0 start|stop|restart|status|boot|net-apply|tgws-restart|test-start ENGINE ARGSFILE UID|test-stop"
		exit 1
		;;
esac
