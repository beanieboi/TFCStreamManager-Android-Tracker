# Counter reader

Reads goals, sets and timeouts at one end of the table from a 640×480 camera frame
(`detection/TableEndReader.kt`). One phone per table end, camera zoomed 1.94×.

## What it reads

From the phone at an end, both bars sit on the bottom edge of the end panel:

- **Right bar: goals.** 7 stones, one free stretch of rod. Score = stones right of it.
- **Left bar: sets + timeouts.** A fixed stop splits it: 3 stones left (sets = stones at the
  left end), 2 stones right (timeouts = 2 − stones at the right end).

Colours differ per end and per bar (Side A goals: black stones, green 5-point stone; Side B
the reverse), so nothing relies on one colour scheme.

## How

1. `StoneLine`: green chroma blobs that are stone-sized and have the dark table edge below them.
   Every bar has at least one green stone. The line through most of them (it may slope: the
   camera can be rolled) is the counter line.
2. `BarScan.classify`: one label per column along the line: green face, white dot (a blob with
   black stone body on all four sides), white face, rod (a thin bright line), nothing. Brightness
   thresholds scale with the brightest stone pixels on the line.
3. Columns group into bars; the two longest are the counters. A bar's pitch is its length over
   its known length in pitches (goal 8.94, set 9.3).
4. `Bar.counts`: stones per run, constrained to add up to 7 or 5, closest to the measured run
   lengths (a dot is one stone; end caps can count as none).
5. Checks before a count is trusted (else null, with `Reading.problems` saying why): both bars
   found with matching pitch, free stretches as long as they physically are, no blank gap in a
   bar, bar not cut off by the frame edge.

## Setup per phone

`TrackerSettings` (kept across restarts): table end (A/B), the team that end's goal bar counts
(home/away), zoom. Set with the HUD buttons or over adb:

```
adb shell am start -n com.tfcleipzig.tracker/.MainActivity --es end B --es side away --ef zoom 2.3
```

The HUD's `bars` line shows the pitch in pixels and the free margin left of the set bar and right
of the goal bar, in pitches. Digital zoom crops around the frame centre, so aim the counters at
the centre, then zoom until the margins are small but not zero. A bar touching the edge is not
read. Zoom is free up to the camera's 4×.

## Tests

| Test | What |
|---|---|
| `ReferencePhotosTest` | every photo in `testdata/` (labels in the file names); ratchet on correct / wrong |
| `ReferenceVariantsTest` | the photos darker, brighter, rolled, shifted, closer, further, covered; wrong must not grow |
| `ReferenceBenchmarkTest` (device) | read time on the phone's CPU, on the same photos |
| `CameraLatencyTest` (device) | live camera: capture age, copy, read, total, fps |

```
./gradlew :app:testDebugUnitTest            # reports in app/build/reports/reference-*.txt
REFERENCE_VERBOSE=1 ./gradlew :app:testDebugUnitTest --tests '*ReferencePhotosTest*' --rerun

set -x ANDROID_SERIAL OUKITELWP51201453     # the tablet may be plugged in too
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e package com.tfcleipzig.tracker.bench \
    com.tfcleipzig.tracker.test/androidx.test.runner.AndroidJUnitRunner
```

## Results, 2026-11-16

JVM: all 26 photos read right (78/78 counters), 0 wrong; 0.2–0.4 ms per read.
Variants: 3 wrong of 702 counters, all with an end stone or rod stretch faded out in one frame.

Oukitel (MT6761, Android 13):

| | median | p90 | max |
|---|---|---|---|
| read, reference photos | 11.9 ms | 14.8 ms | 20.8 ms |
| capture age (sensor → analyzer) | 121.6 ms | 122.1 ms | 124.9 ms |
| copy YUV planes | 0.5 ms | 0.6 ms | 0.7 ms |
| total, sensor → reading | 132.2 ms | 133.0 ms | 134.7 ms |

14.3 fps at 640×480 in room light (exposure-bound, not CPU-bound). On the device, Android's
JPEG scaling gives 75/78 counters right and 0 wrong.

## Next

- Remember bar geometry over frames (the housing doesn't move on a fixed mount) and only accept
  a count after N agreeing frames; that removes the remaining single-frame wrong readings.
- Keep reading the uncovered bar when a hand covers the other one.
- `StoneLine.greenBlobs` is most of the read time on the phone; it scans all chroma pixels.
