package com.voicetoinvoice.app.domain.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.voicetoinvoice.app.network.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class SpeechOutput(
    private val context: Context,
    private val rollingAudioBuffer: com.voicetoinvoice.app.audio.RollingAudioBuffer? = null
) : TextToSpeech.OnInitListener {

    private var androidTts: TextToSpeech? = null
    private var isTtsReady = false
    private var activeMediaPlayer: MediaPlayer? = null

    init {
        androidTts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = androidTts?.setLanguage(Locale("hi", "IN"))
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
            }
        }
    }

    /** @param preferOffline Skip the Grok TTS network round trip (measured 1.3-2.4s in
     *   production edge logs) entirely and speak with the on-device engine. Used by the
     *   assistant's fast path (§3.3), where the whole point is answering before the
     *   shopkeeper has time to wonder if the press registered -- everything else keeps
     *   the higher-quality Grok voice as the default. */
    suspend fun speak(text: String, preferOffline: Boolean = false, onComplete: (() -> Unit)? = null) =
        withContext(Dispatchers.IO + NonCancellable) {
            stop()
            rollingAudioBuffer?.setSuppressed(true)
            try {
                if (!preferOffline) {
                    // 1. Try Grok TTS via Edge Proxy
                    val audioFile = fetchGrokTtsAudio(text)
                    if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
                        playAudioFile(audioFile)
                        onComplete?.invoke()
                        return@withContext
                    }
                }

                // 2. Android System TextToSpeech -- either the preferred path (preferOffline)
                //    or the fallback when Grok TTS failed/was skipped.
                speakOffline(text, onComplete)
            } finally {
                rollingAudioBuffer?.setSuppressed(false)
            }
        }

    /** Speaks via the on-device engine and calls [onComplete] when speech actually
     *  finishes, via UtteranceProgressListener -- the previous version invoked
     *  onComplete immediately after the asynchronous androidTts.speak() call returned,
     *  which fires while the phone is still talking. Any caller that reopens the mic in
     *  onComplete (e.g. a future multi-turn clarifier) would otherwise start listening
     *  while its own answer is still playing, which is a second way the assistant could
     *  hear itself -- see Docs/assistant_speed_and_pipeline_fix_plan.md §3.3. */
    private suspend fun speakOffline(text: String, onComplete: (() -> Unit)?) {
        val tts = androidTts
        if (!isTtsReady || tts == null) {
            withContext(Dispatchers.Main) { onComplete?.invoke() }
            return
        }

        val utteranceId = "voice_output_${System.currentTimeMillis()}"
        val fired = AtomicBoolean(false)

        // Bounded wait -- if some OEM TTS engine never calls back (observed to happen
        // on a handful of devices industry-wide), this must not hang the assistant
        // forever instead of just skipping the "wait for speech to finish" guarantee.
        kotlinx.coroutines.withTimeoutOrNull(15000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(finishedId: String?) {
                        if (finishedId == utteranceId && fired.compareAndSet(false, true) && cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                    @Deprecated("Deprecated in API")
                    override fun onError(errorId: String?) {
                        if (errorId == utteranceId && fired.compareAndSet(false, true) && cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                    override fun onError(errorId: String?, errorCode: Int) {
                        if (errorId == utteranceId && fired.compareAndSet(false, true) && cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                })
                val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (result != TextToSpeech.SUCCESS && fired.compareAndSet(false, true) && cont.isActive) {
                    cont.resumeWith(Result.success(Unit))
                }
            }
        }

        onComplete?.invoke()
    }

    fun stop() {
        try {
            activeMediaPlayer?.stop()
            activeMediaPlayer?.release()
            activeMediaPlayer = null
        } catch (e: Exception) {
            Log.e("SpeechOutput", "Error stopping MediaPlayer", e)
        }
        try {
            if (androidTts?.isSpeaking == true) {
                androidTts?.stop()
            }
        } catch (e: Exception) {
            Log.e("SpeechOutput", "Error stopping Android TTS", e)
        }
    }

    private suspend fun fetchGrokTtsAudio(text: String): File? = withContext(Dispatchers.IO) {
        try {
            val url = URL("${SupabaseConfig.SUPABASE_URL}/functions/v1/tts-proxy")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
            conn.doOutput = true

            val jsonBody = JSONObject().apply {
                put("text", text)
                put("language", "hi")
            }

            conn.outputStream.write(jsonBody.toString().toByteArray())
            if (conn.responseCode == 200) {
                val tempFile = File.createTempFile("tts_speech_", ".mp3", context.cacheDir)
                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                return@withContext tempFile
            } else {
                Log.w("SpeechOutput", "Grok TTS proxy returned error code ${conn.responseCode}")
            }
        } catch (e: Exception) {
            Log.w("SpeechOutput", "Grok TTS network call failed, falling back to Android TTS: ${e.message}")
        }
        return@withContext null
    }

    private suspend fun playAudioFile(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    it.release()
                    activeMediaPlayer = null
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                }
                setOnErrorListener { _, _, _ ->
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    true
                }
                start()
            }
            activeMediaPlayer = mp
        } catch (e: Exception) {
            Log.e("SpeechOutput", "MediaPlayer play failed", e)
            if (cont.isActive) cont.resumeWith(Result.success(Unit))
        }
    }

    fun shutdown() {
        stop()
        androidTts?.shutdown()
        androidTts = null
    }
}
