package com.voicetoinvoice.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RollingBufferRestartTest {

    @get:Rule
    val recordAudioPermission: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private lateinit var context: Context
    private lateinit var buffer: RollingAudioBuffer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        buffer = RollingAudioBuffer(context)
    }

    @Test
    fun restartResetsWriteHeadWithCounter() {
        buffer.startRollingBuffer()
        Thread.sleep(2000L)
        val start1 = buffer.getRecordingStartedAtMs()
        buffer.stopRollingBuffer()

        buffer.startRollingBuffer()
        val start2 = buffer.getRecordingStartedAtMs()
        Thread.sleep(2000L)
        val end2 = System.currentTimeMillis()

        val outputFile = File(context.cacheDir, "test_restart_window.wav")
        val file = buffer.extractAudioWindow(start2, end2, outputFile)
        buffer.stopRollingBuffer()

        assertNotNull(file)
        assertTrue(file!!.exists())
        assertTrue(file.length() > 9600)
    }

    @Test
    fun suppressionDoesNotSurviveRestart() {
        buffer.startRollingBuffer()
        buffer.setSuppressed(true)
        buffer.stopRollingBuffer()

        buffer.startRollingBuffer()
        Thread.sleep(1000L)
        val start = buffer.getRecordingStartedAtMs()
        val end = System.currentTimeMillis()

        val outputFile = File(context.cacheDir, "test_suppress_restart.wav")
        val file = buffer.extractAudioWindow(start, end, outputFile)
        buffer.stopRollingBuffer()

        assertNotNull(file)
        assertTrue(file!!.length() > 0)
    }

    @Test
    fun stopReleasesMicBeforeReturning() {
        buffer.startRollingBuffer()
        Thread.sleep(500L)
        buffer.stopRollingBuffer()

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        val freshRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            Math.max(minBufferSize, 4096)
        )

        assertTrue(freshRecord.state == AudioRecord.STATE_INITIALIZED)
        freshRecord.startRecording()
        freshRecord.stop()
        freshRecord.release()
    }
}
