package com.tfcleipzig.tracker

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings.Secure
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tfcleipzig.tracker.calibration.Calibrator
import com.tfcleipzig.tracker.calibration.ExposureTracker
import com.tfcleipzig.tracker.calibration.LostWatch
import com.tfcleipzig.tracker.camera.CameraPipeline
import com.tfcleipzig.tracker.camera.FrameAnalyzer
import com.tfcleipzig.tracker.databinding.ActivityMainBinding
import com.tfcleipzig.tracker.debug.CameraSweep
import com.tfcleipzig.tracker.debug.DebugPage
import com.tfcleipzig.tracker.debug.DebugServer
import com.tfcleipzig.tracker.debug.PhotoCheck
import com.tfcleipzig.tracker.debug.Recorder
import com.tfcleipzig.tracker.detection.EndVote
import com.tfcleipzig.tracker.detection.GoalDetector
import com.tfcleipzig.tracker.detection.NoopBallDetector
import com.tfcleipzig.tracker.detection.ScoreSync
import com.tfcleipzig.tracker.detection.Side
import com.tfcleipzig.tracker.detection.TableEnd
import com.tfcleipzig.tracker.detection.TableEndReader
import com.tfcleipzig.tracker.detection.Zone
import com.tfcleipzig.tracker.net.ServerClient
import com.tfcleipzig.tracker.net.ServerDiscovery
import com.tfcleipzig.tracker.net.TrackerReport
import com.tfcleipzig.tracker.settings.TrackerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraPipeline
    private lateinit var discovery: ServerDiscovery
    private lateinit var settings: TrackerSettings
    private val client = ServerClient()

    @Volatile private var stats: FrameAnalyzer.Stats? = null

    @Volatile private var lastGoal: String = "-"

    @Volatile private var zoom: CameraPipeline.Zoom? = null

    @Volatile private var exposure: CameraPipeline.Exposure? = null

    /** Camera sweep for recordings, from the debug page; null when off. */
    @Volatile private var sweep: CameraSweep? = null

    /** Exposure set by hand on the debug page; overrides the [tracker] until set back to auto. */
    private data class Manual(
        val exposureNs: Long,
        val iso: Int,
    ) {
        override fun toString() = "${exposureNs / 1000} us iso $iso"
    }

    @Volatile private var manual: Manual? = null

    /** Keeps the stones' brightness as the light changes; null while calibrating, sweeping or set by hand. */
    @Volatile private var tracker: ExposureTracker? = null

    /** Focus on the counters at the next reading that finds both bars (start, after calibration, debug page). */
    @Volatile private var needsFocus = true

    /** Last focus on the counters, for the HUD and debug page. */
    @Volatile private var focusStatus = "auto"

    private lateinit var recorder: Recorder
    private var debugServer: DebugServer? = null

    /** Running calibration, fed from the analysis thread; null when none runs. */
    @Volatile private var calibrator: Calibrator? = null

    /** Outcome of the last calibration, for the HUD. */
    @Volatile private var calibration: String = "-"

    /** Calibrate once the camera is bound, even with a saved calibration (adb extra). */
    private var calibrateWhenBound = false

    /** Which end of the table the camera sees, settled over the frames since start. */
    private val endVote = EndVote()

    /** Counters gone for a few frames in a row outside calibration: calibrate again. */
    private val lost = LostWatch(LOST_FRAMES)

    /** Steady counter readings go to the server (see [ScoreSync]). */
    private val scoreSync = ScoreSync(SYNC_STEADY_READINGS)

    /** Last sync outcome, for the HUD. */
    @Volatile private var syncStatus = "-"

    @Volatile private var end: TableEnd? = null

    /** Debug: result for the photo on screen, or null while the live feed shows. */
    private var photoResult: String? = null

    private val photoCheck = PhotoCheck()

    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) checkPhoto(uri.toString(), ImageDecoder.createSource(contentResolver, uri))
        }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = TrackerSettings(this)
        applySettings(intent)
        camera = CameraPipeline(this, this)
        camera.onZoom = {
            zoom = it
            renderStatus()
        }
        camera.onExposure = {
            exposure = it
            renderStatus()
        }
        calibrateWhenBound = takeCalibrateExtra(intent)
        recorder = Recorder(File(getExternalFilesDir(null), "recordings"), "${Build.MANUFACTURER} ${Build.MODEL}")
        startDebugServer()
        discovery = ServerDiscovery(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }

        binding.pickPhoto.setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.photo.setOnClickListener { closePhoto() }
        binding.calibrate.setOnClickListener { startCalibration() }
        binding.zoomOut.setOnClickListener { stepZoom(-ZOOM_STEP) }
        binding.zoomIn.setOnClickListener { stepZoom(ZOOM_STEP) }
        binding.side.setOnClickListener {
            settings.side = Side.entries[(settings.side.ordinal + 1) % Side.entries.size]
            renderSetup()
        }
        renderSetup()

        lifecycleScope.launch { discovery.server.collect { renderStatus() } }
        startReporting()
        checkPhotoExtra(intent)
    }

    /** Last report to the server, for the HUD. */
    @Volatile private var reportStatus = "-"

    /** Tells the server how this phone is doing, every [REPORT_INTERVAL_MS] while it runs (see [TrackerReport]). */
    private fun startReporting() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val server = discovery.server.value
                    if (server == null) {
                        reportStatus = "no server"
                    } else {
                        client
                            .report(server, report())
                            .onSuccess { reportStatus = "ok" }
                            .onFailure {
                                if (reportStatus == "ok" || reportStatus == "-") Log.w(TAG, "report not sent", it)
                                reportStatus = "failed: ${it.message}"
                            }
                    }
                    delay(REPORT_INTERVAL_MS)
                }
            }
        }
    }

    /** This phone's state for the server; main thread. */
    private fun report(): TrackerReport {
        val s = stats
        val r = s?.counters
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level =
            battery?.let {
                val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (l >= 0 && scale > 0) l * 100 / scale else null
            }
        val charging =
            battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.takeIf { it >= 0 }?.let {
                it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
            }
        val temperature =
            battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let {
                it /
                    10f
            }

        @Suppress("DEPRECATION") // connectionInfo is the simple way on API 33 without a network callback
        val rssi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).connectionInfo?.rssi?.takeIf { it > -127 }
        val ip = wifiAddress()
        val id = Secure.getString(contentResolver, Secure.ANDROID_ID) ?: "unknown"
        val hand = manual
        val e = if (hand != null) ExposureTracker.Exposure(hand.exposureNs, hand.iso) else tracker?.exposure ?: settings.exposure
        return TrackerReport(
            id = id,
            name = "${Build.MODEL} ${id.takeLast(4)}",
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "?",
            ip = ip,
            debugUrl = ip?.let { "http://$it:${DebugServer.PORT}/" },
            uptimeS = SystemClock.elapsedRealtime() / 1000,
            battery = TrackerReport.Battery(level, charging, temperature),
            wifiRssi = rssi,
            tableEnd = end?.name,
            side = settings.side.name.lowercase(),
            reading = r?.let { TrackerReport.Reading(it.goals, it.sets, it.timeouts, it.confidence, it.problems) },
            camera =
                TrackerReport.Camera(
                    fps = s?.fps,
                    zoom = zoom?.ratio,
                    rotation = (s?.rotation ?: settings.rotation).degrees,
                    exposureNs = e.exposureNs,
                    iso = e.iso,
                    stoneLuma = r?.stoneLuma,
                    targetLuma = settings.stoneLuma,
                    readMs = s?.readMs,
                ),
            calibration = calibrator?.status ?: calibration,
            sync = syncStatus,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applySettings(intent)) {
            camera.setZoom(settings.zoom)?.let { settings.zoom = it.ratio }
            renderSetup()
        }
        if (takeCalibrateExtra(intent)) startCalibration()
        checkPhotoExtra(intent)
    }

    /** Settings passed as adb extras (see [TrackerSettings]); true if any were. */
    private fun applySettings(intent: Intent): Boolean {
        val applied = settings.apply(intent)
        if (applied.isNotEmpty()) Log.i(TAG, "settings from intent: ${applied.joinToString()}")
        return applied.isNotEmpty()
    }

    /** `adb shell am start -n com.tfcleipzig.tracker/.MainActivity --ez calibrate true` */
    private fun takeCalibrateExtra(intent: Intent): Boolean {
        val calibrate = intent.getBooleanExtra(EXTRA_CALIBRATE, false)
        intent.removeExtra(EXTRA_CALIBRATE)
        return calibrate
    }

    /** Camera bound with the saved zoom: calibrate, or set the saved exposure and keep it tracking. */
    private fun onCameraBound() {
        if (calibrateWhenBound || !settings.calibrated) {
            calibrateWhenBound = false
            startCalibration()
            return
        }
        calibration = "saved"
        useSavedExposure()
    }

    /** The saved manual exposure on the camera, tracked from there; main thread. */
    private fun useSavedExposure() {
        val e = settings.exposure
        camera.setManualExposure(e.exposureNs, e.iso)
        tracker = ExposureTracker(settings.stoneLuma, e)
    }

    /** Finds zoom and exposure for the counters (see [Calibrator]); replaces a running one. */
    private fun startCalibration() {
        stopSweep(restore = false)
        manual = null
        tracker = null
        val zoomRange = camera.zoomRange() ?: return
        val c = Calibrator(zoomRange, settings.zoom, settings.exposure, settings.rotation)
        lost.reset()
        calibrator = c
        Log.i(TAG, "calibration started")
        applyCalibration(c, c.start())
    }

    /** Carries out [commands] from [c] on the camera; main thread. */
    private fun applyCalibration(
        c: Calibrator,
        commands: List<Calibrator.Command>,
    ) {
        if (calibrator !== c) return // replaced or cancelled meanwhile
        for (command in commands) {
            when (command) {
                is Calibrator.Command.Zoom -> camera.setZoom(command.ratio)
                is Calibrator.Command.SetExposure -> camera.setManualExposure(command.exposure.exposureNs, command.exposure.iso)
                is Calibrator.Command.Done -> {
                    settings.zoom = command.zoom
                    settings.exposure = command.exposure
                    settings.stoneLuma = command.stoneLuma
                    settings.rotation = command.rotation
                    tracker = ExposureTracker(command.stoneLuma, command.exposure)
                    needsFocus = true
                    settings.calibrated = true
                    calibrator = null
                    calibration = c.status
                    Log.i(TAG, "calibration ${c.status}")
                }
                is Calibrator.Command.Failed -> {
                    calibrator = null
                    // The camera is back at the start exposure; keep it tracking if there was a calibration.
                    if (settings.calibrated) tracker = ExposureTracker(settings.stoneLuma, command.exposure)
                    calibration = c.status
                    Log.w(TAG, "calibration ${c.status}")
                }
            }
        }
        renderStatus()
    }

    /** What the camera is doing apart from reading, for recordings. */
    private val mode get() =
        when {
            calibrator != null -> "calibrate"
            sweep != null -> "sweep"
            manual != null -> "manual $manual"
            else -> ""
        }

    /** The debug page on port 8081, for watching and driving the phone from the table. */
    private fun startDebugServer() {
        val host =
            object : DebugPage.Host {
                override val reading get() = stats?.counters
                override val end get() = this@MainActivity.end
                override val fps get() = stats?.fps ?: 0f
                override val zoom get() = this@MainActivity.zoom?.ratio
                override val exposure get() = this@MainActivity.exposure?.index
                override val exposureLocked get() = this@MainActivity.exposure?.locked ?: false
                override val mode get() = this@MainActivity.mode
                override val calibration get() = calibrator?.status ?: this@MainActivity.calibration
                override val rotation get() = stats?.rotation?.degrees ?: settings.rotation.degrees
                override val sync get() = syncStatus
                override val manual get() = this@MainActivity.manual?.toString()
                override val exposureInfo get() = exposureInfo()

                override fun manual(
                    exposureNs: Long,
                    iso: Int,
                ) = runOnUiThread { setManual(exposureNs, iso) }

                override val focus get() = focusStatus

                override fun focus(
                    x: Float?,
                    y: Float?,
                    size: Float?,
                ) {
                    if (x == null || y == null) {
                        needsFocus = true
                        return
                    }
                    focusStatus = "focusing on (%.2f, %.2f)".format(x, y)
                    runOnUiThread {
                        camera.focusOn(x, y, size ?: 0.15f) { ok ->
                            focusStatus =
                                "on (%.2f, %.2f) %s".format(x, y, if (ok) "ok" else "not confirmed")
                        }
                    }
                }

                override fun zoom(ratio: Float) = runOnUiThread { setZoomTo(ratio) }

                override val sweeping get() = sweep != null

                override fun calibrate() = runOnUiThread { startCalibration() }

                override fun sweep(on: Boolean) = runOnUiThread { if (on) startSweep() else stopSweep(restore = true) }
            }
        val page = DebugPage(this, recorder, host)
        debugServer =
            DebugServer(handle = page::handle).also { server ->
                runCatching { server.start() }.onFailure { Log.w(TAG, "no debug page", it) }
            }
    }

    /** [t] wants [e] for the light now; main thread. */
    private fun followLight(
        t: ExposureTracker,
        e: ExposureTracker.Exposure,
    ) {
        if (tracker !== t) return // calibration, sweep or a hand setting took over meanwhile
        camera.setManualExposure(e.exposureNs, e.iso)
        settings.exposure = e
        Log.i(TAG, "light changed: exposure %.1f ms iso %d".format(e.exposureNs / 1e6, e.iso))
    }

    /** Manual exposure from the debug page, or back to the saved auto exposure for 0; main thread. */
    private fun setManual(
        exposureNs: Long,
        iso: Int,
    ) {
        if (exposureNs <= 0L) {
            manual = null
            useSavedExposure()
            return
        }
        calibrator = null
        tracker = null
        stopSweep(restore = false)
        if (camera.setManualExposure(exposureNs, iso)) manual = Manual(exposureNs, iso)
    }

    /** Cycles zoom × exposure for a recording (see [CameraSweep]); main thread. */
    private fun startSweep() {
        if (sweep != null) return
        manual = null
        tracker = null
        calibrator = null
        val zooms = CameraSweep.zoomsAround(settings.zoom, camera.zoomRange() ?: return)
        sweep =
            CameraSweep(zooms, camera.exposureRange() ?: 0..0) { z, e ->
                camera.setZoom(z)
                camera.setExposure(e, locked = false)
            }.also { it.start() }
    }

    /** Stops the sweep; with [restore], back to the saved zoom and exposure. Main thread. */
    private fun stopSweep(restore: Boolean) {
        val s = sweep ?: return
        s.stop()
        sweep = null
        if (!restore) return
        camera.setZoom(settings.zoom)
        useSavedExposure()
    }

    private fun stepZoom(step: Float) {
        // Zooming by hand overrides a running calibration or sweep.
        calibrator = null
        stopSweep(restore = false)
        val current = zoom?.ratio ?: settings.zoom
        val next = Math.round((current + step) * 100) / 100f
        camera.setZoom(next)?.let { settings.zoom = it.ratio }
        needsFocus = true
    }

    /** Zoom set by hand from the debug page; overrides calibration or sweep, kept. Main thread. */
    private fun setZoomTo(ratio: Float) {
        calibrator = null
        stopSweep(restore = false)
        camera.setZoom(ratio)?.let { settings.zoom = it.ratio }
        needsFocus = true
    }

    private fun renderSetup() {
        binding.side.text = getString(R.string.side_button, settings.side.name.lowercase())
        renderStatus()
    }

    /**
     * Debug without the photo picker (MediaStore on the tracker phones may not index anything):
     *   adb push x.jpg /sdcard/Android/data/com.tfcleipzig.tracker/files/
     *   adb shell am start -n com.tfcleipzig.tracker/.MainActivity --es photo x.jpg
     * A relative name is looked up in the app's external files dir.
     */
    private fun checkPhotoExtra(intent: Intent) {
        val name = intent.getStringExtra(EXTRA_PHOTO) ?: return
        intent.removeExtra(EXTRA_PHOTO)
        val file = File(name).takeIf { it.isAbsolute } ?: File(getExternalFilesDir(null), name)
        checkPhoto(file.path, ImageDecoder.createSource(file))
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }

    override fun onDestroy() {
        debugServer?.stop()
        recorder.stop()
        sweep?.stop()
        camera.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
        val goals = GoalDetector(DEFAULT_GOALS, ::onGoal)
        val analyzer =
            FrameAnalyzer(
                NoopBallDetector(),
                goals,
                rotations = { calibrator?.rotations ?: listOf(settings.rotation) },
                onFrame = { frame, reading, readMs ->
                    val z = zoom
                    val e = exposure
                    recorder.record(frame, reading, Recorder.Camera(z?.ratio, e?.index, e?.locked ?: false, mode), readMs)
                },
            ) { s ->
                val settled = endVote.add(s.counters.end)
                if (settled != end) Log.i(TAG, "table end $settled")
                end = settled
                if (needsFocus && calibrator == null) focusOnCounters(s)
                val c = calibrator
                if (c != null) {
                    val commands = c.feed(s.readings, s.rawWidth, s.rawHeight)
                    if (commands.isNotEmpty()) runOnUiThread { applyCalibration(c, commands) }
                } else if (sweep == null) {
                    // Only readings at the calibrated settings count; a sweep tries bad ones on purpose.
                    // While exposure is set by hand, a bad value must not start a calibration.
                    if (lost.update(s.counters) && manual == null) {
                        Log.i(TAG, "counters lost for $LOST_FRAMES readings, recalibrating")
                        runOnUiThread { if (calibrator == null && sweep == null) startCalibration() }
                    } else {
                        scoreSync.update(s.counters)?.let(::sendScore)
                        val t = tracker
                        t?.update(s.counters.stoneLuma)?.let { e -> runOnUiThread { followLight(t, e) } }
                    }
                }
                stats = s
                binding.status.post { renderStatus() }
            }
        camera.start(binding.preview, analyzer, settings.zoom) { onCameraBound() }
    }

    /**
     * Focuses on the counters in [s]'s reading, if it found both bars; analysis thread. The
     * region spans both bars with some room, mapped from the turned frame back to the camera's.
     */
    private fun focusOnCounters(s: FrameAnalyzer.Stats) {
        val r = s.counters
        val line = r.line ?: return
        val set = r.setBar ?: return
        val goal = r.goalBar ?: return
        needsFocus = false
        val cx = (set.left + goal.right) / 2f
        val (x, y) = s.rotation.toCamera(cx / s.width, line.centerAt(cx.toInt()) / s.height)
        val size = ((goal.right - set.left) * FOCUS_MARGIN / s.width).coerceIn(0.1f, 0.8f)
        focusStatus = "focusing"
        runOnUiThread {
            val started =
                camera.focusOn(x, y, size) { ok ->
                    focusStatus = if (ok) "on counters" else "on counters, not confirmed"
                }
            if (!started) needsFocus = true
        }
    }

    /** Puts a steady reading on the server's scoreboard for this end's team; any thread. */
    private fun sendScore(score: ScoreSync.Score) {
        val server = discovery.server.value
        if (server == null) {
            syncStatus = "no server"
            scoreSync.failed(score)
            return
        }
        val side = settings.side
        lifecycleScope.launch {
            client
                .sync(server, side, score.goals, score.sets)
                .onSuccess {
                    scoreSync.accepted(score)
                    syncStatus = "sent ${side.name.lowercase()} goals ${score.goals} sets ${score.sets}"
                    Log.i(TAG, syncStatus)
                }.onFailure {
                    syncStatus = "failed: ${it.message}"
                    Log.w(TAG, "score not sent", it)
                    delay(SYNC_RETRY_MS)
                    scoreSync.failed(score)
                }
        }
    }

    /** Called on the analysis thread. */
    private fun onGoal(side: Side) {
        lastGoal = side.name.lowercase()
        Log.i(TAG, "goal for $side")
        val server = discovery.server.value
        if (server == null) {
            Log.w(TAG, "no server, goal dropped")
            return
        }
        lifecycleScope.launch {
            client.goal(server, side).onFailure { Log.w(TAG, "goal not sent", it) }
        }
    }

    private fun checkPhoto(
        name: String,
        source: ImageDecoder.Source,
    ) {
        photoResult = "reading…"
        renderStatus()
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.Default) { photoCheck.check(source) } }
            result
                .onSuccess { r ->
                    binding.photo.setImageBitmap(r.image)
                    binding.photo.visibility = View.VISIBLE
                    val aspect = if (r.sourceWidth * 3 != r.sourceHeight * 4) "  [not 4:3 landscape, squeezed]" else ""
                    val bars = geometry(r.reading, PhotoCheck.WIDTH)
                    photoResult =
                        listOf(
                            describe(r.reading),
                            bars,
                            "${r.sourceWidth}x${r.sourceHeight}$aspect",
                            "tap photo to go back to live",
                        ).joinToString("\n       ")
                    Log.i(TAG, "photo $name: ${r.reading}")
                }.onFailure {
                    Log.w(TAG, "photo $name failed", it)
                    photoResult = "failed: ${it.message}"
                }
            renderStatus()
        }
    }

    private fun closePhoto() {
        binding.photo.visibility = View.GONE
        binding.photo.setImageDrawable(null)
        photoResult = null
        renderStatus()
    }

    /** "10.3 ms iso 400 (tracking), stones 196 / target 200" for the HUD and debug page. */
    private fun exposureInfo(): String {
        val hand = manual
        val e = if (hand != null) ExposureTracker.Exposure(hand.exposureNs, hand.iso) else tracker?.exposure ?: settings.exposure
        val how =
            when {
                hand != null -> "by hand"
                calibrator != null -> "calibrating"
                sweep != null -> "sweep"
                tracker != null -> "tracking"
                else -> "saved"
            }
        val stones = stats?.counters?.stoneLuma?.let { "%.0f".format(it) } ?: "?"
        return "%.1f ms iso %d (%s), stones %s / target %.0f".format(e.exposureNs / 1e6, e.iso, how, stones, settings.stoneLuma)
    }

    private fun renderStatus() {
        val s = stats
        binding.status.text =
            buildString {
                appendLine("server ${discovery.server.value ?: "searching…"}")
                appendLine("phone  ${wifiAddress() ?: "no wifi"}  adb :5555")
                append(
                    "setup  end ${end ?: "?"}  ${settings.side.name.lowercase()}  turn ${(s?.rotation ?: settings.rotation).degrees}°  zoom ",
                )
                append(zoom?.let { "%.2f (%.2f–%.2f)".format(it.ratio, it.min, it.max) } ?: "%.2f".format(settings.zoom))
                appendLine(
                    exposure?.let {
                        "  exposure %+.1f EV %s".format(it.index * it.step, if (it.locked) "locked" else "auto")
                    } ?: "",
                )
                appendLine("calib  ${calibrator?.status ?: calibration}")
                appendLine("sync   $syncStatus  report $reportStatus")
                appendLine("light  ${exposureInfo()}")
                if (recorder.recording) appendLine("rec    ${recorder.session?.name}  ${recorder.frames} frames, ${recorder.rawFrames} raw")
                appendLine("debug  http://${wifiAddress() ?: "?"}:${DebugServer.PORT}/")
                if (s != null) {
                    appendLine("frame  ${s.width}x${s.height}  %.1f fps".format(s.fps))
                    appendLine(
                        "time   capture %.0f + copy %.1f + read %.1f = %.0f ms".format(s.captureAgeMs, s.copyMs, s.readMs, s.totalMs),
                    )
                    appendLine("bars   ${geometry(s.counters, s.width)}")
                    appendLine("count  ${describe(s.counters)}  conf %.2f".format(s.counters.confidence))
                }
                append("goal   $lastGoal")
                photoResult?.let { append("\nphoto  $it") }
            }
    }

    private fun describe(r: TableEndReader.Reading): String =
        "goals ${r.goals ?: "?"}  sets ${r.sets ?: "?"}  timeouts ${r.timeouts ?: "?"}" +
            if (r.problems.isEmpty()) "" else "\n       " + r.problems.joinToString("\n       ")

    /** This phone's IPv4 address on Wi-Fi, to reach it with `adb connect`. */
    private fun wifiAddress(): String? =
        runCatching {
            NetworkInterface
                .getByName("wlan0")
                ?.inetAddresses
                ?.toList()
                ?.firstOrNull { it is Inet4Address }
                ?.hostAddress
        }.getOrNull()

    /**
     * Bar pitch in pixels (11 at the reference zoom) and the free margin left of the set bar and
     * right of the goal bar, in pitches: what to look at when setting the zoom.
     */
    private fun geometry(
        r: TableEndReader.Reading,
        width: Int,
    ): String {
        val set = r.setBar
        val goal = r.goalBar
        if (set == null || goal == null) return "-"
        return "pitch %.1f / %.1f px  margin %.1f / %.1f pitches".format(
            set.pitch,
            goal.pitch,
            set.left / set.pitch,
            (width - goal.right) / goal.pitch,
        )
    }

    private companion object {
        const val TAG = "Tracker"
        const val ZOOM_STEP = 0.05f
        const val EXTRA_PHOTO = "photo"
        const val EXTRA_CALIBRATE = "calibrate"
        const val LOST_FRAMES = 3
        const val SYNC_RETRY_MS = 2000L

        /** How often the phone reports to the server. */
        const val REPORT_INTERVAL_MS = 5000L

        /** Focus region: both bars' span times this. */
        const val FOCUS_MARGIN = 1.3f

        /** Readings in a row that must agree before a score is sent: about 0.5 s at 25 fps. */
        const val SYNC_STEADY_READINGS = 12

        // TODO: calibrate per table (tap goal corners on the preview) and persist.
        val DEFAULT_GOALS =
            GoalDetector.Config(
                homeGoal = Zone(0.00f, 0.35f, 0.08f, 0.65f),
                awayGoal = Zone(0.92f, 0.35f, 1.00f, 0.65f),
            )
    }
}
