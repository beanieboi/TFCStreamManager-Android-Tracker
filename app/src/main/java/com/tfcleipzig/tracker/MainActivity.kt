package com.tfcleipzig.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tfcleipzig.tracker.camera.CameraPipeline
import com.tfcleipzig.tracker.camera.FrameAnalyzer
import com.tfcleipzig.tracker.databinding.ActivityMainBinding
import com.tfcleipzig.tracker.detection.GoalDetector
import com.tfcleipzig.tracker.detection.NoopBallDetector
import com.tfcleipzig.tracker.detection.Side
import com.tfcleipzig.tracker.detection.Zone
import com.tfcleipzig.tracker.net.ServerClient
import com.tfcleipzig.tracker.net.ServerDiscovery
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var camera: CameraPipeline
    private lateinit var discovery: ServerDiscovery
    private val client = ServerClient()

    @Volatile private var stats: FrameAnalyzer.Stats? = null

    @Volatile private var lastGoal: String = "-"

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

        lifecycleScope.launch { discovery.server.collect { renderStatus() } }
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

    private fun renderStatus() {
        val s = stats
        binding.status.text =
            buildString {
                appendLine("server ${discovery.server.value ?: "searching…"}")
                if (s != null) {
                    appendLine("frame  ${s.width}x${s.height}  %.1f fps  detect %d ms".format(s.fps, s.detectMs))
                    appendLine("ball   ${s.ball?.let { "%.2f,%.2f (%.2f)".format(it.x, it.y, it.confidence) } ?: "-"}")
                }
                append("goal   $lastGoal")
            }
    }

    private companion object {
        const val TAG = "Tracker"

        // TODO: calibrate per table (tap goal corners on the preview) and persist.
        val DEFAULT_GOALS =
            GoalDetector.Config(
                homeGoal = Zone(0.00f, 0.35f, 0.08f, 0.65f),
                awayGoal = Zone(0.92f, 0.35f, 1.00f, 0.65f),
            )
    }
}
