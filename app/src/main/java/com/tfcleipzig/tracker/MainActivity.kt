package com.tfcleipzig.tracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tfcleipzig.tracker.camera.CameraPipeline
import com.tfcleipzig.tracker.camera.FrameAnalyzer
import com.tfcleipzig.tracker.databinding.ActivityMainBinding
import com.tfcleipzig.tracker.debug.PhotoCheck
import com.tfcleipzig.tracker.detection.GoalDetector
import com.tfcleipzig.tracker.detection.NoopBallDetector
import com.tfcleipzig.tracker.detection.Side
import com.tfcleipzig.tracker.detection.TableEndReader
import com.tfcleipzig.tracker.detection.Zone
import com.tfcleipzig.tracker.net.ServerClient
import com.tfcleipzig.tracker.net.ServerDiscovery
import com.tfcleipzig.tracker.settings.TrackerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraPipeline
    private lateinit var discovery: ServerDiscovery
    private lateinit var settings: TrackerSettings
    private val client = ServerClient()

    @Volatile private var stats: FrameAnalyzer.Stats? = null

    @Volatile private var lastGoal: String = "-"

    private var zoom: CameraPipeline.Zoom? = null

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
        binding.zoomOut.setOnClickListener { stepZoom(-ZOOM_STEP) }
        binding.zoomIn.setOnClickListener { stepZoom(ZOOM_STEP) }
        binding.end.setOnClickListener {
            settings.end = TrackerSettings.End.entries[(settings.end.ordinal + 1) % TrackerSettings.End.entries.size]
            renderSetup()
        }
        binding.side.setOnClickListener {
            settings.side = Side.entries[(settings.side.ordinal + 1) % Side.entries.size]
            renderSetup()
        }
        renderSetup()

        lifecycleScope.launch { discovery.server.collect { renderStatus() } }
        checkPhotoExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applySettings(intent)) {
            camera.setZoom(settings.zoom)?.let { settings.zoom = it.ratio }
            renderSetup()
        }
        checkPhotoExtra(intent)
    }

    /** Settings passed as adb extras (see [TrackerSettings]); true if any were. */
    private fun applySettings(intent: Intent): Boolean {
        val applied = settings.apply(intent)
        if (applied.isNotEmpty()) Log.i(TAG, "settings from intent: ${applied.joinToString()}")
        return applied.isNotEmpty()
    }

    private fun stepZoom(step: Float) {
        val current = zoom?.ratio ?: settings.zoom
        val next = Math.round((current + step) * 100) / 100f
        camera.setZoom(next)?.let { settings.zoom = it.ratio }
    }

    private fun renderSetup() {
        binding.end.text = getString(R.string.end_button, settings.end.name)
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
        camera.shutdown()
        super.onDestroy()
    }

    private fun startCamera() {
        val goals = GoalDetector(DEFAULT_GOALS, ::onGoal)
        val analyzer =
            FrameAnalyzer(NoopBallDetector(), goals) { s ->
                stats = s
                binding.status.post { renderStatus() }
            }
        camera.start(binding.preview, analyzer, settings.zoom)
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

    private fun renderStatus() {
        val s = stats
        binding.status.text =
            buildString {
                appendLine("server ${discovery.server.value ?: "searching…"}")
                append("setup  end ${settings.end}  ${settings.side.name.lowercase()}  zoom ")
                appendLine(zoom?.let { "%.2f (%.2f–%.2f)".format(it.ratio, it.min, it.max) } ?: "%.2f".format(settings.zoom))
                if (s != null) {
                    appendLine("frame  ${s.width}x${s.height}  %.1f fps".format(s.fps))
                    appendLine(
                        "time   capture %.0f + copy %.1f + read %.1f = %.0f ms".format(s.captureAgeMs, s.copyMs, s.readMs, s.totalMs),
                    )
                    appendLine("bars   ${geometry(s.counters, s.width)}")
                    appendLine("count  ${describe(s.counters)}")
                }
                append("goal   $lastGoal")
                photoResult?.let { append("\nphoto  $it") }
            }
    }

    private fun describe(r: TableEndReader.Reading): String =
        "goals ${r.goals ?: "?"}  sets ${r.sets ?: "?"}  timeouts ${r.timeouts ?: "?"}" +
            if (r.problems.isEmpty()) "" else "\n       " + r.problems.joinToString("\n       ")

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

        // TODO: calibrate per table (tap goal corners on the preview) and persist.
        val DEFAULT_GOALS =
            GoalDetector.Config(
                homeGoal = Zone(0.00f, 0.35f, 0.08f, 0.65f),
                awayGoal = Zone(0.92f, 0.35f, 1.00f, 0.65f),
            )
    }
}
