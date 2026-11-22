package com.tfcleipzig.tracker.net

import com.tfcleipzig.tracker.detection.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
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

    @Serializable
    private data class CorrectIntent(
        val revision: Long,
        /** `correct` (current set's goals) or `correct_sets`. */
        val type: String,
        val home: Int,
        val away: Int,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Makes the server show [goals] and [sets] for [side], leaving the other team's as they are:
     * `correct_sets` and `correct` intents with absolute values, only for what differs. Absolute,
     * so a missed or repeated sync does no harm. Retries once on a revision conflict.
     */
    suspend fun sync(
        server: HttpUrl,
        side: Side,
        goals: Int,
        sets: Int,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                var board = scoreboard(server)
                repeat(3) {
                    val mine = board.getValue(side.name.lowercase()).jsonObject
                    val theirs = board.getValue(if (side == Side.HOME) "away" else "home").jsonObject
                    val (type, other) =
                        when {
                            mine.int("game_sets") != sets -> "correct_sets" to theirs.int("game_sets")
                            mine.int("score") != goals -> "correct" to theirs.int("score")
                            else -> return@runCatching
                        }
                    val value = if (type == "correct") goals else sets
                    val (home, away) = if (side == Side.HOME) value to other else other to value
                    val intent = CorrectIntent(board.revision(), type, home, away)
                    board = post(server, json.encodeToString(CorrectIntent.serializer(), intent))
                }
                throw IOException("sync: scoreboard still differs after 3 intents")
            }
        }

    /** Sends this phone's [report] (`POST /api/trackers`). */
    suspend fun report(
        server: HttpUrl,
        report: TrackerReport,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url(server.resolve("/api/trackers")!!)
                        .post(json.encodeToString(TrackerReport.serializer(), report).toRequestBody(JSON))
                        .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("POST /api/trackers: HTTP ${response.code}")
                }
            }
        }

    /** POST /api/intents; the scoreboard after it, or the current one on a revision conflict. */
    private fun post(
        server: HttpUrl,
        body: String,
    ): JsonObject {
        val request =
            Request
                .Builder()
                .url(server.resolve("/api/intents")!!)
                .post(body.toRequestBody(JSON))
                .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            return when {
                response.isSuccessful -> json.parseToJsonElement(text).jsonObject
                response.code == 409 ->
                    json
                        .parseToJsonElement(text)
                        .jsonObject
                        .getValue("scoreboard")
                        .jsonObject
                else -> throw IOException("POST /api/intents $body: HTTP ${response.code} $text")
            }
        }
    }

    private fun scoreboard(server: HttpUrl): JsonObject {
        val request = Request.Builder().url(server.resolve("/api/scoreboard")!!).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GET /api/scoreboard: HTTP ${response.code}")
            return json.parseToJsonElement(response.body.string()).jsonObject
        }
    }

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int

    private fun JsonObject.revision() = getValue("revision").jsonPrimitive.long

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
