package az.nvaton.komekci.wake

import az.nvaton.komekci.core.tr
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import az.nvaton.komekci.BuildConfig
import az.nvaton.komekci.MainActivity
import az.nvaton.komekci.R
import az.nvaton.komekci.a11y.ScreenReader
import az.nvaton.komekci.core.WakePhrase
import az.nvaton.komekci.komekci
import az.nvaton.komekci.voice.Wav
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Waits for the assistant's name ("Əli"), like "Hey Siri", entirely on the phone: a small offline
 * model (Vosk) transcribes speech locally and [WakePhrase] checks whether it starts with the name.
 * No audio leaves the phone until the name is heard; then the UI takes over (and the microphone).
 *
 * A foreground service of the microphone type, started while the app is on screen: that is the only
 * way Android lets an app keep listening in the background. The green privacy dot stays on meanwhile.
 */
class WakeWordService : Service() {
    @Volatile
    private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TURN_OFF) {
            komekci.settings.wakeWord = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive != true) {
            running = true
            worker = Thread(::run, "wake-word").apply { start() }
        }
        // Not sticky: Android would not let a restarted service open the microphone from the background.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        markOff()
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot listen in the foreground", e)
        WakeWord.state.value = WakeState.ERROR
        false
    }

    private fun run() {
        WakeWord.state.value = WakeState.LOADING
        try {
            val model = WakeModel.load(this)
            if (model == null) {
                Log.w(TAG, "No wake-word model in assets; run scripts/get-wake-model.sh")
                WakeWord.state.value = WakeState.NO_MODEL
                stopSelf()
                return
            }
            Log.i(TAG, "Model loaded")
            listen(model)
        } catch (e: InterruptedException) {
            // stopping
        } catch (e: Throwable) {
            Log.e(TAG, "Wake word stopped", e)
            WakeWord.state.value = WakeState.ERROR
            stopSelf()
        } finally { // the model stays loaded: the voice-message recorder shares it
            WakeWord.micOpen.value = false
            if (!running) markOff()
        }
    }

    /** OFF, but keep a problem (no model, no microphone) visible in Settings. */
    private fun markOff() = WakeWord.state.update { if (it == WakeState.ERROR || it == WakeState.NO_MODEL) it else WakeState.OFF }

    private fun listen(model: Model) {
        val settings = komekci.settings
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        val frame = ShortArray(FRAME_SAMPLES)
        val preRoll = ArrayDeque<ShortArray>()
        val utterance = ByteArrayOutputStream()
        var recorder: AudioRecord? = null
        var active = false // decoding an utterance (the room is otherwise skipped to save battery)
        var quietFrames = 0
        var frames = 0
        var noise = 150.0
        var freeSince = 0L
        var postedAt = 0L
        var peak = 0.0
        var levelFrames = 0
        var silentFrames = 0
        var silentLimit = SILENT_FRAMES

        fun closeMic() {
            recorder?.let {
                runCatching { it.stop() }
                it.release()
            }
            recorder = null
            WakeWord.micOpen.value = false
        }

        fun resetUtterance() {
            recognizer.reset()
            active = false
            quietFrames = 0
            frames = 0
            utterance.reset()
            preRoll.clear()
        }

        fun feed(samples: ShortArray): Boolean {
            for (s in samples) {
                utterance.write(s.toInt() and 0xff)
                utterance.write((s.toInt() shr 8) and 0xff)
            }
            frames++
            return recognizer.acceptWaveForm(samples, samples.size)
        }

        fun finish(resultJson: String) {
            val text = runCatching { JSONObject(resultJson).optString("text") }.getOrDefault("")
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Heard: \"$text\"")
                // For tuning: adb exec-out run-as az.nvaton.komekci cat cache/wake-last.wav > wake-last.wav
                runCatching { java.io.File(cacheDir, "wake-last.wav").writeBytes(Wav.fromPcm16(utterance.toByteArray(), SAMPLE_RATE)) }
            }
            val wordsAfterName = if (text.isBlank()) null else WakePhrase.match(text, settings.assistantName)
            if (wordsAfterName != null) {
                Log.i(TAG, "Wake: \"$text\"")
                // "Əli, nəvəmə yaz…" in one breath: hand over the recording so the command isn't lost.
                val audio = if (wordsAfterName > 0) Wav.fromPcm16(utterance.toByteArray(), SAMPLE_RATE) else null
                closeMic()
                if (!WakeWord.uiVisible) ScreenReader.capture() // for "Əli, ekranı oxu": our UI is about to cover it
                WakeWord.post(audio)
                postedAt = SystemClock.elapsedRealtime()
                bringUpUi()
            }
            resetUtterance()
        }

        try {
            while (running) {
                val now = SystemClock.elapsedRealtime()
                if (WakeWord.pending.value != null && now - postedAt > HANDOFF_TIMEOUT_MS) WakeWord.drop()
                if (WakeWord.holds.value.isNotEmpty() || !settings.wakeWord || !hasMicPermission(this)) {
                    if (recorder != null) {
                        closeMic()
                        resetUtterance()
                    }
                    freeSince = 0
                    WakeWord.state.value = WakeState.PAUSED
                    Thread.sleep(100)
                    continue
                }
                var mic = recorder
                if (mic == null) {
                    // Wait until the app has been quiet for a moment: it often listens right after speaking.
                    if (freeSince == 0L) freeSince = now
                    if (now - freeSince < RESUME_DELAY_MS) {
                        Thread.sleep(50)
                        continue
                    }
                    mic = openMic()
                    if (mic == null) {
                        WakeWord.state.value = WakeState.ERROR
                        Thread.sleep(2_000)
                        continue
                    }
                    recorder = mic
                    WakeWord.micOpen.value = true
                    WakeWord.state.value = WakeState.LISTENING
                }
                val n = mic.read(frame, 0, frame.size)
                if (n <= 0) {
                    closeMic()
                    Thread.sleep(200)
                    continue
                }
                val injected = if (BuildConfig.DEBUG) WakeWord.injected.poll() else null
                val chunk = injected ?: frame.copyOf(n)
                if (injected == null) {
                    // A live microphone always picks up some noise; exact zeros mean the stream has died. Seen on the
                    // emulator a minute or two after opening it, and on phones while another app holds the microphone.
                    // A fresh AudioRecord brings the sound back; back off in case it doesn't. Zeros are skipped so the
                    // noise estimate keeps matching the room.
                    if (chunk.all { it.toInt() == 0 }) {
                        if (++silentFrames >= silentLimit) {
                            Log.w(TAG, "Microphone gives only silence (${silentFrames / 10} s); reopening it")
                            closeMic()
                            resetUtterance()
                            silentFrames = 0
                            silentLimit = min(silentLimit * 2, MAX_SILENT_FRAMES)
                        }
                        continue
                    }
                    silentFrames = 0
                    silentLimit = SILENT_FRAMES
                }
                val level = rms(chunk)
                val loud = level > max(MIN_SPEECH_RMS, noise * 2.5)
                noise = if (loud) noise * 0.999 + level * 0.001 else noise * 0.95 + level * 0.05
                if (BuildConfig.DEBUG) {
                    peak = max(peak, level)
                    if (++levelFrames >= 50) { // every 5 s: is any sound arriving at all?
                        Log.d(TAG, "Mic level: peak %.0f, noise %.0f".format(peak, noise))
                        peak = 0.0
                        levelFrames = 0
                    }
                }
                if (!active) {
                    preRoll.addLast(chunk)
                    if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    if (!loud) continue
                    // Speech started: decode it from a little before the onset.
                    active = true
                    var ended = false
                    for (f in preRoll) ended = feed(f) || ended
                    preRoll.clear()
                    if (ended) finish(recognizer.result)
                    continue
                }
                val ended = feed(chunk)
                quietFrames = if (loud) 0 else quietFrames + 1
                when {
                    ended -> finish(recognizer.result)
                    quietFrames >= MAX_QUIET_FRAMES || frames >= MAX_UTTERANCE_FRAMES -> finish(recognizer.finalResult)
                }
            }
        } finally {
            closeMic()
            recognizer.close()
        }
    }

    @SuppressLint("MissingPermission") // checked by the caller on every loop
    private fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(min, SAMPLE_RATE * 2), // one second, so a slow decode never drops audio
            )
        } catch (e: Exception) {
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return try {
            record.startRecording()
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record
            } else {
                record.release()
                null
            }
        } catch (e: Exception) {
            record.release()
            null
        }
    }

    private fun rms(samples: ShortArray): Double {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size)
    }

    private fun bringUpUi() {
        if (WakeWord.uiVisible) return
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_WAKE, true)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot bring up the app", e)
        }
    }

    private fun buildNotification(): Notification {
        val name = komekci.settings.assistantName
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val turnOff = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_TURN_OFF),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(tr("«$name» deyin, sizi eşidirəm", "Say «$name», I'm listening"))
            .setContentText(tr("Ad telefonun içində tanınır. Səs serverə yalnız addan sonra gedir.", "The name is recognised on the phone. Audio goes to the server only after the name."))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, tr("Söndür", "Turn off"), turnOff)
            .build()
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_TURN_OFF = "az.nvaton.komekci.wake.TURN_OFF"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = SAMPLE_RATE / 10 // 100 ms
        private const val PRE_ROLL_FRAMES = 5 // 0.5 s before the speech onset
        private const val MAX_QUIET_FRAMES = 15 // 1.5 s of quiet ends an utterance
        private const val MAX_UTTERANCE_FRAMES = 120 // 12 s
        private const val MIN_SPEECH_RMS = 200.0
        private const val RESUME_DELAY_MS = 700L
        private const val HANDOFF_TIMEOUT_MS = 8_000L
        private const val SILENT_FRAMES = 20 // 2 s of digital silence: reopen the microphone
        private const val MAX_SILENT_FRAMES = 300 // retry at most every 30 s while it stays silent

        /** Starts listening if the user wants it and the microphone is allowed. Call while the app is on screen. */
        fun start(context: Context) {
            if (!context.komekci.settings.wakeWord || !hasMicPermission(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start the wake-word service", e)
                WakeWord.state.value = WakeState.ERROR
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        private fun hasMicPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        private fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, tr("Adı gözləmək", "Waiting for the name"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr("Köməkçinin adını deyəndə işə düşməsi üçün", "So the assistant starts when you say its name")
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
