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

class OnDeviceSpeechRecognizer(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var resultDeferred: CompletableDeferred<String>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isListening = false

    fun isAvailable(): Boolean {
        return try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (e: Exception) {
            false
        }
    }

    fun startListening(languageCode: String = "hi-IN") {
        if (!isAvailable()) return

        mainHandler.post {
            try {
                release()
                resultDeferred = CompletableDeferred()
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer = recognizer

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        Log.w("OnDeviceRecognizer", "SpeechRecognizer error: $error")
                        isListening = false
                        resultDeferred?.complete("")
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        isListening = false
                        resultDeferred?.complete(text)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        if (!text.isNullOrBlank() && resultDeferred?.isCompleted == false) {
                            // Keep partial in mind if needed
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                recognizer.startListening(intent)
                isListening = true
            } catch (e: Exception) {
                Log.e("OnDeviceRecognizer", "Failed to start listening", e)
                isListening = false
                resultDeferred?.complete("")
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

    suspend fun awaitTranscript(timeoutMs: Long = 4000L): String {
        val deferred = resultDeferred ?: return ""
        return try {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            } ?: ""
        } catch (e: Exception) {
            ""
        }
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
