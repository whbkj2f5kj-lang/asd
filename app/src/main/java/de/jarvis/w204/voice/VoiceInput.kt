package de.jarvis.w204.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Push-to-talk über die Android-Spracherkennung (Deutsch, offline bevorzugt). */
class VoiceInput(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onFail: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            _listening.value = false
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                onFail("Ich habe nichts verstanden.")
            } else {
                onFail("Spracherkennung gerade nicht verfügbar (Fehler $error).")
            }
        }

        override fun onResults(results: Bundle?) {
            _listening.value = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) onFail("Ich habe nichts verstanden.") else onText(text)
        }
    }

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFail("Spracherkennung ist auf diesem Radio nicht installiert.")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onFail("Bitte erlaube den Mikrofonzugriff.")
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        if (_listening.value) {
            r.stopListening()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        _listening.value = true
        r.startListening(intent)
    }

    fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
