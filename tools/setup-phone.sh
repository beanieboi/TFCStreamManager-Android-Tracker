#!/usr/bin/env bash
# Prepares a tracker phone for mounting out of reach. Needs a TrebleDroid userdebug GSI (adb root)
# and USB for the first run.
#
#   ANDROID_SERIAL=<serial> tools/setup-phone.sh
#
# - adb over Wi-Fi on port 5555 (the HUD shows the IP): adb connect <ip>:5555, then e.g. adb reboot
# - the tracker is the home app: it starts on boot and Android restarts it if it dies
# - screen always on while powered, no lock screen, no battery optimisation for the tracker
#
# Not done here: booting when power comes back (tools/install-autoboot.sh, next step) and adb key
# checks (the GSI ships ro.adb.secure=0, so anyone on the network gets a shell; still open, see
# docs/remote-management.md). Both change /system, which only works offline. Order: docs/flashing.md.
set -euo pipefail

: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the phone serial}"
export ANDROID_SERIAL
ADB=${ADB:-adb}
PKG=com.tfcleipzig.tracker
APK=$(dirname "$0")/../app/build/outputs/apk/debug/app-debug.apk

adb() { "$ADB" "$@"; }
dsh() { adb shell "$@"; }
log() { printf '\n== %s\n' "$*"; }

wait_boot() {
    until [[ $(adb get-state 2>/dev/null) == device ]]; do sleep 2; done
    until [[ $(dsh getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do sleep 2; done
}

root() {
    wait_boot
    adb root >/dev/null
    sleep 3
    wait_boot
}

log "root"
root

log "adb over Wi-Fi"
dsh setprop persist.adb.tcp.port 5555

log "tracker app"
if [[ -f $APK ]]; then
    adb install -r -g "$APK"
else
    echo "no $APK, build it first (./gradlew :app:assembleDebug); skipping install"
fi
dsh pm grant "$PKG" android.permission.CAMERA
dsh cmd package set-home-activity "$PKG/.MainActivity"
# The app shortens the screen timeout on battery so the camera stops and the phone lasts (PowerIdle).
dsh appops set "$PKG" WRITE_SETTINGS allow
dsh dumpsys deviceidle whitelist "+$PKG" >/dev/null

log "screen and Wi-Fi"
dsh settings put global stay_on_while_plugged_in 7
dsh settings put system screen_off_timeout 2147483647
dsh settings put global wifi_sleep_policy 2
dsh locksettings set-disabled true

log "reboot to check it all comes back"
sleep 15 # the home app choice is written to disk with a delay
adb reboot
sleep 10
wait_boot
echo "adb port $(dsh getprop persist.adb.tcp.port | tr -d '\r')"
echo "wifi $(dsh 'ip -4 addr show wlan0' | awk '/inet /{print $2}')"
echo "home $(dsh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
