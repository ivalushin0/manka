# Manka module: daemons under a supervisor. Sourced by manka.sh (POSIX sh), uses its paths and settings.

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
			# the proxied apps would be left without network
			[ "$_name" = proxy ] && remove_proxy_rules
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
