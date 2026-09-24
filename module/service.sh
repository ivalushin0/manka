#!/system/bin/sh
MODDIR=${0%/*}

until [ "$(getprop sys.boot_completed)" = 1 ]; do
	sleep 3
done

sh "$MODDIR/manka.sh" boot </dev/null >/dev/null 2>&1
