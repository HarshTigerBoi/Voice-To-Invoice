package com.voicetoinvoice.app.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class SttResult {
    data class Success(val transcript: String) : SttResult()
    data class Error(val code: Int?, val message: String) : SttResult()
}

class SttProxyClient(
    private val endpointUrl: String = SupabaseConfig.STT_PROXY_ENDPOINT,
    private val anonKey: String = SupabaseConfig.SUPABASE_ANON_KEY
) {

    /**
     * Sends recorded audio payload to Supabase Edge Function proxy (/functions/v1/stt-proxy).
     * The Edge Function securely attaches the SARVAM_API_KEY server-side before invoking Sarvam STT API.
     *
     * Returns SttResult.Success(transcript) or SttResult.Error(code, message).
     */
    suspend fun transcribeAudioProxy(audioFile: File, catalogContext: List<String> = emptyList()): SttResult = withContext(Dispatchers.IO) {
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

                // Part 1: model parameter
                writeString("$twoHyphens$boundary$lineEnd")
                writeString("Content-Disposition: form-data; name=\"model\"$lineEnd$lineEnd")
                writeString("saaras:v3$lineEnd")

                // Part 2: language_code parameter
                writeString("$twoHyphens$boundary$lineEnd")
                writeString("Content-Disposition: form-data; name=\"language_code\"$lineEnd$lineEnd")
                writeString("hi-IN$lineEnd")

                // Part 3: catalogNames parameter for keyterm biasing
                if (catalogContext.isNotEmpty()) {
                    val catalogJson = JSONArray(catalogContext).toString()
                    writeString("$twoHyphens$boundary$lineEnd")
                    writeString("Content-Disposition: form-data; name=\"catalogNames\"$lineEnd$lineEnd")
                    writeString("$catalogJson$lineEnd")
                }

                // Part 4: file parameter
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
            Log.d("SttProxyClient", "HTTP Response Code: $responseCode")

            val inputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseString = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            Log.d("SttProxyClient", "HTTP Response Body: $responseString")

            if (responseCode in 200..299 && responseString.isNotBlank()) {
                val json = JSONObject(responseString)
                val transcript = json.optString("transcript", "").ifBlank {
                    json.optString("text", "")
                }
                if (transcript.isNotBlank()) {
                    return@withContext SttResult.Success(transcript.trim())
                } else {
                    return@withContext SttResult.Error(responseCode, "STT returned empty transcript: $responseString")
                }
            } else {
                var errDetail = responseString
                try {
                    val json = JSONObject(responseString)
                    if (json.has("error")) {
                        val errObj = json.get("error")
                        errDetail = if (errObj is JSONObject) {
                            errObj.optString("message", responseString)
                        } else {
                            errObj.toString()
                        }
                    }
                } catch (_: Exception) {}

                Log.e("SttProxyClient", "STT proxy error HTTP $responseCode: $errDetail")
                return@withContext SttResult.Error(responseCode, "HTTP $responseCode: $errDetail")
            }
        } catch (e: Exception) {
            Log.e("SttProxyClient", "STT Proxy request failed", e)
            val msg = e.localizedMessage ?: e.message ?: "Network failure"
            return@withContext SttResult.Error(null, "Connection error: $msg")
        } finally {
            connection?.disconnect()
        }
    }
}
