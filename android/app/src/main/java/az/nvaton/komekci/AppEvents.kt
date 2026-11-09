package az.nvaton.komekci

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

sealed interface AppEvent {
    data class MessageSent(val contactName: String) : AppEvent
    /** WhatsApp search found no chat with this name in time. */
    data class ChatNotFound(val name: String) : AppEvent
    data class ChatOpened(val name: String) : AppEvent
}

/** Process-wide events from the background services (accessibility, notification listener) to the UI. */
object AppEvents {
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AppEvent> = _events

    fun emit(event: AppEvent) {
        _events.tryEmit(event)
    }
}
