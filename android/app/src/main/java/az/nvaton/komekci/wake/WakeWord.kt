package az.nvaton.komekci.wake

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** The user said the assistant's name. [audio]: WAV of the whole utterance when they went straight on to a command. */
class WakeEvent(val id: Long, val audio: ByteArray?)

enum class WakeState { OFF, LOADING, LISTENING, PAUSED, NO_MODEL, ERROR }

/**
 * Process-wide link between [WakeWordService] and the UI.
 *
 * The service listens only while nobody holds it: the app takes a hold while it speaks (so its own
 * voice saying "Əli" can't wake it), while it records the user, and while it is busy, and gives
 * the microphone back afterwards.
 */
object WakeWord {
    const val HOLD_TTS = "tts"
    const val HOLD_LISTEN = "listen"
    const val HOLD_BUSY = "busy"
    const val HOLD_HANDOFF = "handoff"
    /** A voice message is being recorded: the recorder owns the microphone and announcements wait. */
    const val HOLD_VOICE_NOTE = "voice_note"

    private val _holds = MutableStateFlow(emptySet<String>())
    val holds: StateFlow<Set<String>> = _holds.asStateFlow()

    fun hold(reason: String) = _holds.update { it + reason }

    fun release(reason: String) = _holds.update { it - reason }

    val state = MutableStateFlow(WakeState.OFF)

    /** True while the service has the microphone open. */
    val micOpen = MutableStateFlow(false)

    /** Waits (briefly) until the service has closed the microphone, so the app's own recorder gets it. */
    suspend fun awaitMicFree(timeoutMs: Long = 600) {
        withTimeoutOrNull(timeoutMs) { micOpen.first { !it } }
    }

    /** Whether MainActivity is on screen; when it isn't, the service brings it up on a wake. */
    @Volatile
    var uiVisible = false

    private val ids = AtomicLong()
    private val _pending = MutableStateFlow<WakeEvent?>(null)
    val pending: StateFlow<WakeEvent?> = _pending.asStateFlow()

    /** Called by the service. The handoff hold keeps it quiet until the UI takes the event. */
    fun post(audio: ByteArray?): WakeEvent {
        hold(HOLD_HANDOFF)
        return WakeEvent(ids.incrementAndGet(), audio).also { _pending.value = it }
    }

    /** The UI takes each event exactly once. */
    fun take(event: WakeEvent): Boolean {
        val taken = _pending.compareAndSet(event, null)
        if (taken) release(HOLD_HANDOFF)
        return taken
    }

    /** Nobody took the event in time (the UI could not be shown): forget it and listen again. */
    fun drop() {
        _pending.value = null
        release(HOLD_HANDOFF)
    }

    /** Debug builds: 100 ms frames the service hears instead of the microphone, to test without speaking. */
    val injected = ConcurrentLinkedQueue<ShortArray>()

    /** Queues a 16 kHz mono 16-bit WAV (44-byte header), followed by a second of silence. */
    fun inject(wav: ByteArray) {
        val samples = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val all = ShortArray(samples.remaining()).also { samples.get(it) } + ShortArray(16_000)
        all.toList().chunked(1_600).forEach { injected.add(it.toShortArray()) }
    }
}
