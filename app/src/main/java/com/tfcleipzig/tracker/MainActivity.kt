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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraPipeline
    private lateinit var discovery: ServerDiscovery
    private val client = ServerClient()

    @Volatile private var stats: FrameAnalyzer.Stats? = null

    @Volatile private var lastGoal: String = "-"

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

        camera = CameraPipeline(this, this)
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

        lifecycleScope.launch { discovery.server.collect { renderStatus() } }
        checkPhotoExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkPhotoExtra(intent)
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
        camera.start(binding.preview, analyzer)
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
                    photoResult =
                        "${describe(r.reading)}\n       ${r.sourceWidth}x${r.sourceHeight}$aspect\n       tap photo to go back to live"
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
                if (s != null) {
                    appendLine("frame  ${s.width}x${s.height}  %.1f fps".format(s.fps))
                    appendLine(
                        "time   capture %.0f + copy %.1f + read %.1f = %.0f ms".format(s.captureAgeMs, s.copyMs, s.readMs, s.totalMs),
                    )
                    appendLine("count  ${describe(s.counters)}")
                }
                append("goal   $lastGoal")
                photoResult?.let { append("\nphoto  $it") }
            }
    }

    private fun describe(r: TableEndReader.Reading): String =
        "goals ${r.goals ?: "?"}  sets ${r.sets ?: "?"}  timeouts ${r.timeouts ?: "?"}" +
            if (r.problems.isEmpty()) "" else "\n       " + r.problems.joinToString("\n       ")

    private companion object {
        const val TAG = "Tracker"
        const val EXTRA_PHOTO = "photo"

        // TODO: calibrate per table (tap goal corners on the preview) and persist.
        val DEFAULT_GOALS =
            GoalDetector.Config(
                homeGoal = Zone(0.00f, 0.35f, 0.08f, 0.65f),
                awayGoal = Zone(0.92f, 0.35f, 1.00f, 0.65f),
            )
    }
}
