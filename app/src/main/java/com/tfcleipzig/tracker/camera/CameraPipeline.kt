package com.tfcleipzig.tracker.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Binds preview + analysis to the back camera. Analysis is kept small (≈640x480, latest frame
 * only) so old SoCs keep up; frames are dropped rather than queued.
 *
 * The camera is zoomed in ([DEFAULT_ZOOM]): the counters are small, and digital zoom crops the
 * sensor before the frame is scaled down to the analysis size, so every stone gets about twice the
 * pixels (11 instead of 5.6 per stone in the reference photos).
 */
class CameraPipeline(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    /** Zoom the camera runs at and its range, once bound; for the HUD. */
    data class Zoom(
        val ratio: Float,
        val min: Float,
        val max: Float,
    )

    /** Called on the main thread whenever the zoom is set. */
    var onZoom: (Zoom) -> Unit = {}

    /** Exposure compensation (in [step] EV per index) and whether exposure and white balance are locked. */
    data class Exposure(
        val index: Int,
        val range: IntRange,
        val step: Float,
        val locked: Boolean,
    )

    /** Called on the main thread whenever the exposure is set. */
    var onExposure: (Exposure) -> Unit = {}

    /** [previewView] may be null to analyse without showing anything (tests, benchmarks). */
    fun start(
        previewView: PreviewView?,
        analyzer: ImageAnalysis.Analyzer,
        zoomRatio: Float = DEFAULT_ZOOM,
        exposureIndex: Int = 0,
        /** Called on the main thread once the camera is bound and set. */
        onBound: () -> Unit = {},
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get().also { provider = it }
            val useCases = mutableListOf<UseCase>()
            if (previewView != null) {
                useCases += Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            }
            useCases +=
                ImageAnalysis
                    .Builder()
                    .setResolutionSelector(
                        ResolutionSelector
                            .Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    ANALYSIS_SIZE,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            ).build(),
                    ).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer) }

            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
            setZoom(zoomRatio)
            setExposure(exposureIndex, locked = false)
            onBound()
        }, ContextCompat.getMainExecutor(context))
    }

    /** The zoom range, once bound. */
    fun zoomRange(): ClosedFloatingPointRange<Float>? =
        camera
            ?.cameraInfo
            ?.zoomState
            ?.value
            ?.let { it.minZoomRatio..it.maxZoomRatio }

    /** The exposure compensation range, once bound; null if the camera has none. */
    fun exposureRange(): IntRange? =
        camera
            ?.cameraInfo
            ?.exposureState
            ?.takeIf { it.isExposureCompensationSupported }
            ?.exposureCompensationRange
            ?.let { it.lower..it.upper }

    /**
     * Sets exposure compensation, clamped to the camera's range, and locks or unlocks auto
     * exposure and white balance at what they currently are. Lock only after the exposure has
     * settled at the new compensation. Call on the main thread; null before [start] bound.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun setExposure(
        index: Int,
        locked: Boolean,
    ): Exposure? {
        val camera = camera ?: return null
        val state = camera.cameraInfo.exposureState
        val range = if (state.isExposureCompensationSupported) state.exposureCompensationRange.let { it.lower..it.upper } else 0..0
        val exposure = Exposure(index.coerceIn(range), range, state.exposureCompensationStep.toFloat(), locked)
        if (state.isExposureCompensationSupported) camera.cameraControl.setExposureCompensationIndex(exposure.index)
        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions
                .Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked)
                .build(),
        )
        Log.i(TAG, "exposure ${exposure.index} (${exposure.index * exposure.step} EV), ${if (locked) "locked" else "auto"}")
        onExposure(exposure)
        return exposure
    }

    /** Sets the zoom, clamped to the camera's range; call on the main thread. Null before [start] bound. */
    fun setZoom(ratio: Float): Zoom? {
        val camera = camera ?: return null
        val state = camera.cameraInfo.zoomState.value
        val min = state?.minZoomRatio ?: 1f
        val max = state?.maxZoomRatio ?: ratio
        val zoom = Zoom(ratio.coerceIn(min, max), min, max)
        camera.cameraControl.setZoomRatio(zoom.ratio)
        Log.i(TAG, "zoom ${zoom.ratio} (range $min..$max)")
        onZoom(zoom)
        return zoom
    }

    /** Unbinds the camera; call on the main thread. [start] binds it again. */
    fun stop() {
        provider?.unbindAll()
        camera = null
    }

    fun shutdown() {
        analysisExecutor.shutdown()
    }

    companion object {
        val ANALYSIS_SIZE = Size(640, 480)

        /** The zoom the reference photos in `testdata/` were taken at. */
        const val DEFAULT_ZOOM = 1.94f

        private const val TAG = "CameraPipeline"
    }
}
