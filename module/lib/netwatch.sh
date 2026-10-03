# Manka module: network watcher. Sourced by manka.sh (POSIX sh), uses its paths and settings.


# Cheap fingerprint of the current network: default route plus the MAC of its gateway (two Wi-Fi
# networks can share the same addresses, their routers do not share a MAC). No binder calls.
net_sig() {
	_r=$(ip route get 1.1.1.1 2>/dev/null | head -n1 | sed 's/ uid [0-9]*//')
	_gw=$(echo "$_r" | sed -n 's/.* via \([^ ]*\).*/\1/p')
	_dv=$(echo "$_r" | sed -n 's/.* dev \([^ ]*\).*/\1/p')
	echo "$_r $(ip neigh show dev "$_dv" 2>/dev/null | grep "^$_gw " | sed -n 's/.* lladdr \([^ ]*\).*/\1/p')"
}

# Switches the profile when the network changes. "ip monitor" was tried first, but on Android its
# output is block-buffered: events arrived minutes late. A 10 s check is cheap and the sleep does
# not wake a suspended phone.
netwatch() {
	_last=$(net_sig)
	_n=0
	while :; do
		sleep 10
		[ -f "$RUN/testing" ] && continue
		# blocks change on the same network too: Telegram's direct addresses every 30 min
		_n=$((_n + 1))
		if [ $_n -ge 180 ]; then
			_n=0
			sh "$SELF" tgws-check </dev/null >/dev/null 2>&1
		fi
		_sig=$(net_sig)
		[ "$_sig" = "$_last" ] && continue
		_last=$_sig
		[ "$(current_key)" = "$(cat "$RUN/key" 2>/dev/null)" ] && continue
		# let the routing settle, then re-check
		sleep 2
		_k=$(current_key)
		[ "$_k" = "$(cat "$RUN/key" 2>/dev/null)" ] && continue
		log "network changed: $_k"
		sh "$SELF" net-apply </dev/null >/dev/null 2>&1
		_last=$(net_sig)
	done
}
