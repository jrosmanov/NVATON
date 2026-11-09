package az.nvaton.komekci.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import az.nvaton.komekci.core.I18n
import az.nvaton.komekci.data.AppSettings
import az.nvaton.komekci.net.BackendApi
import az.nvaton.komekci.net.Health
import az.nvaton.komekci.wake.WakeWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Speaks Azerbaijani text, in order of preference:
 *  1. Azure's az-AZ neural voice through the backend (natural; needs AZURE_SPEECH_KEY on the server);
 *  2. an on-device Azerbaijani voice, if the phone has one;
 *  3. the on-device Turkish voice with a few letters adapted (ə→e, x→h, q→g), which Azerbaijani listeners understand.
 * When the user picks English in Settings, the phone's English voice speaks instead.
 * Call from the main thread.
 */
class Speaker(context: Context, private val settings: AppSettings, private val api: BackendApi) : TextToSpeech.OnInitListener {
    enum class Engine { NONE, AZERBAIJANI, TURKISH_APPROX, ENGLISH, DEFAULT }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private var initDone = false
    private var queuedBeforeInit: Pair<String, (() -> Unit)?>? = null
    private var counter = 0
    private var job: Job? = null
    private var player: MediaPlayer? = null

    /** Utterances the engine has begun to play, and when it last showed any sign of progress. */
    private val started: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var lastProgress = 0L

    /** Set once the engine has crashed: from then on use an online voice (see [restartEngine]). */
    private var preferNetworkVoice = false

    /** Whether the backend offers Azure TTS; null until the first health check. */
    @Volatile
    private var serverVoice: Boolean? = null

    var engine = Engine.NONE
        private set

    // Created last: onInit may arrive as soon as the engine binds. Replaced if the engine crashes.
    private var tts = TextToSpeech(appContext, this)

    override fun onInit(status: Int) {
        main.post {
            initDone = true
            if (status == TextToSpeech.SUCCESS) {
                selectVoice()
                tts.setSpeechRate(settings.speechRate) // a little slower for elderly listeners, adjustable by voice
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.let(started::add)
                        lastProgress = SystemClock.elapsedRealtime()
                    }

                    // Word by word while it speaks (API 26+): proof the engine is alive even when isSpeaking lies.
                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        lastProgress = SystemClock.elapsedRealtime()
                    }

                    override fun onDone(utteranceId: String?) = finished(utteranceId)

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finished(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
                })
            }
            queuedBeforeInit?.let { (text, onDone) ->
                queuedBeforeInit = null
                speakOnDevice(text, onDone)
            }
        }
    }

    /** "Speak slower" / "speak faster" changed the setting. */
    fun applyRate() {
        if (initDone) tts.setSpeechRate(settings.speechRate)
    }

    /** The user switched the app's language: pick the matching voice. */
    fun applyLanguage() {
        if (initDone && engine != Engine.NONE) selectVoice()
    }

    private fun selectVoice() {
        engine = when {
            I18n.english -> {
                tts.setLanguage(Locale.US)
                Engine.ENGLISH
            }
            tts.isLanguageAvailable(AZ) >= TextToSpeech.LANG_AVAILABLE -> {
                tts.setLanguage(AZ)
                Engine.AZERBAIJANI
            }
            tts.isLanguageAvailable(TR) >= TextToSpeech.LANG_AVAILABLE -> {
                tts.setLanguage(TR)
                Engine.TURKISH_APPROX
            }
            else -> Engine.DEFAULT
        }
        if (preferNetworkVoice) useNetworkVoice()
    }

    fun onServerHealth(health: Health?) {
        serverVoice = health?.tts
    }

    /**
     * Speaks [text], interrupting anything already playing. [onDone] runs on the main thread when it finishes.
     * The wake word is paused meanwhile, so the assistant saying its own name can't wake it up.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        stop()
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        WakeWord.hold(WakeWord.HOLD_TTS)
        val finished: () -> Unit = {
            onDone?.invoke() // may start listening, which takes its own hold first
            WakeWord.release(WakeWord.HOLD_TTS)
        }
        speakNow(text, finished)
    }

    private fun speakNow(text: String, onDone: () -> Unit) {
        if (settings.useServerVoice && serverVoice == true && !I18n.english) { // the server's Azure voice is Azerbaijani
            job = scope.launch {
                val audio = try {
                    api.tts(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (audio != null && audio.isNotEmpty()) play(audio, onDone) else speakOnDevice(text, onDone)
            }
        } else {
            speakOnDevice(text, onDone)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        callbacks.clear() // an interrupted sentence must not trigger "listen after speaking"
        queuedBeforeInit = null
        if (initDone) tts.stop()
        WakeWord.release(WakeWord.HOLD_TTS)
    }

    private fun speakOnDevice(text: String, onDone: (() -> Unit)?) {
        if (!initDone) {
            queuedBeforeInit = text to onDone
            return
        }
        if (engine == Engine.NONE) {
            onDone?.invoke()
            return
        }
        val spoken = if (engine == Engine.TURKISH_APPROX) adaptForTurkish(text) else text
        val id = "u${++counter}"
        onDone?.let { callbacks[id] = it }
        if (tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            finished(id)
            restartEngine() // the engine is gone (it crashed or was killed); bind a fresh one for next time
            return
        }
        watchEngine(id, spoken)
    }

    /**
     * If the engine dies mid-sentence it never reports back, and the app would stay "speaking" (with the wake word
     * paused) forever. But [TextToSpeech.isSpeaking] is unreliable on some phones (it says false while still
     * talking), and trusting it ended sentences early: the microphone opened while the answer was still being read.
     * So a sentence is never cut short before the time it needs; only an engine that never started, or that has been
     * silent well past the sentence's length, counts as dead.
     */
    private fun watchEngine(id: String, text: String) {
        val startedAt = SystemClock.elapsedRealtime()
        val needs = expectedMs(text)
        fun check() {
            if (!callbacks.containsKey(id)) return // finished or interrupted normally
            val now = SystemClock.elapsedRealtime()
            val speaking = runCatching { tts.isSpeaking }.getOrDefault(false)
            val dead = when {
                id !in started -> now - startedAt > NEVER_STARTED_MS && !speaking // crashed before a word (seen on emulators)
                now - startedAt < needs -> false
                else -> !speaking && now - lastProgress > 1_500
            }
            if (dead) {
                started.remove(id)
                finished(id)
                restartEngine()
            } else {
                main.postDelayed(::check, 1_000)
            }
        }
        main.postDelayed(::check, 1_000)
    }

    /** About how long [text] takes to say at our slightly slow rate, plus a margin. */
    private fun expectedMs(text: String): Long = (text.length * 1000L / CHARS_PER_SECOND) + 2_500

    private fun restartEngine() {
        // The usual cause is the on-device neural voice crashing (seen on Apple-silicon emulators: SIGILL in
        // Google TTS). The same language's online voice is synthesized on Google's servers and doesn't crash.
        preferNetworkVoice = true
        runCatching { tts.shutdown() }
        initDone = false
        engine = Engine.NONE
        tts = TextToSpeech(appContext, this)
    }

    private fun useNetworkVoice() {
        val language = tts.voice?.locale?.language ?: return
        val online = runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == language && it.isNetworkConnectionRequired }
            .maxByOrNull { it.quality }
            ?: return
        tts.voice = online
    }

    private fun play(bytes: ByteArray, onDone: (() -> Unit)?) {
        try {
            val file = File(appContext.cacheDir, "speech.mp3").apply { writeBytes(bytes) }
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { done(it, onDone) }
            mp.setOnErrorListener { p, _, _ ->
                done(p, onDone)
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            player?.release()
            player = null
            onDone?.invoke()
        }
    }

    private fun done(mp: MediaPlayer, onDone: (() -> Unit)?) {
        mp.release()
        if (player === mp) {
            player = null
            onDone?.invoke()
        }
    }

    private fun finished(utteranceId: String?) {
        utteranceId?.let(started::remove)
        main.post { utteranceId?.let { callbacks.remove(it) }?.invoke() }
    }

    companion object {
        private const val NEVER_STARTED_MS = 6_000L
        private const val CHARS_PER_SECOND = 11 // Azerbaijani at speech rate 0.9
        private val AZ: Locale = Locale.forLanguageTag("az-AZ")
        private val TR: Locale = Locale.forLanguageTag("tr-TR")

        fun adaptForTurkish(text: String): String = buildString(text.length) {
            for (ch in text) {
                append(
                    when (ch) {
                        'ə' -> 'e'
                        'Ə' -> 'E'
                        'x' -> 'h'
                        'X' -> 'H'
                        'q' -> 'g'
                        'Q' -> 'G'
                        else -> ch
                    },
                )
            }
        }
    }
}
