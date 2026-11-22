package com.tfcleipzig.tracker.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.tfcleipzig.tracker.detection.Frame
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The back camera through Camera2, delivering small YUV frames (≈640×480) to a [FrameSink].
 *
 * Frames are asked for, not streamed: while reading, one capture every [readIntervalNs]
 * (5 a second), so sensor and ISP rest in between. The WP5's camera ignores frame durations and
 * frame rate ranges and streams at about 26 fps otherwise, which kept the camera HAL at 70 % CPU
 * and the phone warm. Calibration and sweeps, which score many frames per setting, get a stream
 * ([FAST_FRAME_NS]).
 *
 * The camera is zoomed in ([DEFAULT_ZOOM]): the counters are small, and digital zoom crops the
 * sensor before the frame is scaled down, so every stone gets more pixels. Zoom, exposure and
 * focus go into every request.
 *
 * Opens when the lifecycle owner starts and closes when it stops (screen off), like CameraX did:
 * [com.tfcleipzig.tracker.power.PowerIdle] relies on that to stop the camera on battery.
 */
class CameraPipeline(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    /** Gets every frame, on the analysis thread; the frame's buffers are reused afterwards. */
    fun interface FrameSink {
        fun onFrame(
            frame: Frame,
            timestampNs: Long,
        )
    }

    /** Zoom the camera runs at and its range; for the HUD. */
    data class Zoom(
        val ratio: Float,
        val min: Float,
        val max: Float,
    )

    /** Called on the main thread whenever the zoom is set. */
    var onZoom: (Zoom) -> Unit = {}

    /**
     * Auto exposure with compensation (in [step] EV per index; only the camera sweep uses it) and
     * whether exposure and white balance are locked.
     */
    data class Exposure(
        val index: Int,
        val range: IntRange,
        val step: Float,
        val locked: Boolean,
    )

    /** Called on the main thread whenever auto exposure is set. */
    var onExposure: (Exposure) -> Unit = {}

    /** What every request asks for. Changed on the main thread, read on the camera thread. */
    private data class Settings(
        val zoom: Float = DEFAULT_ZOOM,
        /** Manual exposure (ns, iso), or null for auto exposure with [compensation]. */
        val manual: Pair<Long, Int>? = null,
        val compensation: Int = 0,
        val aeLocked: Boolean = false,
        val awbLocked: Boolean = false,
        /** Between captures; [FAST_FRAME_NS] or less means a stream. */
        val intervalNs: Long = FAST_FRAME_NS,
        /** Focus region once [focusOn] ran, else continuous autofocus. */
        val focus: MeteringRectangle? = null,
    )

    @Volatile private var settings = Settings()

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraId: String? = manager.cameraIdList.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_BACK }
    private val characteristics = cameraId?.let { manager.getCameraCharacteristics(it) }
    private val activeArray: Rect = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 1, 1)

    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val analysing = AtomicBoolean(false)
    private val copier = FrameCopier()

    private var sink: FrameSink? = null

    /** Off: the camera stays closed even while the lifecycle is started ([setEnabled]). */
    @Volatile private var enabled = true
    private var onBound: (() -> Unit)? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var streaming = false
    private var nextCapture: Runnable? = null

    /** When the last single capture was sent; the next goes one interval later. */
    private var lastSingleMs = 0L

    /** Focus trigger to send with the next request, and who to tell how it went. */
    private var pendingFocus: ((Boolean) -> Unit)? = null
    private var focusWatch: ((Boolean) -> Unit)? = null

    /** White balance settles while streaming after the camera opens, then follows [Settings.awbLocked]. */
    private var awbSettledAtMs = 0L

    init {
        lifecycleOwner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (sink != null && enabled) cameraHandler.post { open() }
                }

                override fun onStop(owner: LifecycleOwner) {
                    cameraHandler.post { close() }
                }
            },
        )
    }

    /**
     * Starts delivering frames to [sink] at [zoomRatio]; opens the camera now if the lifecycle is
     * started, else when it starts. [onBound] runs once, on the main thread, when the first
     * session is ready.
     */
    fun start(
        sink: FrameSink,
        zoomRatio: Float = DEFAULT_ZOOM,
        onBound: () -> Unit = {},
    ) {
        this.sink = sink
        this.onBound = onBound
        setZoom(zoomRatio)
        if (enabled && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) cameraHandler.post { open() }
    }

    /**
     * Turns the camera off (closed, whatever the lifecycle does) or on again (opens if the
     * lifecycle is started). For holding back while the battery charges. Main thread.
     */
    fun setEnabled(on: Boolean) {
        enabled = on
        cameraHandler.post {
            when {
                !on -> close()
                sink != null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> open()
            }
        }
    }

    /** The zoom range. */
    fun zoomRange(): ClosedFloatingPointRange<Float>? =
        characteristics?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.lower..it.upper }

    /** The exposure compensation range; null if the camera has none. */
    fun exposureRange(): IntRange? =
        characteristics
            ?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?.takeIf { it.lower != it.upper }
            ?.let { it.lower..it.upper }

    /**
     * Auto exposure at compensation [index], clamped to the camera's range, with exposure and
     * white balance locked or not. Streams (the sweep scores many frames). Main thread.
     */
    fun setExposure(
        index: Int,
        locked: Boolean,
    ): Exposure {
        val range = exposureRange() ?: 0..0
        val step = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        val exposure = Exposure(index.coerceIn(range), range, step, locked)
        update { it.copy(manual = null, compensation = exposure.index, aeLocked = locked, awbLocked = locked, intervalNs = FAST_FRAME_NS) }
        Log.i(TAG, "exposure ${exposure.index} (${exposure.index * step} EV), ${if (locked) "locked" else "auto"}")
        onExposure(exposure)
        return exposure
    }

    /**
     * Manual exposure: auto exposure off, the sensor at [exposureNs] and [iso], white balance
     * locked. Auto exposure meters the whole frame, mostly dark floor around a lit table, and
     * overexposes the counters. [frameDurationNs] is the time between frames: [SLOW_FRAME_NS]
     * while reading (single captures), [FAST_FRAME_NS] while calibrating (a stream). Main thread.
     */
    fun setManualExposure(
        exposureNs: Long,
        iso: Int,
        frameDurationNs: Long = FAST_FRAME_NS,
    ): Boolean {
        update { it.copy(manual = exposureNs to iso, aeLocked = false, awbLocked = true, intervalNs = frameDurationNs) }
        Log.i(TAG, "manual exposure ${exposureNs / 1000} us, iso $iso, a frame every ${frameDurationNs / 1_000_000} ms")
        return true
    }

    /**
     * Focuses once on the region around ([x], [y]) of the camera frame (0..1, as the frames come,
     * before turning), [size] wide, and keeps the lens there. Continuous autofocus judges the
     * whole frame; the counters are what matter, and manual lens positions are ignored by this
     * camera. [onResult] gets whether the camera reports it in focus, on the main thread.
     */
    fun focusOn(
        x: Float,
        y: Float,
        size: Float,
        onResult: (Boolean) -> Unit = {},
    ): Boolean {
        if (device == null) return false
        val w = activeArray.width()
        val h = activeArray.height()
        val half = (size * minOf(w, h) / 2).toInt()
        val cx = activeArray.left + (x * w).toInt()
        val cy = activeArray.top + (y * h).toInt()
        val region =
            MeteringRectangle(
                Rect(
                    (cx - half).coerceIn(activeArray.left, activeArray.right - 1),
                    (cy - half).coerceIn(activeArray.top, activeArray.bottom - 1),
                    (cx + half).coerceIn(activeArray.left + 1, activeArray.right),
                    (cy + half).coerceIn(activeArray.top + 1, activeArray.bottom),
                ),
                MeteringRectangle.METERING_WEIGHT_MAX,
            )
        cameraHandler.post {
            settings = settings.copy(focus = region)
            pendingFocus = { ok ->
                Log.i(TAG, "focus on (%.2f, %.2f) size %.2f: %s".format(x, y, size, if (ok) "in focus" else "not in focus"))
                ContextCompat.getMainExecutor(context).execute { onResult(ok) }
            }
            requestFrames()
        }
        return true
    }

    /** Sets the zoom, clamped to the camera's range. Main thread. */
    fun setZoom(ratio: Float): Zoom {
        val range = zoomRange() ?: 1f..1f
        val zoom = Zoom(ratio.coerceIn(range.start, range.endInclusive), range.start, range.endInclusive)
        update { it.copy(zoom = zoom.ratio) }
        Log.i(TAG, "zoom ${zoom.ratio} (range ${range.start}..${range.endInclusive})")
        onZoom(zoom)
        return zoom
    }

    /** Closes the camera; it opens again when the lifecycle starts or on [start]. */
    fun stop() {
        cameraHandler.post { close() }
    }

    fun shutdown() {
        cameraHandler.post { close() }
        cameraThread.quitSafely()
        analysisExecutor.shutdown()
    }

    private fun update(change: (Settings) -> Settings) {
        cameraHandler.post {
            settings = change(settings)
            requestFrames()
        }
    }

    private fun facing(id: String) = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)

    /** The YUV size closest to [ANALYSIS_SIZE] at 4:3, not smaller. */
    private fun frameSize(): Size {
        val sizes =
            characteristics
                ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                .orEmpty()
        return sizes
            .filter { it.width * 3 == it.height * 4 && it.width >= ANALYSIS_SIZE.width }
            .minByOrNull { it.width }
            ?: ANALYSIS_SIZE
    }

    @SuppressLint("MissingPermission") // the activity asks for the camera before start()
    private fun open() {
        if (!enabled || device != null || cameraId == null) return
        manager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    configure(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                }

                override fun onError(
                    camera: CameraDevice,
                    error: Int,
                ) {
                    Log.w(TAG, "camera error $error")
                    camera.close()
                    if (device === camera) device = null
                }
            },
            cameraHandler,
        )
    }

    private fun configure(camera: CameraDevice) {
        val size = frameSize()
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).also { this.reader = it }
        reader.setOnImageAvailableListener({ onImage(it) }, cameraHandler)
        val config =
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(reader.surface)),
                { cameraHandler.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        streaming = false
                        awbSettledAtMs = SystemClock.elapsedRealtime() + AWB_SETTLE_MS
                        Log.i(TAG, "camera open, ${size.width}x${size.height}")
                        requestFrames()
                        onBound?.let { callback ->
                            onBound = null
                            ContextCompat.getMainExecutor(context).execute(callback)
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.w(TAG, "camera session failed")
                    }
                },
            )
        camera.createCaptureSession(config)
    }

    private fun close() {
        nextCapture?.let { cameraHandler.removeCallbacks(it) }
        nextCapture = null
        streaming = false
        session?.close()
        session = null
        device?.close()
        device = null
        reader?.close()
        reader = null
    }

    /**
     * Brings the camera in line with [settings]: a repeating request when streaming, else the
     * next single capture (now, or when the interval is up).
     */
    private fun requestFrames() {
        val s = session ?: return
        val stream = settings.intervalNs <= FAST_FRAME_NS || SystemClock.elapsedRealtime() < awbSettledAtMs
        nextCapture?.let { cameraHandler.removeCallbacks(it) }
        nextCapture = null
        try {
            if (stream) {
                s.setRepeatingRequest(request(), results, cameraHandler)
                streaming = true
                // Re-check once white balance has had its time, to stop streaming if reading.
                val settle = awbSettledAtMs - SystemClock.elapsedRealtime()
                if (settle > 0) schedule(settle) { requestFrames() }
            } else {
                if (streaming) {
                    s.stopRepeating()
                    streaming = false
                }
                lastSingleMs = SystemClock.elapsedRealtime()
                // A short burst: every switch between capturing and idle costs the system more than
                // the frames do (the camera-in-use indicator, the display's refresh policy).
                val burst = List(BURST) { request(single = it == BURST - 1) }
                s.captureBurst(burst, results, cameraHandler)
            }
        } catch (e: Exception) {
            Log.w(TAG, "request failed", e)
        }
    }

    private fun schedule(
        delayMs: Long,
        action: () -> Unit,
    ) {
        nextCapture?.let { cameraHandler.removeCallbacks(it) }
        val r = Runnable { action() }
        nextCapture = r
        cameraHandler.postDelayed(r, delayMs)
    }

    private fun request(single: Boolean = false): CaptureRequest {
        val d = device ?: error("no camera")
        val r = reader ?: error("no reader")
        val s = settings
        val settling = SystemClock.elapsedRealtime() < awbSettledAtMs
        return d
            .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            .apply {
                addTarget(r.surface)
                if (single) setTag(SINGLE)
                set(CaptureRequest.CONTROL_ZOOM_RATIO, s.zoom)
                val manual = s.manual
                if (manual != null) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, manual.first)
                    set(CaptureRequest.SENSOR_SENSITIVITY, manual.second)
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.compensation)
                    set(CaptureRequest.CONTROL_AE_LOCK, s.aeLocked)
                }
                set(CaptureRequest.CONTROL_AWB_LOCK, s.awbLocked && !settling)
                val focus = s.focus
                if (focus == null) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focus))
                    pendingFocus?.let { callback ->
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                        pendingFocus = null
                        focusWatch = callback
                    }
                }
            }.build()
    }

    private val results =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                focusWatch?.let { watch ->
                    when (result.get(CaptureResult.CONTROL_AF_STATE)) {
                        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> watch(true)
                        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> watch(false)
                        else -> return@let
                    }
                    focusWatch = null
                }
                // Only single captures pace the next one; results of a stream just stopped don't.
                if (streaming || request.tag !== SINGLE) return
                val sinceMs = SystemClock.elapsedRealtime() - lastSingleMs
                schedule((settings.intervalNs / 1_000_000 - sinceMs).coerceAtLeast(0)) { requestFrames() }
            }
        }

    private fun onImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val target = sink
        // One frame at a time: a frame that comes while the last is still being read is dropped.
        if (target == null || !analysing.compareAndSet(false, true)) {
            image.close()
            return
        }
        val timestamp = image.timestamp
        val frame =
            try {
                copier.copy(image)
            } finally {
                image.close()
            }
        analysisExecutor.execute {
            try {
                target.onFrame(frame, timestamp)
            } finally {
                analysing.set(false)
            }
        }
    }

    companion object {
        val ANALYSIS_SIZE = Size(640, 480)

        /** The zoom the reference photos in `testdata/` were taken at. */
        const val DEFAULT_ZOOM = 1.94f

        /** Time between captures: a stream while calibrating (many settings to score), a burst every 2 s while reading. */
        const val FAST_FRAME_NS = 33_333_333L
        const val SLOW_FRAME_NS = 2_000_000_000L

        /** Frames per single capture while reading, taken back to back. */
        const val BURST = 3

        /** After opening, stream this long so auto white balance settles before it is locked. */
        const val AWB_SETTLE_MS = 1500L

        private const val TAG = "CameraPipeline"

        /** Tags single captures, so only their results schedule the next one. */
        private val SINGLE = Any()
    }
}
