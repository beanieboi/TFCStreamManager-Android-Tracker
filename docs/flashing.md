# Flashing a tracker phone

How to back up, flash and restore the partitions of an Oukitel WP5 tracker phone, and the order to
set up a new one. Everything here was done on the first tracker phone on 2026-11-22, from a Mac with
`nix develop` (adb, fastboot, e2fsprogs).

## Before you start

- **Shell:** `nix develop` in this repo. Set `ANDROID_SERIAL=<serial>` (from `adb devices`) when
  more than one phone is connected.
- **USB:** a data cable, directly on the computer (no hub). adb itself only listens on Wi-Fi after
  `setup-phone.sh` (`persist.adb.tcp.port`), but fastboot and the first setup need USB.
- **Someone at the phone.** Every step below can end with the phone needing a button press.
- **Debug key:** to install the tracker app over an existing one, build with the shared debug key
  (`~/.android/debug.keystore`, SHA-256 `C7:D6:FF:1F:…:2A:99`), or Android refuses the update.

## The phone's modes

| mode | how to get there | what it can do |
|---|---|---|
| Android | normal boot | adb (USB at first, Wi-Fi after setup), `su` for the shell |
| **fastbootd** (userspace fastboot, in recovery) | `adb reboot fastboot`; or recovery → "Enter fastboot" | flash `system` and `vendor` (logical partitions in `super`) |
| bootloader fastboot (LK) | `adb reboot bootloader`; or recovery → "Reboot to bootloader" | flash `boot`, `vbmeta`, whole `super`; **not** `system`/`vendor` on their own |
| recovery | hold **Power + Volume Up** about 12 s | menu to reboot, enter fastboot, factory reset |
| charger mode | power arrives at a phone that is off | with the tracker boot image: restarts into Android by itself once the battery is at 3.7 V |

- **Getting out of anything:** holding **Power 10–15 s** is a hardware cold reset (boot reason
  `reboot_longkey`). A 4 s press in charger mode starts the boot normally.
- **"Orange State"** on the blue screen is the bootloader's warning for an unlocked phone. It
  shows on every boot and is expected.

## Backing up a partition

Before flashing anything, dump the partition over adb and compare checksums on both sides.
`system` is `/dev/block/dm-0` and `vendor` is `/dev/block/dm-1` (check with
`adb shell su -c 'dmctl list devices'`).

```
adb shell "su -c 'dd if=/dev/block/dm-0 of=/sdcard/system.img bs=4m'"
adb shell "su -c 'sha256sum /dev/block/dm-0 /sdcard/system.img'"
adb pull /sdcard/system.img phone-backups/<serial>-system-<date>.img
shasum -a 256 phone-backups/<serial>-system-<date>.img
adb shell rm /sdcard/system.img
```

All three checksums must match. Keep the backups outside git (about 2.3 GB per phone).

## Flashing system or vendor

1. Get the phone into fastbootd and wait for it:
   ```
   adb reboot fastboot
   fastboot devices                   # repeat until the phone shows up (up to a minute)
   fastboot getvar is-userspace       # must say "yes": that's fastbootd, not the bootloader
   ```
2. Flash in **small chunks**. With the default 256 MB chunks the transfer times out on macOS
   (`usb_read/usb_write failed with status e00002ed`); 8 MB works.
   ```
   fastboot -S 8M flash system <image>
   ```
   - It ends with `Finished` and exit code 0, and every `Sending` has a matching `Writing … OKAY`.
   - If it fails partway, run the same command again right away, in the same fastbootd session.
     A flash always writes the whole image, so repeating is safe. The second attempt has always
     worked so far.
   - **Don't reboot into Android after a failed flash:** the partition is half written.
   - **Never pipe fastboot's output into `head` or anything else that stops reading early.**
     fastboot dies on the broken pipe in the middle of the flash.
   - fastbootd resizes the logical partition to the image size (`Resizing 'system' OKAY`). `super`
     is 4 GiB; the TrebleDroid system (2.1 GB) and vendor fit with room to spare.
3. Boot and compare:
   ```
   fastboot reboot
   adb wait-for-device                # then wait for: adb shell getprop sys.boot_completed → 1
   adb shell "su -c 'dd if=/dev/block/dm-0 bs=4096 skip=1 | sha256sum'"
   tail -c +4097 <image> | shasum -a 256
   ```
   Compare everything except the first 4 KB: mounting at boot updates the ext4 superblock
   (timestamps, mount count), so block 0 always differs.

## Restoring a backup

Flash the backup exactly the same way (`fastboot -S 8M flash system <backup>`). fastbootd shrinks or
grows the partition to the backup's size. If Android doesn't come up after a bad flash: recovery
(Power + Volume Up), then "Enter fastboot", then flash the backup.

## Patching an image: offline, never on the phone

`/system` has ext4 `shared_blocks` and can't be mounted writable. `/vendor`'s image has wrong
free-inode counts, and writing to it on the phone damages it (creating a file fails "doubly
allocated" and sets the error flag). Writes through device-mapper or to `super` from Android are
silently dropped. So change a copy on the computer and flash it:

```
cp <backup> work.img
truncate -s 3G work.img && resize2fs -f work.img          # room for unsharing
e2fsck -fy -E unshare_blocks work.img                     # only /system has shared_blocks
resize2fs -M work.img                                     # then grow by a reserve, and truncate to match
debugfs -w work.img                                       # write / rm / set_inode_field / ea_set
e2fsck -fn work.img                                       # must be clean
```

New files need the SELinux label of their neighbours (`debugfs -R 'ea_list <file>'`), e.g.
`u:object_r:system_file:s0` with a trailing NUL. A change that keeps every byte count the same
(like the TEE fix below) needs none of this: patch the bytes in place.

## Setting up a new tracker phone

What goes on a tracker phone (set up this way on 2026-11-25):

| partition | what | from |
|---|---|---|
| `boot` | Linux 6.12 with the WP5 drivers: autostart from charger mode, battery kept at 20–80 % | WP5 kernel port, built with `KPOC_AUTOBOOT_MV=3700 CHARGE_STOP_SOC=80 SHUTDOWN_SOC=20` (kernel command line `mtk_charger.kpoc_autoboot_mv=3700 mtk_charger.charge_stop_soc=80 mt6357_battery.shutdown_soc=20`) |
| `system` | TrebleDroid `ci-20250617`, Android 16, userdebug: `adb root` works | `system-td-arm64-vanilla.img.xz` from [TrebleDroid's releases](https://github.com/TrebleDroid/treble_experimentations/releases/tag/ci-20250617), plus the TEE fix below |
| `vendor` | stock Android 11 vendor from the WP5 port, `/data` not encrypted | already on phones from the WP5 port; TrebleDroid doesn't boot yet with encrypted `/data` on this phone |

1. **Unlock the bootloader** (done for all WP5s from the port; `ro.boot.verifiedbootstate=orange`,
   vbmeta verification off).
2. **TEE fix for the GSI.** The phone's TEE (keystore, lock screen) only starts if
   `ro.product.device` is `WP5`, and the GSI overrides it with `tdgsi_arm64_ab` in two places.
   Comment both out by turning their first byte into `#` (same size, so no filesystem changes):
   ```
   xz -dk system-td-arm64-vanilla.img.xz
   img=system-td-arm64-vanilla.img
   for key in ro.product.product.device= ro.product.system_ext.device=; do
       off=$(LC_ALL=C grep -abo "^$key" "$img" | cut -d: -f1)   # exactly one offset each
       printf '#' | dd of="$img" bs=1 seek="$off" conv=notrunc
   done
   LC_ALL=C grep -ao '^#o\.product\.[a-z_]*\.device=.*' "$img"  # both lines now start with #
   ```
   For `ci-20250617` the result has SHA-256 `c22e2eec…f80a8b480`.
3. **Flash the boot image** from bootloader fastboot: `adb reboot bootloader`, then
   `fastboot flash boot <boot image>`.
4. **Flash system and wipe** from fastbootd (see "Flashing system or vendor" above):
   ```
   fastboot reboot fastboot
   fastboot -S 8M flash system system-td-arm64-vanilla.img
   fastboot erase userdata            # `fastboot -w` fails in fastbootd ("cache doesn't exist")
   fastboot erase metadata
   fastboot reboot
   ```
   The first boot takes a few minutes. Connect Wi-Fi on the phone (or
   `adb shell cmd wifi connect-network <ssid> wpa2 <password>`).
5. **Tracker app and phone settings:** build `nix develop -c ./gradlew :app:assembleDebug`, then run
   `ANDROID_SERIAL=<serial> tools/setup-phone.sh`. It sets up adb over Wi-Fi, makes the
   tracker the home app, and turns on always-on screen, no lock screen, no battery optimisation,
   `WRITE_SETTINGS`, and all sounds off.
6. **Check the autostart:** plugged in, `adb shell reboot -p`. It should go charging picture →
   Orange State → Oukitel logo → Android with the tracker by itself, in about 2 minutes (see
   docs/remote-management.md, "Booting when power comes back").
7. **Hold while away from the table:** start the app with `--ez hold true` until it's mounted,
   so it doesn't calibrate on the bench (see docs/remote-management.md, "Hold").
8. **At the table:** `--ez hold false`, calibrate from the debug page, check the reading. See
   docs/on-site-debugging.md.

## Updating just the app

```
nix develop -c ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.tfcleipzig.tracker/.MainActivity     # add --ez hold true away from the table
```

After `install -r` the app stays stopped until something starts it, so always start it.
