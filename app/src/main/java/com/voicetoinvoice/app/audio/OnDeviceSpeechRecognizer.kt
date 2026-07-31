package com.voicetoinvoice.app.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

data class OnDeviceResult(
    val transcript: String,
    val status: String
)

class OnDeviceSpeechRecognizer(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var resultDeferred: CompletableDeferred<OnDeviceResult>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isListening = false
    @Volatile private var latestPartialText: String = ""

    fun isAvailable(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: Exception) {
            false
        }
    }

    fun startListening(languageCode: String = "hi-IN") {
        if (!isAvailable()) {
            resultDeferred = CompletableDeferred(OnDeviceResult("", "unavailable"))
            return
        }

        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
                resultDeferred = CompletableDeferred()
                latestPartialText = ""
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        val statusStr = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "error_audio"
                            SpeechRecognizer.ERROR_CLIENT -> "error_client"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "error_permissions"
                            SpeechRecognizer.ERROR_NETWORK -> "error_network"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "error_network_timeout"
                            SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "error_busy"
                            SpeechRecognizer.ERROR_SERVER -> "error_server"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "error_speech_timeout"
                            else -> "error_$error"
                        }
                        Log.w("OnDeviceRecognizer", "SpeechRecognizer error: $error ($statusStr)")
                        isListening = false
                        
                        if (latestPartialText.isNotBlank()) {
                            resultDeferred?.complete(OnDeviceResult(latestPartialText, "partial_fallback"))
                        } else {
                            resultDeferred?.complete(OnDeviceResult("", statusStr))
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        isListening = false
                        if (text.isNotBlank()) {
                            resultDeferred?.complete(OnDeviceResult(text, "ok"))
                        } else if (latestPartialText.isNotBlank()) {
                            resultDeferred?.complete(OnDeviceResult(latestPartialText, "partial_fallback"))
                        } else {
                            resultDeferred?.complete(OnDeviceResult("", "no_match"))
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            latestPartialText = text.trim()
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // Use the on-device model when the device has one downloaded, instead
                    // of implicitly falling back to network STT -- see
                    // Docs/assistant_speed_and_pipeline_fix_plan.md §4.
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }

                recognizer.startListening(intent)
                isListening = true
            } catch (e: Exception) {
                Log.e("OnDeviceRecognizer", "Failed to start listening", e)
                isListening = false
                resultDeferred?.complete(OnDeviceResult("", "error_exception"))
            }
        }
    }

    fun finishListening() {
        mainHandler.post {
            try {
                if (isListening) {
                    speechRecognizer?.stopListening()
                }
            } catch (e: Exception) {
                Log.w("OnDeviceRecognizer", "Failed to stop listening cleanly", e)
            }
        }
    }

    suspend fun awaitResult(timeoutMs: Long = 4000L): OnDeviceResult {
        val deferred = resultDeferred ?: return OnDeviceResult("", "unavailable")
        return try {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            } ?: if (latestPartialText.isNotBlank()) {
                OnDeviceResult(latestPartialText, "partial_fallback")
            } else {
                OnDeviceResult("", "timeout")
            }
        } catch (e: Exception) {
            OnDeviceResult("", "error_exception")
        }
    }

    @Deprecated("Use awaitResult() instead", ReplaceWith("awaitResult(timeoutMs).transcript"))
    suspend fun awaitTranscript(timeoutMs: Long = 4000L): String {
        return awaitResult(timeoutMs).transcript
    }

    fun release() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
                isListening = false
            } catch (e: Exception) {
                Log.w("OnDeviceRecognizer", "Failed to release SpeechRecognizer", e)
            }
        }
    }
}
