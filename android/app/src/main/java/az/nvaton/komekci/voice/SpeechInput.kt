package az.nvaton.komekci.voice

import az.nvaton.komekci.core.I18n
import az.nvaton.komekci.core.tr
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Wraps Android's SpeechRecognizer (Google, az-AZ). Must be used from the main thread. */
class SpeechInput(private val context: Context, private val listener: Listener) : RecognitionListener {

    interface Listener {
        fun onListeningStarted()
        fun onPartial(text: String)
        fun onResult(text: String)

        /** [languageUnsupported]: the phone can't recognise Azerbaijani, so the server fallback should take over. */
        fun onSpeechError(message: String, languageUnsupported: Boolean)
        fun onListeningStopped()
    }

    private var recognizer: SpeechRecognizer? = null
    var isListening = false
        private set

    fun start(language: String = I18n.lang.speechTag) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onSpeechError(
                tr("Bu telefonda nitq tanıma xidməti yoxdur. «Yazaraq əmr ver» düyməsindən istifadə edin.", "This phone has no speech recognition. Use «Type a command» instead."),
                languageUnsupported = true,
            )
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // English models are on the phone: no upload, no waiting for the network.
            if (language.startsWith("en")) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        isListening = true
        r.startListening(intent)
        listener.onListeningStarted()
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun cancel() {
        recognizer?.cancel()
        if (isListening) {
            isListening = false
            listener.onListeningStopped()
        }
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        isListening = false
    }

    override fun onResults(results: Bundle?) {
        if (!isListening) return
        isListening = false
        listener.onListeningStopped()
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (text.isBlank()) listener.onSpeechError(tr("Başa düşmədim, bir də deyin.", "I didn't catch that, please say it again."), languageUnsupported = false) else listener.onResult(text)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(listener::onPartial)
    }

    override fun onError(error: Int) {
        if (!isListening) return // late errors after results or cancel()
        isListening = false
        listener.onListeningStopped()
        // 12 / 13 = ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31+)
        listener.onSpeechError(messageFor(error), languageUnsupported = error == 12 || error == 13)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> tr("Başa düşmədim, bir də deyin.", "I didn't catch that, please say it again.")
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> tr("Səsinizi eşitmədim. Mikrofonu basıb yenidən danışın.", "I didn't hear you. Press the microphone and speak again.")
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> tr("İnternet bağlantısı yoxdur.", "There is no internet connection.")
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> tr("Mikrofon icazəsi lazımdır.", "Microphone permission is needed.")
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> tr("Bir saniyə gözləyin və yenidən cəhd edin.", "Wait a second and try again.")
        // ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31+)
        12, 13 -> tr(
            "Bu telefonda Azərbaycan dili üçün nitq tanıma yoxdur. Serverə Gemini açarı qoşun və ya yazaraq əmr verin.",
            "This phone has no speech recognition for this language. Add a Gemini key to the server or type the command.",
        )
        else -> tr("Səsi tanımaq alınmadı (xəta $error).", "Speech recognition failed (error $error).")
    }
}
