#!/system/bin/sh
# "Action" button in Magisk / KernelSU manager: show status and restart services.
MODDIR=${0%/*}

echo "Manka: restarting..."
sh "$MODDIR/manka.sh" restart
