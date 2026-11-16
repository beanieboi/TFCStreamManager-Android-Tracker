# On-site debugging

For a session at the table: record what the tracker reads while moving the stones, enter what's
true, replay on the PC.

## At the table

1. Phone and your own phone on the same Wi-Fi. Open `http://<tracker ip>:8081/` (the IP is on
   the tracker's HUD, `debug` line).
2. **calibrate** once the phone is mounted (it also runs by itself on first start). The page shows
   its progress under *Phone → calib*; it ends with `done: zoom …, exposure …` or `failed: …`.
3. Set the **truth** to what the stones show, then **record**.
4. For every change: tap **hands on / moving**, move the stones, enter the new truth (that clears
   the hands flag). Frames with hands flagged are left out of the comparison.
5. Try the hard cases while recording: hands near the bars, people walking past, lights,
   shadows. Use **note** to say what you're doing ("ceiling lights off").
6. **sweep camera** cycles exposure −2…+2 EV at the zoom and ±0.5, 3 s each (27 settings,
   ~80 s a round); keep recording and moving stones meanwhile. Stop the sweep to go back to the
   calibrated settings.

Recording writes every frame's reading and about 2 raw frames a second (plus every frame where the
reading or truth changes): roughly 1 MB/s.

## Afterwards

```
adb connect <tracker ip>:5555
adb pull /sdcard/Android/data/com.tfcleipzig.tracker/files/recordings/ recordings/
RECORDING=recordings ./gradlew :app:testDebugUnitTest --tests '*RecordingReplayTest*' --rerun
```

Per session, `app/build/reports/recording/<session>.txt` lists correct / unread / wrong per camera
setting, as read live and as the current reader reads the raw frames; wrong frames are saved as PNG
in `app/build/reports/recording/<session>/`. Change the reader, replay, compare.

## Endpoints

`GET /` page, `/state` JSON, `/frame.jpg` live picture. `POST /truth?goals=&sets=&timeouts=`
(empty = unknown), `/hand?on=0|1`, `/record?on=0|1`, `/mark?text=`, `/calibrate`,
`/sweep?on=0|1`. Open to anyone on the network, like adb on these phones.
