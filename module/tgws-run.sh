#!/system/bin/sh
# The proxy is the Go core of the TgWsProxy app (vendor/tgws). It runs as the shell user, not as
# root: root traffic is skipped by the bypass (SYS_UIDS) and the ISP cuts the proxy's TLS, while the
# shell user is never restricted and the bypass covers it; /data/adb is root-only, hence the copy of
# the binary in /data/local/tmp/manka (lib/tgws.sh keeps it fresh).
B=/data/local/tmp/manka/tgwsproxy
CMD=
for a in "$@"; do
	# single quotes for the su -c command line; every argument of the proxy is a plain word
	a=$(printf '%s' "$a" | sed "s/'/'\\\\''/g")
	CMD="$CMD '$a'"
done
exec su 2000 -G 3003 -c "exec $B$CMD"

