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
adb connect 10.0.0.69:5555          # IP is on the HUD ("phone" line); reserve it in DHCP
adb -s 10.0.0.69:5555 reboot
adb -s 10.0.0.69:5555 shell am force-stop com.tfcleipzig.tracker   # home app, comes back
adb -s 10.0.0.69:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 10.0.0.69:5555 shell am start -n com.tfcleipzig.tracker/.MainActivity --ef zoom 2.3
```

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

## Open: needs a modified system image

Tested on the Oukitel WP5 with TrebleDroid (Android 13, `treble_arm64_bvS`), 2026-11-16:

- **No adb key check.** The GSI sets `ro.adb.secure=0` in `/system/build.prop`: anyone on the
  network can open a shell (and `adb root`). Keep the phones on a network only we use, until the
  image is fixed.
- **Plugged in while off, the phone stays in the charger screen.** Only matters after a power cut
  longer than the battery lasts, or a shutdown. The bootloader has `off-mode-charge: 1`, but
  `fastboot oem off-mode-charge 0` is refused (`not support on security`).

Both need `/system` changed, which can't be done on the phone: the ext4 image has `shared_blocks`
(can't be mounted read-write), and `adb remount`'s overlay fails on this GSI (it can't move phh's
`su` bind mounts, and creates no scratch partition), so edits are gone after a reboot. The fix is
to patch the GSI image on a PC and flash it with `fastboot flash system` from fastbootd (keeps
`/data`):

- `ro.adb.secure=1` in `build.prop` and `system_ext/etc/build.prop`; the authorised keys are already in
  `/data/misc/adb/adb_keys` (the setup run left them there),
- `/system/etc/init/tfc-tracker.rc`:
  ```
  on charger
      setprop sys.powerctl reboot,tfc-tracker-power-on
  ```

Side effect of the attempt: `adb remount` disabled verity (vbmeta flag); harmless on an unlocked
phone, and needed for a patched image anyway.
