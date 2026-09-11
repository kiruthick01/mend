package com.mend.app.ai

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * A deliberately small Anthropic Messages API client.
 *
 * The official Java SDK adds about 7.5 MB to the APK — Jackson, OkHttp, Okio
 * and Apache HttpComponents — for what amounts to two POST requests. On a
 * phone-first app whose AI layer is explicitly optional, and against
 * PROJECT_PLAN.md Section 2's own wording ("via a thin network layer"), that
 * trade did not hold. This uses the platform's own HTTP stack and `org.json`,
 * both already present on every Android device, and adds no dependency at all.
 *
 * The cost of that choice is that the request shape is ours to get right, so it
 * is kept to the documented essentials and nothing clever.
 */
class AnthropicClient(
    private val apiKeyProvider: () -> String?,
    private val endpoint: String = MESSAGES_ENDPOINT,
) {

    /** One request to the Messages API. */
    suspend fun send(
        system: String,
        userMessage: String,
        maxTokens: Int,
        effort: String = EFFORT_LOW,
    ): AiResult<String> {
        val key = apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: return AiResult.Unavailable(AiAvailability.NO_API_KEY)

        // Everything below runs off the main thread and under a hard timeout.
        // Nothing in Mend waits on this, but an AI call that hangs would still
        // leave a spinner on screen indefinitely.
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                try {
                    execute(key, system, userMessage, maxTokens, effort)
                } catch (e: Exception) {
                    // Unreachable, DNS failure, no permission, TLS problem: all
                    // the same thing from the caller's point of view.
                    AiResult.Unavailable(
                        reason = AiAvailability.OFFLINE,
                        detail = e.message ?: e::class.java.simpleName,
                    )
                }
            } ?: AiResult.Unavailable(
                reason = AiAvailability.OFFLINE,
                detail = "the request took longer than ${REQUEST_TIMEOUT_MS / 1000}s",
            )
        }
    }

    private fun execute(
        apiKey: String,
        system: String,
        userMessage: String,
        maxTokens: Int,
        effort: String,
    ): AiResult<String> {
        val connection = (URL(endpoint).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", API_VERSION)
        }

        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", maxTokens)
            put("system", system)
            // Effort belongs inside output_config, not at the top level.
            put("output_config", JSONObject().put("effort", effort))
            put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", userMessage),
                ),
            )
        }

        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream
                    ?.bufferedReader()
                    ?.use(BufferedReader::readText)
                    .orEmpty()
                return AiResult.Failed(describeHttpFailure(status, error))
            }

            val payload = connection.inputStream.bufferedReader().use(BufferedReader::readText)
            parseResponse(JSONObject(payload))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Pulls the text out of a Messages API response.
     *
     * A refusal arrives as a perfectly ordinary 200, so `stop_reason` has to be
     * checked before the content is read — otherwise a declined request looks
     * like an empty answer.
     */
    private fun parseResponse(payload: JSONObject): AiResult<String> {
        if (payload.optString("stop_reason") == "refusal") {
            return AiResult.Failed("the model declined to answer that")
        }

        val content = payload.optJSONArray("content")
            ?: return AiResult.Failed("the response had no content")

        val text = buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") {
                    append(block.optString("text"))
                }
            }
        }.trim()

        return if (text.isEmpty()) {
            AiResult.Failed("the response contained no text")
        } else {
            AiResult.Success(text)
        }
    }

    private fun describeHttpFailure(status: Int, body: String): String {
        val apiMessage = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        return when (status) {
            401 -> "the API key was rejected"
            403 -> "that API key is not allowed to make this request"
            429 -> "rate limited; try again shortly"
            in 500..599 -> "the API is having trouble (HTTP $status)"
            else -> apiMessage ?: "the request failed (HTTP $status)"
        }
    }

    companion object {
        const val MESSAGES_ENDPOINT = "https://api.anthropic.com/v1/messages"

        /** Pinned; the API is versioned by header rather than by URL. */
        const val API_VERSION = "2023-06-01"

        const val MODEL = "claude-opus-5"

        /**
         * Both jobs here are small and well-specified, and neither benefits
         * from deep reasoning. Low effort keeps them quick and cheap.
         */
        const val EFFORT_LOW = "low"

        private const val REQUEST_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}
