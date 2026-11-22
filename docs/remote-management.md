# Remote management

The tracker phones hang near the ceiling, always on power. Everything after mounting goes over
Wi-Fi.

## Setup (once per phone, over USB)

```
./gradlew :app:assembleDebug
ANDROID_SERIAL=<serial> ADB=~/Android/Sdk/platform-tools/adb tools/setup-phone.sh
```

Needs the TrebleDroid `userdebug` GSI (`adb root` works without a toggle). The script:

- turns on adb over Wi-Fi, port 5555 (`persist.adb.tcp.port`, survives reboots),
- installs the tracker and makes it the home app: it starts on boot, and Android brings it back if
  it dies,
- keeps the screen on while powered, turns off the lock screen and battery optimisation for the
  tracker.

## Day to day

```
adb connect <ip>:5555               # IP is on the HUD ("phone" line); reserve it in DHCP
adb -s <ip>:5555 reboot
adb -s <ip>:5555 shell am force-stop com.tfcleipzig.tracker   # home app, comes back
adb -s <ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <ip>:5555 shell am start -n com.tfcleipzig.tracker/.MainActivity --ef zoom 2.3
```

## Hold (away from the table)

At home or on the bench the phone doesn't see its counters. To keep it from recalibrating or
tracking exposure on whatever it sees, turn on **hold**:

- debug page: tap **hold** (red, "held: tap to release")
- over adb: `adb shell am start -n com.tfcleipzig.tracker/.MainActivity --ez hold true`

Hold is saved and survives restarts. It blocks calibration, the lost-counters recalibration and
exposure tracking, and it uses the saved settings. Turn it off again at the table
(`--ez hold false`). After `adb install -r`, start the app with the extra right away, or the old
setting applies.

## Power cuts

On battery the tracker idles instead of running the camera flat (`PowerIdle`): after 60 s
without power the screen may turn off (timeout 15 s), which closes the camera; Wi-Fi stays up.
When power comes back the screen turns on and the tracker carries on with its saved
calibration. So the phone only switches off if power is gone for longer than the idle battery
lasts. Needs `WRITE_SETTINGS` (`setup-phone.sh` grants it; the HUD's `power` line says if it's
missing). Try it without pulling the plug:

```
adb shell dumpsys battery unplug     # power gone
adb shell dumpsys battery reset      # back
```

## Booting when power comes back

Plugged in while off, the bootloader starts charger mode, and without help the phone stays there
until someone holds the power button. `tools/install-autoboot.sh` adds
`/system/etc/init/tracker-autoboot.rc`:

```
on charger
    setprop sys.powerctl reboot,tracker-autoboot
```

A restart from charger mode boots Android normally: the bootloader only chooses charger mode
when power arrives at a phone that is off. On the WP5 (2026-11-22), off on battery and then
plugged in, it boots by itself in about 1.5 minutes: boot reason `reboot,tracker-autoboot`, boot
mode `normal`, no loop.

The script backs up `/system`, patches a copy offline (unshare the ext4 `shared_blocks`, add the
file, `e2fsck`), flashes it from fastbootd and compares the result. `/system` grows from 1.93 to
about 2.5 GB, and `super` has room for it. Run on one phone so far (step by step by hand; the script afterwards only for its checks).

After a boot on a nearly empty battery the app holds tracking back (`BatteryGate`): the camera
stays off below 15 % and the phone only charges. Try it with
`adb shell dumpsys battery set level 5` (and `reset`).

What didn't work, so nobody tries it again:

- **A rule in `/vendor`** (TrebleDroid's `charger.rc` imports
  `/vendor/etc/init/${ro.bootmode}/init.${ro.bootmode}.rc`, and `/vendor/etc/init/*.rc` is read in
  every mode). Commands from `/vendor` run as `vendor_init`, which SELinux allows neither
  `sys.powerctl` nor `sys.boot_from_charger_mode`, nor writing `/proc/sysrq-trigger`.
- **Continuing the boot inside charger mode** (`class_stop charger` + `trigger late-init`, what
  `sys.boot_from_charger_mode` does): Android hangs at the bootloader's charging picture; it took a
  10–15 s power press (cold reset) to get out.
- **Writing `/vendor` on the phone:** its ext4 image has wrong free-inode counts, so creating a file
  fails ("doubly allocated") and sets the filesystem's error flag; writes through the
  device-mapper device or to `super` from Android are silently dropped. Patch images offline and
  flash them instead.
- **fastboot over USB on macOS** times out on the default 256 MB chunks
  (`usb_read/usb_write failed e00002ed`); 8 or 16 MB chunks (`fastboot -S 8M`) work, sometimes on
  the second attempt in the same fastbootd session. Never cut fastboot's output with `head`: it
  dies on the broken pipe mid-flash.

## Open: adb key check

The GSI sets `ro.adb.secure=0` in `/system/build.prop`: anyone on the network can open a shell
(and `su`). Keep the phones on a network only we use. The fix is `ro.adb.secure=1` in
`build.prop` and `system_ext/etc/build.prop`, patched the same way as the autoboot rule. The
setup run left the Linux machine's key in `/data/misc/adb/adb_keys`; add every machine's key
before switching it on, or adb is locked out (the phones hang out of reach).

Side effect of the 2026-11-16 attempt: `adb remount` disabled verity (vbmeta flag); harmless on an
unlocked phone, and needed for the patched images anyway.
