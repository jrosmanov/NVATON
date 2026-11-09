package az.nvaton.komekci.voice

import az.nvaton.komekci.core.tr
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import az.nvaton.komekci.net.BackendApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Fallback speech recognition for phones whose recognizer has no Azerbaijani: records the
 * user until they stop talking, then sends a WAV to the backend (Gemini or Azure).
 * Same [SpeechInput.Listener] callbacks as [SpeechInput]. Call from the main thread.
 */
class ServerSpeechInput(private val api: BackendApi, private val listener: SpeechInput.Listener) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    var isListening = false
        private set

    @SuppressLint("MissingPermission") // callers only start listening once RECORD_AUDIO is granted
    fun start() {
        if (isListening) return
        isListening = true
        stopRequested = false
        listener.onListeningStarted()
        job = scope.launch {
            val pcm = withContext(Dispatchers.IO) { record() }
            isListening = false
            listener.onListeningStopped()
            when {
                pcm == null -> listener.onSpeechError(tr("Mikrofonu açmaq alınmadı.", "I couldn't open the microphone."), languageUnsupported = false)
                pcm.isEmpty() -> listener.onSpeechError(tr("Səsinizi eşitmədim. Mikrofonu basıb yenidən danışın.", "I didn't hear you. Press the microphone and speak again."), languageUnsupported = false)
                else -> {
                    listener.onPartial("…")
                    val text = try {
                        api.stt(Wav.fromPcm16(pcm, SAMPLE_RATE))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    when {
                        text == null -> listener.onSpeechError(tr("Serverin nitq tanımasına qoşula bilmədim.", "I couldn't reach the server's speech recognition."), languageUnsupported = false)
                        text.isBlank() -> listener.onSpeechError(tr("Başa düşmədim, bir də deyin.", "I didn't catch that, please say it again."), languageUnsupported = false)
                        else -> listener.onResult(text)
                    }
                }
            }
        }
    }

    /** Stop recording now and recognise what was said so far. */
    fun stop() {
        stopRequested = true
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (isListening) {
            isListening = false
            listener.onListeningStopped()
        }
    }

    /**
     * Records until about 1.2 s of silence after speech (12 s at most). Returns an empty array
     * when no speech was heard, or null when the microphone could not be opened.
     */
    @SuppressLint("MissingPermission")
    private fun record(): ByteArray? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, SAMPLE_RATE),
            )
        } catch (e: Exception) {
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }
        val out = ByteArrayOutputStream()
        val frame = ShortArray(SAMPLE_RATE / 10) // 100 ms
        var heardSpeech = false
        var silentMs = 0
        var elapsedMs = 0
        var noiseFloor = 0.0
        try {
            recorder.startRecording()
            while (!stopRequested && elapsedMs < MAX_MS) {
                val n = recorder.read(frame, 0, frame.size)
                if (n <= 0) break
                val frameMs = n * 1000 / SAMPLE_RATE
                elapsedMs += frameMs
                for (i in 0 until n) {
                    val s = frame[i].toInt()
                    out.write(s and 0xff)
                    out.write((s shr 8) and 0xff)
                }
                val rms = rms(frame, n)
                if (elapsedMs <= CALIBRATION_MS) noiseFloor = maxOf(noiseFloor, rms)
                val speaking = elapsedMs > CALIBRATION_MS && rms > maxOf(SPEECH_RMS, noiseFloor * 2.5)
                if (speaking) {
                    heardSpeech = true
                    silentMs = 0
                } else if (heardSpeech) {
                    silentMs += frameMs
                }
                if (heardSpeech && silentMs >= END_SILENCE_MS) break
                if (!heardSpeech && elapsedMs >= START_TIMEOUT_MS) break
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        return if (heardSpeech) out.toByteArray() else ByteArray(0)
    }

    private fun rms(samples: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / count)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_MS = 12_000
        const val START_TIMEOUT_MS = 6_000
        const val END_SILENCE_MS = 1_200
        const val CALIBRATION_MS = 300
        const val SPEECH_RMS = 600.0
    }
}

object Wav {
    /** Wraps 16-bit little-endian PCM in a 44-byte RIFF/WAVE header. */
    fun fromPcm16(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM header size
            putShort(1) // PCM format
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * 2) // byte rate
            putShort((channels * 2).toShort()) // block align
            putShort(16) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
        }
        return header.array() + pcm
    }
}
