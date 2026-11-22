# Counter reader

Since 2026-11-22 the app reads the counters by their dots (`detection/DotReader.kt`); the reader
described below (`TableEndReader`) stays for comparison. See "Dot reader" at the end.

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

`TrackerSettings` (kept across restarts): the team the end's goal bar counts (home/away), zoom.
Set with the HUD buttons or over adb:

```
adb shell am start -n com.tfcleipzig.tracker/.MainActivity --es side away --ef zoom 2.3
```

The table end is detected, not set: at end A the set bar is mostly green and the goal bar mostly
black, at end B the reverse. Each reading compares the mean green chroma of the two bars
(`Reading.end`, `endScore` about ±0.5–0.7 on the reference photos, threshold 0.4), and
`EndVote` settles once 25 of the last 30 frames with a guess agree (about 2 s after start).

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

## Dot reader

On 2026-11-22 every stone got a fresh round marker: white on the black stones, pink on the green
ones. One dot is one stone, so `DotReader` counts instead of measuring run lengths:

1. Dots: small round blobs, bright and colourless (white) or clearly red (pink).
2. The counter row: the line most dots of one size sit on (a vote over slopes up to ±0.15, then a
   least-squares fit and a fresh pick from all dots, since the camera is rolled about 4°).
3. Bars: split at the widest gap; a blob closer than 0.9 stones to its neighbour is an end cap and
   dropped. Exactly 5 + 7 dots, or nothing is read.
4. Free rod: a thin bright line with dark above and below, away from the stones. Its position gives
   the split, including all stones at one end (goals 0 or 7).
5. Counts as before: goals right of the goal bar's free rod; sets left of the set bar's first free
   rod, timeouts 2 − stones right of its last.

`ReaderComparisonTest` runs both readers on recordings (`RECORDING=recordings/...`). On the first
recordings with the new dots (136 labelled frames, end A): both 111 correct; the old reader read one
frame wrong (goals 2 as 1), the dot reader none. Its other "wrong" frames were labelling slips.
`DotReaderPhotosTest` checks it never reads a reference photo wrong (photos with old dots may stay
unread).
