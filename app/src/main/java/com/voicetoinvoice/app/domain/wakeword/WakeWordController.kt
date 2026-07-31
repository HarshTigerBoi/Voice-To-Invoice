package com.voicetoinvoice.app.domain.wakeword

import android.content.Context
import android.util.Log

class WakeWordController(private val context: Context) {

    private var isEnabled = false

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        Log.i("WakeWordController", "Wake word detection ('बिल वाले') set to: $enabled")
    }

    fun isWakeWordDetected(pcmBuffer: ShortArray, readSize: Int): Boolean {
        if (!isEnabled || readSize < 160) return false

        // Two-stage battery gate (VAD Energy Check)
        var energySum = 0.0
        for (i in 0 until readSize) {
            energySum += Math.abs(pcmBuffer[i].toDouble())
        }
        val avgEnergy = energySum / readSize
        if (avgEnergy < 1500.0) {
            // Energy below speech threshold -> skip wake word model inference to conserve battery (Principle #10)
            return false
        }

        // Speech present -> check for wake word phonetics
        return false
    }
}
