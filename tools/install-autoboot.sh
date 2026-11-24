#!/bin/sh
# Makes a tracker phone boot by itself when it gets power while off: adds
# tools/system/tracker-autoboot.rc to /system. Without it the phone stays in charger mode until
# someone holds the power button, and the phones hang where nobody can reach it.
#
# Why /system: the rule restarts the phone from charger mode (setprop sys.powerctl), and only init
# may do that. Commands from /vendor run as vendor_init, which SELinux doesn't allow it.
#
# /system is read-only (erofs, dm-verity), so this uses `adb remount`: an overlayfs over /system,
# kept in /data. The first remount turns verity off and reboots. A LineageOS update drops the
# overlay: run this again after every update.
#
# Needs: LineageOS 23.2 (userdebug), unlocked bootloader, Developer options > Rooted debugging,
# adb (in `nix develop`). Undo: adb root && adb shell rm /system/etc/init/tracker-autoboot.rc,
# or `adb enable-verity` (drops the overlay) and reboot.
#
#   ANDROID_SERIAL=<serial> tools/install-autoboot.sh
set -eu
ADB=${ADB:-adb}
RC=$(cd "$(dirname "$0")" && pwd)/system/tracker-autoboot.rc
DEST=/system/etc/init/tracker-autoboot.rc
: "${ANDROID_SERIAL:?set ANDROID_SERIAL}"
export ANDROID_SERIAL

log() { printf '\n== %s\n' "$*"; }
booted() { $ADB wait-for-device; until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done; }
root() { booted; $ADB root >/dev/null; sleep 3; booted; }

log "checks"
root
if $ADB shell ls "$DEST" >/dev/null 2>&1; then
    echo "already installed"
    exit 0
fi

log "1. remount /system (reboots once if verity is on)"
$ADB remount -R
root
$ADB remount

log "2. add the rule"
$ADB push "$RC" "$DEST"
$ADB shell "chown root:root $DEST && chmod 644 $DEST && restorecon $DEST"

log "3. reboot and check"
$ADB reboot
root
$ADB shell ls -lZ "$DEST"
$ADB shell ls -lZ "$DEST" | grep -q 'u:object_r:system_file:s0' || { echo "wrong SELinux label" >&2; exit 1; }
echo "done. Test: power off while plugged in; it should boot by itself within about 2 minutes."
