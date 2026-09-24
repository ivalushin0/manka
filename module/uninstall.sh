#!/system/bin/sh
MODDIR=${0%/*}

sh "$MODDIR/manka.sh" stop >/dev/null 2>&1
rm -rf /data/adb/manka
