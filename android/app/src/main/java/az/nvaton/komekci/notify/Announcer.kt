package az.nvaton.komekci.notify

import android.content.Context
import android.media.AudioManager
import android.util.Log
import az.nvaton.komekci.BuildConfig
import az.nvaton.komekci.core.Notice
import az.nvaton.komekci.komekci
import az.nvaton.komekci.wake.WakeWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads incoming notifications aloud, one after another, without talking over the assistant, the user or a
 * phone call. Also keeps the latest messages in memory (never on disk) for «mesajlarımı oxu».
 */
object Announcer {
    private const val KEEP_MS = 3 * 60 * 60_000L
    private const val MAX_KEPT = 30
    private const val MAX_QUEUED = 5
    private const val WAIT_FOR_QUIET_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recent = ArrayDeque<Notice>()
    private val queue = ArrayDeque<String>()
    private var worker: Job? = null
    private var readUpTo = 0L

    @Synchronized
    fun remember(notice: Notice) {
        recent.addLast(notice)
        while (recent.size > MAX_KEPT) recent.removeFirst()
    }

    /** Messages not yet read out by «mesajlarımı oxu» (the latest five), or else the last few. */
    @Synchronized
    fun unread(now: Long): List<Notice> {
        recent.removeAll { now - it.postedAt > KEEP_MS }
        return recent.filter { it.postedAt > readUpTo }.takeLast(5)
    }

    @Synchronized
    fun markRead(now: Long) {
        readUpTo = now
    }

    /** «Dayan»: forget what is queued and stop the one being read. */
    fun clear() {
        queue.clear()
        worker?.cancel()
        worker = null
    }

    /** Queues [text] to be spoken as soon as nobody is talking. Call on the main thread. */
    fun announce(context: Context, text: String) {
        if (queue.size >= MAX_QUEUED) queue.removeFirst()
        queue.addLast(text)
        if (worker?.isActive == true) return
        val app = context.applicationContext
        worker = scope.launch {
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (!awaitQuiet(app)) continue // a call or a long conversation: skip rather than interrupt
                if (BuildConfig.DEBUG) Log.d("Announcer", "Speaking: $next")
                withTimeoutOrNull(60_000) {
                    suspendCancellableCoroutine { done -> app.komekci.speaker.speak(next) { if (done.isActive) done.resume(Unit) } }
                }
                delay(400)
            }
        }
    }

    private suspend fun awaitQuiet(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val until = System.currentTimeMillis() + WAIT_FOR_QUIET_MS
        while (System.currentTimeMillis() < until) {
            val inCall = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
                audio.mode == AudioManager.MODE_RINGTONE
            // Holds: the assistant is speaking, listening or thinking.
            if (!inCall && WakeWord.holds.value.isEmpty()) return true
            delay(500)
        }
        return false
    }
}
