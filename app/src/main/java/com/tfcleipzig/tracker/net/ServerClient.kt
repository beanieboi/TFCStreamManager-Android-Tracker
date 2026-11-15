package com.tfcleipzig.tracker.net

import com.tfcleipzig.tracker.detection.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to the linux app's match API (`linux/src/services/match_api.rs`).
 * Commands carry the revision the client last saw; a stale one gets 409 + current scoreboard.
 */
class ServerClient(
    private val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build(),
) {
    @Serializable
    private data class GoalIntent(
        val revision: Long,
        val type: String = "goal",
        val side: Side,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Posts a goal. On 409 it retries once with the server's current revision.
     * TODO: a conflict can mean the operator already entered this goal on the tablet;
     *  decide whether the camera should then drop its goal instead of retrying.
     */
    suspend fun goal(
        server: HttpUrl,
        side: Side,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                var revision = revision(server)
                repeat(2) {
                    val body = json.encodeToString(GoalIntent.serializer(), GoalIntent(revision, side = side))
                    val request =
                        Request
                            .Builder()
                            .url(server.resolve("/api/intents")!!)
                            .post(body.toRequestBody(JSON))
                            .build()
                    http.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> return@runCatching
                            response.code == 409 ->
                                revision = revisionOf(response.body.string(), "scoreboard")
                            else -> throw IOException("POST /api/intents: HTTP ${response.code}")
                        }
                    }
                }
                throw IOException("POST /api/intents: revision conflict")
            }
        }

    private fun revision(server: HttpUrl): Long {
        val request = Request.Builder().url(server.resolve("/api/scoreboard")!!).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GET /api/scoreboard: HTTP ${response.code}")
            return revisionOf(response.body.string())
        }
    }

    private fun revisionOf(
        body: String,
        wrapper: String? = null,
    ): Long {
        var obj = json.parseToJsonElement(body).jsonObject
        if (wrapper != null) obj = obj.getValue(wrapper).jsonObject
        return obj.getValue("revision").jsonPrimitive.long
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
