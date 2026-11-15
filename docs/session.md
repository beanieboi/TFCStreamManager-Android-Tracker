# Session notes: goal counter (Torzähler) detection, 2026-11-15

Hand-off from a Claude Code session on the Mac, to continue on Linux.

## Phone

- Oukitel running a LineageOS 20 GSI (`lineage_arm64_bvN`, model `TrebleDroid_vanilla`), adb serial `OUKITELWP51201453`.
- Many stock apps were removed with `pm uninstall --user 0`: calculator, contacts, deskclock, dialer, documentsui, gallery3d, messaging, audiofx, eleven, etar, recorder, and the camera.
- The stock camera (Aperture) was restored with:
  `adb shell cmd package install-existing org.lineageos.aperture`
- Photos are in `/sdcard/DCIM/Camera/`.
- adb isn't installed on the Mac. Run it through nix: `nix shell nixpkgs#android-tools -c adb ...`

## Test photo

- `2026-11-15-16-14-14-886.jpg`, 4160×3120 with no EXIF rotation. The camera looks down along Tisch 1 from roughly where the tracker will be mounted.
- Copies:
  - repo root (untracked, put there by you)
  - `app/src/test/resources/goal-counter/2026-11-15-16-14-14-886.jpg` (the test reads this one)
- There are two earlier photos on the phone, from 16:02 and 16:08. I haven't looked at them yet.

## How the counter works (as you explained it)

- **Only the bottom-right counter** is in scope, at the near end of the table, right side in the image.
- 7 stones slide on a rod and don't change colour. From this camera's view, **scored stones sit at the right end** and unscored ones at the left end.
- The **spacer** (the free stretch of rod) separates them. The number of stones right of the spacer is the score.
  - 0 means all 7 stones are on the left.
  - 7 is the total on every table.
- The **green stone is the 5th from the right** (the 5-point stone, since a set usually ends at 5). It's right of the spacer only when the score is **5 or more**.
- The photo shows **3**:

  ```
  [W][W][G][W] ──── [W][▢][▢]
   7  6  5  4         3  2  1
  ```

  The two rightmost stones have worn white markers. You offered to renew the markers; that would help, since the reader relies on the bright dots.

## Resolution

- The analysis stream runs at **640×480 YUV** (`CameraPipeline.ANALYSIS_SIZE`).
- At that size the bar is about 53 px wide with about 5.6 px per stone, but the stones still show as separate bright dots. Simulated crops confirmed it's readable.
- In the 640×480 frame:
  - bar x is 337.5 to 390.5
  - stone rows are y 369–374
  - the rod is on rows 371–372
  - the spacer is about 13 px, roughly 25% of the bar

## Code written this session (uncommitted)

### `app/src/main/java/com/tfcleipzig/tracker/detection/GoalCounterReader.kt`

Pure Kotlin with no Android types:

- **Input:** the luma plane, plus a `Calibration` giving the bar rectangle in normalized coordinates, `stones = 7`, `spacer = 0.25` and `maxSpacerContrast = 0.5`.
- **Method:**
  1. For each column, average the brightness of the band's outer rows (top and bottom thirds), skipping the middle third where the rod is.
  2. Use prefix sums to find the darkest window of spacer width. That window is the spacer.
  3. Score = round((barWidth − spacerStart − spacerWidth) / pitch), with pitch = (barWidth − spacerWidth) / 7.
- **Returns null** when the spacer mean is more than 0.5× the stone mean, i.e. nothing stands out (for example, a hand over the bar).
- **Confidence** = 1 − contrast.

### `app/src/test/java/com/tfcleipzig/tracker/detection/GoalCounterReaderTest.kt`

| Test | Status |
|---|---|
| `readsEveryScoreOnASyntheticBar` (drawn bar, scores 0–7) | pass |
| `rejectsACoveredBar` (uniform grey → null) | pass |
| `readsThreeFromThePhoto` (photo scaled to 640×480, BT.601 luma; reads 3, confidence ≈ 0.65) | pass |
| `toleratesAShiftedCalibration` (sweeps dx −2..2, dy −1..1) | **fail** |

The failing sweep:

- With dy = 0, every dx from −2 to 2 reads 3.
- Every **dy = ±1 returns null**. The band is only 6 rows tall, so a 1 px vertical shift moves the rod into the "outer" rows and the spacer is no longer dark.

`TISCH_1_BOTTOM_RIGHT` in the test holds the calibration measured from the photo.

## Running the tests

### With Gradle (needs an Android SDK)

```
./gradlew :app:testDebugUnitTest --tests '*GoalCounterReaderTest*'
```

The tests read the photo through a path relative to `app/`, which is Gradle's working directory for unit tests.

### Without an SDK (what I used on the Mac)

```
# junit-4.13.2.jar and hamcrest-core-1.3.jar from Maven Central into ./lib
nix shell nixpkgs#kotlin nixpkgs#jdk21_headless
cd app
kotlinc -cp ../lib/junit.jar:../lib/hamcrest.jar \
  src/main/java/com/tfcleipzig/tracker/detection/GoalCounterReader.kt \
  src/test/java/com/tfcleipzig/tracker/detection/GoalCounterReaderTest.kt -d /tmp/gcr.jar
java -Djava.awt.headless=true \
  -cp /tmp/gcr.jar:../lib/junit.jar:../lib/hamcrest.jar:<kotlin-stdlib.jar> \
  org.junit.runner.JUnitCore com.tfcleipzig.tracker.detection.GoalCounterReaderTest
```

On the Mac, `.tool-versions` pins Java 26.0.1, which mise can't install on macos-arm64. That's why the nix JDK 21 was used.

## Next steps

1. **Fix the vertical shift.** Locate the rod row inside a taller search band instead of assuming it's in the middle. The rod is the row that stays bright through the spacer while the rows next to it are dark. Then take the outer rows relative to the rod. After that, `toleratesAShiftedCalibration` should pass.
2. **Use the green stone as a check.** If the score is under 5, the green stone should be the 3rd from the left end. If it's 5 or more, it should be right of the spacer. Reject or lower confidence when the two disagree.
3. **Smooth over time.** Only accept a new score after N consecutive frames agree, and only allow +1 or −1 steps (or a reset to 0). This would replace or complement the ball-based `GoalDetector`.
4. **Wire it in.** Read the Y plane from `ImageProxy` in `FrameAnalyzer`, using the plane's `rowStride`, and send score changes through `ServerClient`.
5. **Calibration on the device.** The mount is approximate, so calibrate on the device: a calibration screen, or auto-locating the bar from the green stone.
6. **More photos.** Take some at other scores (0, 4, 5, 7), in different light, and with a hand partly covering the bar. Renew the worn markers first.
