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
        if (speakEnabled && ready) tts.speak(text, TextToSpeech.QUEUE_ADD, null, key ?: "msg$now")
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
    }
}
