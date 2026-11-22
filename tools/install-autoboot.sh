#!/bin/sh
# Makes a tracker phone boot by itself when it gets power while off: adds
# tools/system/tracker-autoboot.rc to /system. Without it the phone stays in charger mode until
# someone holds the power button, and the phones hang where nobody can reach it.
#
# Why /system: the rule restarts the phone from charger mode (setprop sys.powerctl), and only init
# may do that. Commands from /vendor run as vendor_init, which SELinux doesn't allow it.
#
# /system can't be changed on the phone (ext4 shared_blocks), so this works on a copy:
#   1. dump /system over adb and keep it as backup (checksums compared)
#   2. offline: grow, unshare the blocks, shrink to fit, add the rule, e2fsck
#   3. flash it from fastbootd in 8 MB chunks (bigger ones time out over USB on macOS), retried
#   4. reboot and compare the partition with the image (all but the superblock, which mounting
#      updates)
#
# Needs: TrebleDroid GSI with su for the shell (adb root not needed), adb, fastboot, e2fsprogs
# (all in `nix develop`). Takes about 10 minutes. Undo: flash the backup the same way.
#
#   ANDROID_SERIAL=<serial> tools/install-autoboot.sh <work dir>
set -eu
ADB=${ADB:-adb}
FASTBOOT=${FASTBOOT:-fastboot}
RC=$(cd "$(dirname "$0")" && pwd)/system/tracker-autoboot.rc
WORK=${1:?usage: $0 <work dir, about 6 GB free>}
SERIAL=${ANDROID_SERIAL:?set ANDROID_SERIAL}
STAMP=$(date +%Y-%m-%d)
BACKUP=$WORK/system-$SERIAL-$STAMP.img
PATCHED=$WORK/system-$SERIAL-$STAMP-autoboot.img
mkdir -p "$WORK"

log() { printf '\n== %s\n' "$*"; }
dsh() { $ADB shell "su -c \"$1\""; }
booted() { $ADB wait-for-device; until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done; }

log "checks"
if dsh "ls /system/etc/init/tracker-autoboot.rc" >/dev/null 2>&1; then echo "already installed"; exit 0; fi
dsh "dmctl table system" | grep -q verity && { echo "/system is verity-checked, not touching it" >&2; exit 1; }

log "1. backup /system"
dsh "dd if=/dev/block/dm-0 of=/sdcard/system-backup.img bs=4m 2>/dev/null"
want=$(dsh "sha256sum /dev/block/dm-0" | cut -d' ' -f1)
$ADB pull /sdcard/system-backup.img "$BACKUP" >/dev/null
$ADB shell rm /sdcard/system-backup.img
got=$(shasum -a 256 "$BACKUP" 2>/dev/null || sha256sum "$BACKUP"); got=${got%% *}
[ "$got" = "$want" ] || { echo "backup checksum differs: $got vs $want" >&2; exit 1; }
echo "backup $BACKUP ($want)"

log "2. patch a copy"
cp "$BACKUP" "$PATCHED"
truncate -s 3G "$PATCHED"
resize2fs -f "$PATCHED" >/dev/null
e2fsck -fy -E unshare_blocks "$PATCHED" >/dev/null || [ $? -le 1 ]
resize2fs -M "$PATCHED" >/dev/null
blocks=$(dumpe2fs -h "$PATCHED" 2>/dev/null | awk -F: '/Block count/ {gsub(/ /, "", $2); print $2}')
blocks=$(( (blocks + 16384 + 255) / 256 * 256 ))   # 64 MB room, aligned to 1 MB
resize2fs "$PATCHED" "$blocks" >/dev/null
truncate -s $(( blocks * 4096 )) "$PATCHED"
printf 'u:object_r:system_file:s0\0' > "$WORK/label.bin"
debugfs -w -f - "$PATCHED" >/dev/null <<DEBUGFS
cd /system/etc/init
write $RC tracker-autoboot.rc
set_inode_field tracker-autoboot.rc mode 0100644
set_inode_field tracker-autoboot.rc uid 0
set_inode_field tracker-autoboot.rc gid 0
ea_set -f $WORK/label.bin tracker-autoboot.rc security.selinux
DEBUGFS
e2fsck -fn "$PATCHED" >/dev/null
dumpe2fs -h "$PATCHED" 2>/dev/null | grep -q shared_blocks && { echo "still has shared_blocks" >&2; exit 1; }
echo "patched $PATCHED ($blocks blocks)"

log "3. flash"
$ADB reboot fastboot
for i in $(seq 1 60); do [ -n "$($FASTBOOT devices 2>/dev/null)" ] && break; sleep 2; done
[ -n "$($FASTBOOT devices 2>/dev/null)" ] || { echo "no fastbootd after 2 minutes" >&2; exit 1; }
ok=
for attempt in 1 2 3; do
    if $FASTBOOT -S 8M flash system "$PATCHED"; then ok=1; break; fi
    echo "flash attempt $attempt failed, trying again (a flash always writes the whole image)"
done
[ -n "$ok" ] || { echo "flash failed 3 times; the phone is in fastbootd, don't reboot it into Android" >&2; exit 1; }
$FASTBOOT reboot

log "4. check"
booted
want=$(tail -c +4097 "$PATCHED" | { shasum -a 256 2>/dev/null || sha256sum; } | cut -d' ' -f1)
got=$(dsh "dd if=/dev/block/dm-0 bs=4096 skip=1 2>/dev/null | sha256sum" | cut -d' ' -f1)
[ "$got" = "$want" ] || { echo "partition differs from the image: $got vs $want" >&2; exit 1; }
dsh "ls -lZ /system/etc/init/tracker-autoboot.rc"
echo "done. Test: power off while plugged in; it should boot by itself within about 2 minutes."
