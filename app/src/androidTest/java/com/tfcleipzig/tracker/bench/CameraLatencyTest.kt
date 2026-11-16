package com.tfcleipzig.tracker.bench

import android.Manifest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.tfcleipzig.tracker.camera.CameraPipeline
import com.tfcleipzig.tracker.camera.FrameAnalyzer
import com.tfcleipzig.tracker.detection.GoalDetector
import com.tfcleipzig.tracker.detection.NoopBallDetector
import com.tfcleipzig.tracker.detection.Zone
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The live pipeline the app runs, without a preview: back camera at the analysis size and zoom,
 * every frame copied and read by the counter reader. Measures, per frame:
 * - capture age: sensor exposure until the analyzer gets the frame (camera + CameraX latency),
 * - copy: the YUV planes into a [com.tfcleipzig.tracker.detection.Frame],
 * - read: the counter reader,
 * - total: sensor exposure until the reading is done,
 * and the frame rate. Read time depends on what the camera sees; point the phone at the table end
 * for numbers that match the match setup ([ReferenceBenchmarkTest] has them for the photos).
 */
@RunWith(AndroidJUnit4::class)
class CameraLatencyTest {
    @get:Rule
    val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun captureToReading() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val owner = TestOwner()
        val stats = Collections.synchronizedList(mutableListOf<FrameAnalyzer.Stats>())
        val arrivals = Collections.synchronizedList(mutableListOf<Long>())
        val done = CountDownLatch(1)
        val pipeline = CameraPipeline(instrumentation.targetContext, owner)
        val goals = GoalDetector(GoalDetector.Config(Zone(0f, 0f, 0f, 0f), Zone(0f, 0f, 0f, 0f))) {}
        val analyzer =
            FrameAnalyzer(NoopBallDetector(), goals) { s ->
                arrivals += System.nanoTime()
                stats += s
                if (stats.size >= WARMUP + FRAMES) done.countDown()
            }

        instrumentation.runOnMainSync {
            owner.registry.currentState = Lifecycle.State.RESUMED
            pipeline.start(null, analyzer)
        }
        val finished = done.await(TIMEOUT_S, TimeUnit.SECONDS)
        instrumentation.runOnMainSync {
            pipeline.stop()
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
        pipeline.shutdown()
        assertTrue("only ${stats.size} frames in $TIMEOUT_S s", finished)

        val measured = stats.drop(WARMUP).take(FRAMES)
        val times = arrivals.drop(WARMUP).take(FRAMES)
        val fps = (times.size - 1) * 1e9f / (times.last() - times.first())
        val first = measured.first()
        val read = measured.count { it.counters.goals != null || it.counters.sets != null }
        val report =
            buildString {
                appendLine(
                    "frames ${measured.size} at ${first.width}x${first.height}, zoom ${CameraPipeline.DEFAULT_ZOOM}, %.1f fps".format(fps),
                )
                appendLine("capture age ms  ${BenchReport.spread(measured.map { it.captureAgeMs })}")
                appendLine("copy ms         ${BenchReport.spread(measured.map { it.copyMs })}")
                appendLine("read ms         ${BenchReport.spread(measured.map { it.readMs })}")
                appendLine("total ms        ${BenchReport.spread(measured.map { it.totalMs })}")
                appendLine("frames with a counter read: $read/${measured.size}")
                appendLine(
                    "last reading: ${measured.last().counters.let {
                        "goals ${it.goals} sets ${it.sets} timeouts ${it.timeouts} ${it.problems}"
                    }}",
                )
            }
        BenchReport.write("camera-latency", report)
    }

    private companion object {
        const val WARMUP = 30
        const val FRAMES = 200
        const val TIMEOUT_S = 90L
    }
}
