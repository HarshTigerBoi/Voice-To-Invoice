package com.voicetoinvoice.app.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

sealed class SttResult {
    data class Success(val transcript: String) : SttResult()
    data class Error(val code: Int?, val message: String) : SttResult()
}

/**
 * Synchronous-looking wrapper over the asynchronous `process-voice-job` Edge Function,
 * used by [com.voicetoinvoice.app.domain.processor.BackgroundSttProcessor] — the
 * in-app fallback that runs when WorkManager enqueue fails.
 *
 * This previously did not work at all. It posted `model` and `language_code` fields
 * that `process-voice-job` never reads (it sets both server-side), and — fatally —
 * omitted `jobId`, which that function requires, so every call was rejected with
 * HTTP 400 before any audio was transcribed. Even had it been accepted, the function
 * replies 202 `{status: "QUEUED"}` with no transcript, while this client only looked
 * for a `transcript`/`text` field and reported "empty transcript" regardless.
 *
 * It now speaks the real contract: submit with a job id, then poll `stt_job_logs`
 * until the background pipeline publishes a terminal status. See ISSUE-020.
 */
class SttProxyClient(
    private val endpointUrl: String = SupabaseConfig.STT_PROXY_ENDPOINT,
    private val anonKey: String = SupabaseConfig.SUPABASE_ANON_KEY
) {

    /**
     * Uploads [audioFile] and waits for the server-side pipeline to publish a result.
     *
     * @param jobId defaults to a fresh UUID per call. This matters: the Edge Function
     *   is idempotent per job id and replays the cached log for one it has already
     *   processed, so reusing an id across the adaptive audio-expansion retries would
     *   return the first pass's transcript for every subsequent pass.
     */
    suspend fun transcribeAudioProxy(
        audioFile: File,
        catalogContext: List<String> = emptyList(),
        jobId: String = UUID.randomUUID().toString(),
        shopId: String? = null,
        onDeviceTranscript: String = "",
        onDeviceStatus: String = "",
        previousJobId: String? = null,
        precedingGapMs: Long = -1L
    ): SttResult = withContext(Dispatchers.IO) {
        if (!audioFile.exists()) {
            return@withContext SttResult.Error(null, "Audio file does not exist")
        }

        val fileSize = audioFile.length()
        if (fileSize < 1000L) { // Less than ~0.1 sec audio
            return@withContext SttResult.Error(null, "Audio too short ($fileSize bytes). Please hold button longer to speak.")
        }

        val boundary = "Boundary-${System.currentTimeMillis()}"
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        var connection: HttpURLConnection? = null
        try {
            val url = URL(endpointUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                doInput = true
                doOutput = true
                useCaches = false
                requestMethod = "POST"
                connectTimeout = 30000 // 30s
                readTimeout = 60000    // 60s
                setRequestProperty("Authorization", "Bearer $anonKey")
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Connection", "Keep-Alive")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(connection.outputStream).use { output ->
                fun writeString(str: String) {
                    output.write(str.toByteArray(Charsets.UTF_8))
                }

                fun writeField(name: String, value: String) {
                    writeString("$twoHyphens$boundary$lineEnd")
                    writeString("Content-Disposition: form-data; name=\"$name\"$lineEnd$lineEnd")
                    writeString("$value$lineEnd")
                }

                // Required by the Edge Function; its absence was the 400 above.
                writeField("jobId", jobId)

                SupabaseConfig.getNullSafeShopId(shopId)?.let { writeField("shopId", it) }

                if (onDeviceTranscript.isNotBlank()) {
                    writeField("onDeviceTranscript", onDeviceTranscript)
                }

                if (onDeviceStatus.isNotBlank()) {
                    writeField("onDeviceStatus", onDeviceStatus)
                }

                previousJobId?.let { writeField("previousJobId", it) }
                if (precedingGapMs >= 0L) {
                    writeField("precedingGapMs", precedingGapMs.toString())
                }

                if (catalogContext.isNotEmpty()) {
                    writeField("catalogNames", JSONArray(catalogContext).toString())
                }

                writeField("metadata", JSONObject().apply {
                    put("recordedAtMs", System.currentTimeMillis())
                }.toString())

                val filename = if (audioFile.name.endsWith(".wav", ignoreCase = true)) audioFile.name else "${audioFile.name}.wav"
                writeString("$twoHyphens$boundary$lineEnd")
                writeString("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"$lineEnd")
                writeString("Content-Type: audio/wav$lineEnd$lineEnd")

                FileInputStream(audioFile).use { input ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
                writeString(lineEnd)
                writeString("$twoHyphens$boundary$twoHyphens$lineEnd")
                output.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "HTTP Response Code: $responseCode (job $jobId)")

            val inputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseString = inputStream?.bufferedReader()?.use { it.readText() } ?: ""

            if (responseCode !in 200..299) {
                var errDetail = responseString
                try {
                    val json = JSONObject(responseString)
                    if (json.has("error")) {
                        val errObj = json.get("error")
                        errDetail = if (errObj is JSONObject) errObj.optString("message", responseString) else errObj.toString()
                    }
                } catch (_: Exception) {}

                Log.e(TAG, "STT proxy error HTTP $responseCode: $errDetail")
                return@withContext SttResult.Error(responseCode, "HTTP $responseCode: $errDetail")
            }

            if (responseString.isBlank()) {
                return@withContext SttResult.Error(responseCode, "Empty response body from STT proxy")
            }

            val json = JSONObject(responseString)

            // A cached (already-processed) job comes back complete on the first call.
            val immediateTranscript = json.optString("raw_transcript", "")
            if (json.optString("status", "") != "QUEUED" && immediateTranscript.isNotBlank()) {
                return@withContext SttResult.Success(immediateTranscript.trim())
            }

            pollForTranscript(jobId)
        } catch (e: Exception) {
            Log.e(TAG, "STT Proxy request failed", e)
            val msg = e.localizedMessage ?: e.message ?: "Network failure"
            return@withContext SttResult.Error(null, "Connection error: $msg")
        } finally {
            connection?.disconnect()
        }
    }

    /** Polls `stt_job_logs` until the background pipeline publishes a terminal status. */
    private suspend fun pollForTranscript(jobId: String): SttResult = withContext(Dispatchers.IO) {
        val pollUrl = "${SupabaseConfig.SUPABASE_URL}/rest/v1/stt_job_logs" +
            "?job_id=eq.$jobId&select=status,raw_transcript,error_message"

        var lastError = "STT job did not complete within ${POLL_TIMEOUT_MS / 1000}s"

        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(pollUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Authorization", "Bearer $anonKey")
                    setRequestProperty("apikey", anonKey)
                }
                if (conn.responseCode !in 200..299) {
                    lastError = "Poll failed with HTTP ${conn.responseCode}"
                    continue
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(body)
                if (arr.length() == 0) continue

                val row = arr.getJSONObject(0)
                when (val status = row.optString("status", "QUEUED")) {
                    "QUEUED" -> continue
                    "ERROR" -> {
                        val msg = row.optString("error_message", "Server reported ERROR")
                        return@withContext SttResult.Error(null, msg.ifBlank { "Server reported ERROR" })
                    }
                    else -> {
                        val transcript = row.optString("raw_transcript", "")
                        return@withContext if (transcript.isNotBlank()) {
                            SttResult.Success(transcript.trim())
                        } else {
                            val msg = row.optString("error_message", "")
                            SttResult.Error(null, msg.ifBlank { "STT returned an empty transcript (status $status)" })
                        }
                    }
                }
            } catch (e: Exception) {
                lastError = "Poll exception: ${e.message}"
            } finally {
                conn?.disconnect()
            }
        }
        SttResult.Error(null, lastError)
    }

    companion object {
        private const val TAG = "SttProxyClient"
        private const val POLL_INTERVAL_MS = 2000L
        private const val POLL_TIMEOUT_MS = 30000L
    }
}
