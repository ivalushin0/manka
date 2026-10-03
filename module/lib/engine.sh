# Manka module: network profiles and the bypass engines. Sourced by manka.sh (POSIX sh), uses its paths and settings.

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
			_lis=127.0.0.1
			[ "$HOTSPOT" = 1 ] && _lis=0.0.0.0
			start_daemon byedpi "$BIN/ciadpi" "$PROFILE_ARGS" -E -i "$_lis" -p "$BYEDPI_PORT"
			if [ "$IPV6" = 1 ] && [ "$HAS_NAT6" = 1 ]; then
				start_daemon byedpi6 "$BIN/ciadpi" "$PROFILE_ARGS" -E -i ::1 -p "$((BYEDPI_PORT + 1))"
			fi
			setup_byedpi_rules
			;;
		*) return 0 ;;
	esac
	# report the state after the engine is really up (the app reads the status right away)
	await_daemon "$ENGINE" >/dev/null
	setup_filter
	echo "$KEY" > "$RUN/key"
	echo "$PROFILE" > "$RUN/profile"
	echo "$ENGINE" > "$RUN/engine"
	engine_sig > "$RUN/engine.sig"
	log "engine $ENGINE started, profile $PROFILE ($KEY)"
}

stop_engine() {
	for _n in zapret zapret2 byedpi byedpi6; do
		stop_daemon $_n
	done
	remove_main_rules
	rm -f "$RUN/engine" "$RUN/engine.sig"
}

# Everything the running engine depends on: its settings (not those of DNS, TG WS Proxy, the Meta
# remap or the proxy port), the profile, the host lists and kit files. Unchanged: an apply keeps
# the engine running, the bypass has no gap and connections survive.
engine_sig() {
	{
		echo "$KEY $PROFILE $HAS_MP $HAS_CB $HAS_NFQ $HAS_NAT6 $HAS_LEN"
		grep -v -E '^(TGWS|DNS_|META_|PROXY_PORT)' "$DATA/settings.conf" 2>/dev/null
		cat "$PROFILES/$PROFILE.conf" "$PROFILE_ARGS" 2>/dev/null
		# the app rewrites the lists on every apply: their content counts, not their time
		find "$DATA/lists" -type f -exec md5sum {} + 2>/dev/null | sort
		ls -lR "$DATA/kits" "$FILES" 2>/dev/null
	} | md5sum
}

# the engine runs with exactly the current configuration
engine_current() {
	[ -f "$RUN/engine" ] && is_running "$(cat "$RUN/engine")" && rules_ok &&
		[ "$(engine_sig)" = "$(cat "$RUN/engine.sig" 2>/dev/null)" ]
}
