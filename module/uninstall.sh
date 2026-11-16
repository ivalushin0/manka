#!/system/bin/sh
MODDIR=${0%/*}

sh "$MODDIR/manka.sh" stop >/dev/null 2>&1
rm -rf /data/adb/manka
# the copy of tg-ws-proxy that the shell user runs (lib/tgws.sh)
rm -rf /data/local/tmp/manka
