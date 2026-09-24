#!/system/bin/sh
# Magisk / KernelSU / APatch installer hook. $MODPATH, $ARCH and helpers are provided by the manager.

case "$ARCH" in
	arm64) ABI=arm64-v8a ;;
	arm) ABI=armeabi-v7a ;;
	x64) ABI=x86_64 ;;
	x86) ABI=x86 ;;
	*) ABI=$ARCH ;;
esac

if [ ! -d "$MODPATH/libs/$ABI" ]; then
	rm -rf "$MODPATH"
	abort "! Manka: unsupported CPU architecture ($ABI)"
fi

ui_print "- Manka: installing binaries for $ABI"
mkdir -p "$MODPATH/bin"
cp -f "$MODPATH/libs/$ABI/"* "$MODPATH/bin/"
rm -rf "$MODPATH/libs"

mkdir -p /data/adb/manka/args /data/adb/manka/run /data/adb/manka/logs /data/adb/manka/lists /data/adb/manka/kits
# fake payloads and lua scripts live at a path that does not change between module updates
rm -rf /data/adb/manka/files
cp -r "$MODPATH/files" /data/adb/manka/files
# stale runtime state from a previous version
rm -f /data/adb/manka/run/caps

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/bin" 0 0 0755 0755
set_perm "$MODPATH/manka.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

ui_print "- Manka: open the Manka app to configure bypass"
