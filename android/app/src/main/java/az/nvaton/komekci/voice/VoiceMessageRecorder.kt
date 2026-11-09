package az.nvaton.komekci.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import az.nvaton.komekci.core.WakePhrase
import az.nvaton.komekci.wake.WakeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records a WhatsApp voice message on the phone itself, so the assistant stays in control: one microphone stream
 * both saves the sound and listens for the stop word ("Mellon"). The user speaks; when they say the word (or press
 * Stop), recording ends, the word is trimmed off the end, and the sound is saved as an .m4a to send.
 *
 * Keeping it in the app (rather than letting WhatsApp record) is the only way the stop word can work: while WhatsApp
 * records, Android gives it the microphone and nothing else can hear anything.
 */
class VoiceMessageRecorder(private val context: Context) {
    interface Listener {
        /** Every 100 ms while recording: [level] 0..1 for a simple meter, [seconds] elapsed. */
        fun onProgress(level: Float, seconds: Int)

        /** Recording is done. [file] is the .m4a to send, or null if it failed or was too short. */
        fun onFinished(file: File?, stoppedByWord: Boolean)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var cancelled = false

    var isRecording = false
        private set

    /** Start recording. [listener]'s callbacks come on the main thread. */
    fun start(listener: Listener) {
        if (isRecording) return
        isRecording = true
        stopRequested = false
        cancelled = false
        job = scope.launch {
            val result = withContext(Dispatchers.IO) { record(listener) }
            isRecording = false
            if (cancelled) return@launch
            val pcm = result?.first
            val file = if (pcm == null || pcm.size < MIN_BYTES) null else withContext(Dispatchers.IO) { encode(pcm) }
            listener.onFinished(file, result?.second ?: false)
        }
    }

    /** The user pressed Stop: finish and keep the sound. */
    fun stop() {
        stopRequested = true
    }

    /** Throw the recording away (they cancelled, or the app was stopped). */
    fun cancel() {
        cancelled = true
        stopRequested = true
        job?.cancel()
        job = null
        isRecording = false
    }

    @SuppressLint("MissingPermission") // callers start recording only once RECORD_AUDIO is granted
    private fun record(listener: Listener): Pair<ByteArray, Boolean>? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, SAMPLE_RATE * 2),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open the microphone", e)
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }

        // The stop-word recogniser loads in the background; capture starts at once and feeds it once it is ready.
        var recognizer: Recognizer? = null
        val recognizerJob = scope.launch(Dispatchers.IO) {
            recognizer = runCatching { WakeModel.load(context)?.let { Recognizer(it, SAMPLE_RATE.toFloat()) } }.getOrNull()
        }

        val out = ByteArrayOutputStream()
        val frame = ShortArray(SAMPLE_RATE / 10) // 100 ms
        var elapsedMs = 0
        var stoppedByWord = false
        try {
            recorder.startRecording()
            while (!stopRequested && elapsedMs < MAX_MS) {
                val n = recorder.read(frame, 0, frame.size)
                if (n <= 0) break
                elapsedMs += n * 1000 / SAMPLE_RATE
                for (i in 0 until n) {
                    out.write(frame[i].toInt() and 0xff)
                    out.write((frame[i].toInt() shr 8) and 0xff)
                }
                val level = (rms(frame, n) / LEVEL_FULL).toFloat().coerceIn(0f, 1f)
                val seconds = elapsedMs / 1000
                scope.launch { if (!cancelled) listener.onProgress(level, seconds) }

                val r = recognizer
                if (r != null && elapsedMs > IGNORE_START_MS) { // skip the first moment (the assistant's own tail)
                    val ended = r.acceptWaveForm(frame, n)
                    val text = if (ended) textOf(r.result) else textOf(r.partialResult)
                    if (heardStopWord(text)) {
                        stoppedByWord = true
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recording error", e)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            recognizerJob.cancel()
            runCatching { recognizer?.close() }
        }
        if (cancelled) return null
        var pcm = out.toByteArray()
        if (stoppedByWord) pcm = pcm.copyOf(maxOf(0, pcm.size - TRIM_BYTES)) // drop where "Mellon" was said
        return pcm to stoppedByWord
    }

    private fun heardStopWord(text: String): Boolean {
        if (text.isBlank()) return false
        val words = text.split(Regex("\\s+")).map { WakePhrase.key(it) }.filter { it.isNotEmpty() }
        return words.any { w -> w in STOP_WORDS || (w.length >= 4 && w.startsWith("mel")) || (w.length >= 5 && w.startsWith("mil") && w.endsWith("n")) }
    }

    private fun textOf(json: String): String = runCatching {
        JSONObject(json).let { it.optString("partial").ifBlank { it.optString("text") } }
    }.getOrDefault("")

    /** PCM 16-bit mono → AAC in an .m4a, the format WhatsApp plays as an audio message. */
    private fun encode(pcm: ByteArray): File? {
        val out = File(context.cacheDir, "voice-message.m4a")
        out.delete()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var muxing = false
            var offset = 0
            var inputDone = false
            val bytesPerSecond = SAMPLE_RATE * 2
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!.also { it.clear() }
                        val chunk = min(buf.capacity(), pcm.size - offset)
                        val ptsUs = offset.toLong() * 1_000_000L / bytesPerSecond
                        if (chunk <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(pcm, offset, chunk)
                            codec.queueInputBuffer(inIndex, 0, chunk, ptsUs, 0)
                            offset += chunk
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                } else if (outIndex >= 0) {
                    val buf = codec.getOutputBuffer(outIndex)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return if (out.length() > 0) out else null
        } catch (e: Exception) {
            Log.e(TAG, "Encoding the voice message failed", e)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun rms(samples: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / count)
    }

    companion object {
        private const val TAG = "VoiceMessage"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_MS = 3 * 60_000 // stop well before memory is a worry
        private const val IGNORE_START_MS = 600 // don't match the stop word in the first moment
        private const val TRIM_BYTES = SAMPLE_RATE * 2 * 7 / 10 // ~0.7 s where "Mellon" was said
        private const val MIN_BYTES = SAMPLE_RATE * 2 * 4 / 10 // under ~0.4 s is too short to be a message
        private const val LEVEL_FULL = 6_000.0 // RMS that shows as a full meter
        private const val TIMEOUT_US = 10_000L

        /** How the Turkish model is likely to hear "Mellon"; folded by [WakePhrase.key]. */
        private val STOP_WORDS = setOf("melon", "mellon", "melun", "melin", "milan", "milon", "milana", "malin", "melan", "mellun")
    }
}
