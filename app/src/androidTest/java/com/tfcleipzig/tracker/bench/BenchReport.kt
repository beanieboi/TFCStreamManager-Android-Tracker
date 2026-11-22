package com.tfcleipzig.tracker.bench

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Where on-device benchmarks put their results: logcat (tag `Bench`), the instrumentation output
 * (shown by `am instrument -w`), and a file in the app's external files dir, which survives the
 * run when the APKs are installed by hand:
 *
 * ```
 * set -x ANDROID_SERIAL <serial>
 * ./gradlew :app:installDebug :app:installDebugAndroidTest
 * adb shell am instrument -w -e package com.tfcleipzig.tracker.bench \
 *     com.tfcleipzig.tracker.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull /sdcard/Android/data/com.tfcleipzig.tracker/files/bench/
 * ```
 */
object BenchReport {
    fun write(
        name: String,
        text: String,
    ) {
        val header = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE}), Android ${Build.VERSION.RELEASE}\n"
        val report = header + text
        report.lines().forEach { Log.i("Bench", it) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n== $name ==\n$report\n") })
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "bench")
        dir.mkdirs()
        File(dir, "$name.txt").writeText(report)
    }

    /** Median, 90th percentile and max of [values], formatted. */
    fun spread(values: List<Float>): String {
        if (values.isEmpty()) return "-"
        val s = values.sorted()
        return "median %.2f  p90 %.2f  max %.2f".format(s[s.size / 2], s[(s.size * 9) / 10], s.last())
    }
}
