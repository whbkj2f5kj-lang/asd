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
    private val ctx = context.applicationContext
    @Volatile private var tts = TextToSpeech(ctx, this)
    @Volatile private var ready = false
    /** gewünschte Stimme (Name) und Sprach-Engine (Paket); null = Standard */
    @Volatile private var wantVoice: String? = null
    @Volatile private var engine: String? = null

    /** Eine auswählbare deutsche Stimme */
    data class VoiceOption(val name: String, val label: String, val online: Boolean, val quality: Int)

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> = _voices

    /** installierte Sprach-Engines (Paket -> Name) */
    fun engines(): List<Pair<String, String>> = runCatching { tts.engines.map { it.name to it.label } }.getOrDefault(emptyList())
    fun currentEngine(): String? = runCatching { engine ?: tts.defaultEngine }.getOrNull()

    /** Engine wechseln (z. B. Google-Sprachausgabe statt der eingebauten) */
    fun setEngine(pkg: String?) {
        if (pkg == engine) return
        engine = pkg
        ready = false
        runCatching { tts.stop(); tts.shutdown() }
        tts = if (pkg == null) TextToSpeech(ctx, this) else TextToSpeech(ctx, this, pkg)
    }

    fun setVoice(name: String?) {
        wantVoice = name
        if (ready) applyVoice()
    }

    private fun applyVoice() {
        val list = runCatching { tts.voices?.toList() }.getOrNull().orEmpty()
            .filter { it.locale.language == "de" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy<android.speech.tts.Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))
        _voices.value = list.mapIndexed { i, v ->
            val q = when {
                v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "sehr gut"
                v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "gut"
                else -> "einfach"
            }
            VoiceOption(v.name, "Stimme ${i + 1} · $q" + (if (v.isNetworkConnectionRequired) " · braucht Internet" else ""), v.isNetworkConnectionRequired, v.quality)
        }
        val v = list.firstOrNull { it.name == wantVoice }
        if (v != null) runCatching { tts.voice = v }
    }

    /** Probe: kurzer Satz mit der gewählten Stimme */
    fun preview() {
        if (!ready) return
        tts.speak("Guten Tag. Öltemperatur 85 Grad, alles in Ordnung.", TextToSpeech.QUEUE_FLUSH, null, "preview")
    }
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
        // natürliche Stimmen klingen unverändert am besten
        tts.setPitch(1.0f)
        tts.setSpeechRate(1.0f)
        applyVoice()
    }

    /** Jede tatsächlich ausgegebene Meldung (Text, Schlüssel) – für die Warnkarten im Cockpit */
    @Volatile var onSaid: ((String, String?) -> Unit)? = null

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
        runCatching { onSaid?.invoke(text, key) }
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
