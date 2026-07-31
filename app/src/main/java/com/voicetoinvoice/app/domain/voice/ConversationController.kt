package com.voicetoinvoice.app.domain.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

class ConversationController(
    private val context: Context,
    private val speechOutput: SpeechOutput
) {
    private var turnCount = 0
    private val MAX_TURNS = 2
    private var jobScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeTimeoutJob: Job? = null

    fun reset() {
        turnCount = 0
        activeTimeoutJob?.cancel()
        activeTimeoutJob = null
    }

    fun onBargeIn() {
        // Principle #2: Barge-in cuts TTS instantly
        speechOutput.stop()
        activeTimeoutJob?.cancel()
        activeTimeoutJob = null
    }

    fun askQuestion(
        questionText: String,
        onReopenMic: () -> Unit,
        onFallbackToVisual: () -> Unit
    ) {
        if (turnCount >= MAX_TURNS) {
            Log.i("ConversationController", "Max turns reached ($MAX_TURNS). Falling back to visual UI.")
            reset()
            onFallbackToVisual()
            return
        }

        turnCount++
        jobScope.launch {
            speechOutput.speak(questionText) {
                // Speech finished -> reopen mic and set 4s timeout
                onReopenMic()

                activeTimeoutJob?.cancel()
                activeTimeoutJob = jobScope.launch {
                    delay(4000L) // 4 second timeout guard
                    Log.i("ConversationController", "Clarification turn timed out after 4s.")
                    reset()
                    onFallbackToVisual()
                }
            }
        }
    }
}
