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
- turns off all sounds and vibration (system sounds, charging sound, volumes).

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

## Reachable over Wi-Fi

The WP5's Wi-Fi in power save drops broadcast ARP requests: other hosts get "No route to host"
until the phone sends something itself. The app holds a low-latency Wi-Fi lock (`WifiAwake`), so
Android turns power save off while the tracker is in front and the screen is on, which is always
the case on power. On battery `PowerIdle` turns the screen off after a while, power save comes back
and saves battery; the phone may then only be reachable after it has sent something.

```
adb shell iw dev wlan0 get power_save    # "off" on power
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

## Booting when power comes back

Plugged in while off, the bootloader starts charger mode, and without help the phone stays there
until someone holds the power button. The tracker boot image does it in the kernel: MediaTek's
charger driver restarts the phone from charger mode once USB power is there and the battery is at
3.7 V or more (two checks in a row, at least 20 s after start). A restart from charger mode boots
Android normally: the bootloader only chooses charger mode when power arrives at a phone that is
off.

- **Switch:** kernel command line `mtk_charger.kpoc_autoboot_mv=3700`, set when the boot image is
  built (`KPOC_AUTOBOOT_MV=3700` in the WP5 port's build script). 0 or missing: off, the phone
  waits in charger mode as usual.
- **Nothing on `/system`:** it survives GSI updates and wipes, and the GSI stays as TrebleDroid
  ships it (apart from the TEE fix in docs/flashing.md).
- **Why 3.7 V:** with a nearly empty battery the bootloader refuses a normal boot (at about
  3.45 V and below it switches the phone off instead). Restarting right away would leave a
  drained phone off for good; waiting in charger mode until 3.7 V lets it charge first.
- **Seen on 2026-11-25:** `reboot -p` while plugged in → charger mode → restart after 22–35 s →
  Android with the tracker in about 2 minutes, Wi-Fi back. The kernel log says
  `wp5: KPOC autoboot: vbus=… vbat=… >= 3700 mV, rebooting to Android`. With a threshold the
  battery can't reach, the phone stays in charger mode and the power button still starts it.
- **Not tested yet:** a fully drained battery (the real power cut).

## Battery limits (20–80 %)

The tracker boot image keeps the battery between 20 and 80 %, also in the kernel, so power can
simply be cut:

- **Charge limit:** charging stops at 80 % and starts again at 75 %. In between the phone runs
  from USB and the battery current is 0. Android shows "not charging" (status 4) while plugged
  in; the app still sees power (`EXTRA_PLUGGED`), so `PowerIdle` doesn't kick in. Kernel log:
  `wp5: charge limit: soc=80 >= 80 %, charging stopped` / `... charging again`.
- **Low-battery shutdown:** on battery below 20 % the kernel reports the battery as empty
  (capacity 0, level critical) and Android shuts down cleanly ("Shutting down" on screen). Only
  without power and not in the first 2 minutes after boot; on a weak charger the battery can sink
  below 20 % without a shutdown. Kernel log: `wp5: on battery at 16 % < 20 %: reporting empty`.
  When power comes back the phone restarts by itself (see above).
- **Switches:** kernel command line `mtk_charger.charge_stop_soc=80` and
  `mt6357_battery.shutdown_soc=20` (`CHARGE_STOP_SOC=80 SHUTDOWN_SOC=20` in the build script);
  0 or missing: off. Both can be changed until the next boot as root:
  `echo 70 > /sys/module/mtk_charger/parameters/charge_stop_soc`.
- **Seen on 2026-11-25:** limit set to 65 % at 69 % → charging stopped, current 0; back to 80 % →
  charging again. Unplugged at 16 % → shutdown within 2 s; plugged in → Android again after
  about 2 minutes.

### Chargers

At least 5 V / 2 A (10 W) per phone, 5 V / 3 A (15 W) charges at full speed. The phone only takes
more than 0.5 A from a charger it recognises as a wall charger:

```
adb shell cat /sys/class/power_supply/mt6370_pmu_charger/usb_type   # wants [DCP]
```

| detected as | input limit | battery current |
|---|---|---|
| PC port (`SDP`) | 0.5 A | 0.5 A |
| charging port (`CDP`) | 1.5 A | 1.5 A |
| wall charger (`DCP`) | 3.2 A | 2.05 A |

On a PC port a running tracker (screen, camera, Wi-Fi: around 1–1.5 A) drains the battery even
though it is plugged in. USB-C PD or Quick Charge make no difference, the phone charges at 5 V.

After a boot on a nearly empty battery the app holds tracking back (`BatteryGate`): the camera
stays off below 15 % and the phone only charges. Try it with
`adb shell dumpsys battery set level 5` (and `reset`).

What didn't work, so nobody tries it again:

- **An init rule in `/system`** (`on charger` → `setprop sys.powerctl reboot`, used until
  2026-11-24): it needs `adb remount` or an offline patch of the GSI, and on kernel 6.12 the
  remount overlay doesn't survive a reboot. It also restarted at any battery level.
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
`build.prop` and `system_ext/etc/build.prop`, patched offline like the TEE fix (docs/flashing.md). The
setup run left the Linux machine's key in `/data/misc/adb/adb_keys`; add every machine's key
before switching it on, or adb is locked out (the phones hang out of reach).

Side effect of the 2026-11-16 attempt: `adb remount` disabled verity (vbmeta flag); harmless on an
unlocked phone, and needed for the patched images anyway.
