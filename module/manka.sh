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
FILES=$DATA/files
DATA=/data/adb/manka
RUN=$DATA/run
LOGDIR=$DATA/logs
ARGS=$DATA/args

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
EXCLUDE_UIDS=
DEBUG=0
[ -f "$DATA/settings.conf" ] && . "$DATA/settings.conf"

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

# setup_nfq QNUM main|test [UID] : NFQUEUE rules for zapret / zapret2
setup_nfq() {
	_q=$1; _mode=$2; _tuid=$3
	if [ "$_mode" = test ]; then _o=MANKA_TOUT; _i=MANKA_TIN; else _o=MANKA_OUT; _i=MANKA_IN; fi
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
			for _u in $EXCLUDE_UIDS; do
				$_c -t mangle -A $_o -m owner --uid-owner "$_u" -j RETURN
			done
		fi
		$_c -t mangle -A $_i -i lo -j RETURN
		skip_private $_c mangle $_i src
		if [ "$HAS_CB" = 1 ]; then
			add_ports $_c mangle $_o tcp dports "$TCP_PORTS" -m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes "1:$PKT_OUT" $_jq
			add_ports $_c mangle $_o udp dports "$UDP_PORTS" -m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes "1:$PKT_OUT_UDP" $_jq
			add_ports $_c mangle $_i tcp sports "$TCP_PORTS" -m connbytes --connbytes-dir=reply --connbytes-mode=packets --connbytes "1:$PKT_IN" $_jq
			add_ports $_c mangle $_i udp sports "$UDP_PORTS" -m connbytes --connbytes-dir=reply --connbytes-mode=packets --connbytes "1:$PKT_IN" $_jq
		else
			# No connbytes in this kernel (typical for GKI). Keep the userspace load low anyway:
			# SYN and packets that carry data go to nfqws, pure ACKs of downloads do not.
			add_ports $_c mangle $_o tcp dports "$TCP_PORTS" --tcp-flags SYN,ACK,FIN,RST SYN $_jq
			if [ "$HAS_LEN" = 1 ]; then
				add_ports $_c mangle $_o tcp dports "$TCP_PORTS" -m length --length 81:65535 $_jq
			else
				add_ports $_c mangle $_o tcp dports "$TCP_PORTS" $_jq
			fi
			add_ports $_c mangle $_o udp dports "$UDP_PORTS" $_jq
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
		for _u in $EXCLUDE_UIDS; do
			$_c -t nat -A MANKA_NAT -m owner --uid-owner "$_u" -j RETURN
		done
		add_ports $_c nat MANKA_NAT tcp dports "$BYEDPI_PORTS" -j REDIRECT --to-ports "$_port"
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
		for _u in $EXCLUDE_UIDS; do
			$_c -t filter -A MANKA_FLT -m owner --uid-owner "$_u" -j RETURN
		done
		[ "$BLOCK_QUIC" = 1 ] && $_c -t filter -A MANKA_FLT -p udp --dport 443 -j REJECT
		if [ $_v6reset = 1 ]; then
			skip_private $_c filter MANKA_FLT dst
			$_c -t filter -A MANKA_FLT -m owner --uid-owner 0 -j RETURN
			add_ports $_c filter MANKA_FLT tcp dports "$BYEDPI_PORTS" -j REJECT --reject-with tcp-reset
		fi
	done
}

remove_main_rules() {
	for _c in ipt ip6t; do
		chain_del $_c mangle MANKA_OUT OUTPUT
		chain_del $_c mangle MANKA_IN PREROUTING
		chain_del $_c nat MANKA_NAT OUTPUT
		chain_del $_c filter MANKA_FLT OUTPUT
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
		[ -f "$FILES/lua/$_l" ] && echo "--lua-init=@$FILES/lua/$_l"
	done
}

# ---------------------------------------------------------------- engines

start_engine() {
	case "$ENGINE" in
		zapret)
			PKT_OUT=6; PKT_OUT_UDP=6; PKT_IN=3
			start_daemon zapret "$BIN/nfqws" "$ARGS/zapret.args" "--qnum=$QNUM" --uid=0:0
			setup_nfq "$QNUM" main
			;;
		zapret2)
			PKT_OUT=20; PKT_OUT_UDP=5; PKT_IN=10
			# shellcheck disable=SC2046
			start_daemon zapret2 "$BIN/nfqws2" "$ARGS/zapret2.args" "--qnum=$QNUM" --uid=0:0 $(lua_init)
			setup_nfq "$QNUM" main
			;;
		byedpi)
			start_daemon byedpi "$BIN/ciadpi" "$ARGS/byedpi.args" -E -i 127.0.0.1 -p "$BYEDPI_PORT"
			if [ "$IPV6" = 1 ] && [ "$HAS_NAT6" = 1 ]; then
				start_daemon byedpi6 "$BIN/ciadpi" "$ARGS/byedpi.args" -E -i ::1 -p "$((BYEDPI_PORT + 1))"
			fi
			setup_byedpi_rules
			;;
		*) return 0 ;;
	esac
	setup_filter
	log "engine $ENGINE started"
}

stop_engine() {
	for _n in zapret zapret2 byedpi byedpi6; do
		stop_daemon $_n
	done
	remove_main_rules
}

start_tgws() {
	start_daemon tgws "$BIN/tg-ws-proxy" "$ARGS/tgws.args" --host 127.0.0.1 --port "$TGWS_PORT"
	log "tg-ws-proxy started on 127.0.0.1:$TGWS_PORT"
}

# ---------------------------------------------------------------- commands

cmd_start() {
	probe_caps
	stop_engine
	[ "$ENABLED" = 1 ] && start_engine
	if [ "$TGWS" = 1 ]; then
		is_running tgws || start_tgws
	else
		stop_daemon tgws
	fi
	cmd_status
}

cmd_stop() {
	stop_engine
	remove_test_rules
	stop_daemon test
	stop_daemon tgws
	log "stopped"
	cmd_status
}

cmd_status() {
	load_caps
	echo "module_dir=$MODDIR"
	echo "module_version=$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null)"
	echo "enabled=$ENABLED"
	echo "engine=$ENGINE"
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
	echo ok
}

cmd_boot() {
	log "boot"
	probe_caps
	[ "$ENABLED" = 1 ] && start_engine
	[ "$TGWS" = 1 ] && start_tgws
}

case "$1" in
	start|restart|apply) cmd_start ;;
	stop) cmd_stop ;;
	engine-stop)
		# stop DPI bypass only, TG WS Proxy keeps running
		stop_daemon test
		remove_test_rules
		stop_engine
		echo ok
		;;
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
	*)
		echo "usage: $0 start|stop|restart|status|boot|tgws-restart|test-start ENGINE ARGSFILE UID|test-stop"
		exit 1
		;;
esac
