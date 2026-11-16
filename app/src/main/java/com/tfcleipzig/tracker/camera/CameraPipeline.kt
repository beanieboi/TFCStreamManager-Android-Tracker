package com.tfcleipzig.tracker.camera

import android.content.Context
import android.util.Log
import android.util.Size
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

    /** [previewView] may be null to analyse without showing anything (tests, benchmarks). */
    fun start(
        previewView: PreviewView?,
        analyzer: ImageAnalysis.Analyzer,
        zoomRatio: Float = DEFAULT_ZOOM,
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
            val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
            val zoom = camera.cameraInfo.zoomState.value
            val ratio = zoom?.let { zoomRatio.coerceIn(it.minZoomRatio, it.maxZoomRatio) } ?: zoomRatio
            camera.cameraControl.setZoomRatio(ratio)
            Log.i(TAG, "camera bound, zoom $ratio (range ${zoom?.minZoomRatio}..${zoom?.maxZoomRatio})")
        }, ContextCompat.getMainExecutor(context))
    }

    /** Unbinds the camera; call on the main thread. [start] binds it again. */
    fun stop() {
        provider?.unbindAll()
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
