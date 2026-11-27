#!/usr/bin/env bash
# Prepares a tracker phone for mounting out of reach. Needs the TrebleDroid userdebug GSI
# (adb root works without a toggle) and USB for the first run.
#
#   ANDROID_SERIAL=<serial> NAME=tracker1 tools/setup-phone.sh
#   WIFI="SSID=password-file ..." ANDROID_SERIAL=<serial> NAME=tracker1 tools/setup-phone.sh
#
# - NAME is the phone's hostname (DHCP) and its name in the HUD and in reports to the server
# - adb over Wi-Fi on port 5555 (the HUD shows the IP): adb connect <ip>:5555, then e.g. adb reboot
# - the tracker is the home app: it starts on boot and Android restarts it if it dies
# - screen always on while powered, no lock screen, no battery optimisation for the tracker
#
# Booting when power comes back is the kernel's job (see docs/remote-management.md).
# The GSI has ro.adb.secure=0: adb over Wi-Fi takes anyone on the network.
set -euo pipefail

: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the phone serial}"
: "${NAME:?set NAME to the phone name, e.g. tracker1}"
export ANDROID_SERIAL
ADB=${ADB:-adb}
PKG=com.tfcleipzig.tracker
APK=$(dirname "$0")/../app/build/outputs/apk/release/app-release.apk

# `command`: with ADB=adb the function would call itself.
adb() { command "$ADB" "$@"; }
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

log "name $NAME"
# Android sends device_name as the DHCP hostname (on the next Wi-Fi connect).
dsh settings put global device_name "$NAME"

log "Wi-Fi networks"
# WIFI="SSID=password-file ..." adds or updates each network (WPA2/WPA3); passwords stay out of the repo.
# -r none: the phone's own MAC instead of a random one per network, so the router always sees the same device.
for net in ${WIFI:-}; do
    dsh cmd wifi add-network "${net%%=*}" wpa2 "'$(<"${net#*=}")'" -r none
done
dsh cmd wifi list-networks

log "adb over Wi-Fi"
dsh setprop persist.adb.tcp.port 5555

log "tracker app"
if [[ -f $APK ]]; then
    adb install -r -g "$APK"
    # Compiled ahead of time, the release build reads a frame in about half the time of the debug build.
    dsh cmd package compile -m speed -f "$PKG"
else
    echo "no $APK, build it first (./gradlew :app:assembleRelease); skipping install"
fi
dsh pm grant "$PKG" android.permission.CAMERA
dsh cmd package set-home-activity "$PKG/.MainActivity"
# The app shortens the screen timeout on battery so the camera stops and the phone lasts (PowerIdle).
dsh appops set "$PKG" WRITE_SETTINGS allow
# Lets the app light the screen when power comes back (PowerIdle).
dsh appops set "$PKG" TURN_SCREEN_ON allow
dsh dumpsys deviceidle whitelist "+$PKG" >/dev/null

log "screen and Wi-Fi"
dsh settings put global stay_on_while_plugged_in 7
dsh settings put system screen_off_timeout 2147483647
dsh settings put global wifi_sleep_policy 2
dsh locksettings set-disabled true

log "sounds and vibration off"
for k in sound_effects_enabled haptic_feedback_enabled dtmf_tone lockscreen_sounds_enabled vibrate_when_ringing; do
  dsh settings put system $k 0
done
for k in charging_sounds_enabled power_sounds_enabled dock_sounds_enabled; do
  dsh settings put global $k 0
done
dsh settings put secure charging_sounds_enabled 0
dsh settings put secure charging_vibration_enabled 0
# volume: system, ring, music, notification to 0; voice call, alarm, accessibility can only go down to 1
for s in 1 2 3 5; do dsh cmd media_session volume --stream $s --set 0 >/dev/null; done
for s in 0 4 10; do dsh cmd media_session volume --stream $s --set 1 >/dev/null; done
dsh cmd audio set-ringer-mode SILENT 2>/dev/null || true
# media volume set sometimes does not stick (seen on tracker 2); volume-down keys lower music too
for i in $(seq 16); do dsh input keyevent 25; done

log "WebView"
# TrebleDroid ships com.android.webview, but Android looks for Google's WebView first and then
# picks none: every app that shows web content crashes. Choosing it is stored and survives reboots.
dsh cmd webviewupdate set-webview-implementation com.android.webview

log "reboot to check it all comes back"
sleep 15 # the home app choice is written to disk with a delay
adb reboot
sleep 10
wait_boot
echo "name $(dsh settings get global device_name | tr -d '\r')"
echo "adb port $(dsh getprop persist.adb.tcp.port | tr -d '\r')"
echo "wifi $(dsh 'ip -4 addr show wlan0' | awk '/inet /{print $2}')"
echo "home $(dsh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
