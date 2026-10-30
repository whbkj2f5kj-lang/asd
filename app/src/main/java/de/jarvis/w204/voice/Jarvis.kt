package de.jarvis.w204.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Jarvis' Stimme: deutsche Sprachausgabe, Musik wird dabei leiser (wie bei Navi-Ansagen). */
class Jarvis(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    @Volatile var speakEnabled = true
    private val cooldowns = HashMap<String, Long>()
    @Volatile private var pending = 0

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = tts.setLanguage(Locale.GERMANY)
        ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { pending = (pending - 1).coerceAtLeast(0) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { pending = (pending - 1).coerceAtLeast(0) }
        })
        tts.setPitch(0.92f)
        tts.setSpeechRate(1.02f)
    }

    /** key + cooldownMs verhindern, dass dieselbe Warnung ständig wiederholt wird. */
    @Synchronized
    fun say(text: String, key: String? = null, cooldownMs: Long = 0L) {
        val now = SystemClock.elapsedRealtime()
        if (key != null) {
            val last = cooldowns[key]
            if (last != null && now - last < cooldownMs) return
            cooldowns[key] = now
        }
        _message.value = text
        if (!speakEnabled || !ready) return
        // Warnungen sind nur jetzt relevant: wenn schon zwei Ansagen warten, ältere verwerfen
        val mode = if (pending >= 2) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        pending = if (mode == TextToSpeech.QUEUE_FLUSH) 1 else pending + 1
        tts.speak(text, mode, null, key ?: "msg$now")
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
    }
}
