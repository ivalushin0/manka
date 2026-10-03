# Manka module: WhatsApp address remap. Sourced by manka.sh (POSIX sh), uses its paths and settings.

# ---------------------------------------------------------------- Meta address remap (see META_FIX)

remove_meta() {
	chain_del ipt nat MANKA_META OUTPUT
	chain_del ipt nat MANKA_METAP PREROUTING
	chain_del ip6t filter MANKA_META6 OUTPUT
	rm -f "$RUN/meta_ip"
}

# the first edge address that accepts a connection on this network
pick_meta_edge() {
	for _ip in $META_EDGES; do
		timeout 4 nc -z -w 3 "$_ip" 443 >/dev/null 2>&1 && { echo "$_ip"; return 0; }
	done
	return 1
}

# Only for the WhatsApp apps (META_UIDS): the 57.144.x edges serve WhatsApp alone, any other Meta
# host name (Instagram, Facebook) gets a stub 404 there, and their own addresses are reachable.
setup_meta() {
	remove_meta
	[ "$META_FIX" = 1 ] && [ -n "$META_UIDS" ] || return 0
	# ByeDPI: the apps' connections are made by ciadpi (root), they cannot be told apart
	if [ "$ENGINE" = byedpi ]; then
		log "meta: not with ByeDPI"
		return 0
	fi
	_edge=$(pick_meta_edge) || { log "meta: no reachable edge address, remap off"; return 1; }
	chain_init ipt nat MANKA_META OUTPUT || return 1
	for _u in $META_UIDS; do
		for _n in $META_NETS; do
			ipt -t nat -A MANKA_META -p tcp -d "$_n" -m owner --uid-owner "$_u" -j DNAT --to-destination "$_edge"
		done
	done
	if chain_init ip6t filter MANKA_META6 OUTPUT; then
		for _u in $META_UIDS; do
			for _n in $META_NETS6; do
				ip6t -t filter -A MANKA_META6 -p tcp -d "$_n" -m owner --uid-owner "$_u" -j REJECT --reject-with tcp-reset
			done
		done
	fi
	echo "$_edge" > "$RUN/meta_ip"
	log "meta: WhatsApp ($META_UIDS) goes to $_edge"
}
