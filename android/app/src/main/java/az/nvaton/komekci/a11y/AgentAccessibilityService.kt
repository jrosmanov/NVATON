package az.nvaton.komekci.a11y

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import az.nvaton.komekci.AppEvent
import az.nvaton.komekci.AppEvents
import az.nvaton.komekci.MainActivity
import az.nvaton.komekci.actions.Messenger

/** A message the user confirmed by voice and that may be sent within a short window. */
object PendingSend {
    data class Item(val contactName: String, val text: String, val expiresAt: Long)

    @Volatile
    private var item: Item? = null

    fun arm(contactName: String, text: String, ttlMs: Long = 20_000) {
        item = Item(contactName, text, SystemClock.elapsedRealtime() + ttlMs)
    }

    fun current(): Item? = item?.takeIf { SystemClock.elapsedRealtime() < it.expiresAt }

    fun clear() {
        item = null
    }
}

/**
 * Presses WhatsApp's "Send" button for a confirmed message, and opens chats for [WhatsAppDriver]. Send only when:
 *  - the user confirmed this exact message by voice in the last 20 seconds ([PendingSend]);
 *  - the event comes from WhatsApp;
 *  - WhatsApp's input box contains exactly the confirmed text.
 * It also lets [ScreenReader] read the screen aloud when the user asks; it stores nothing otherwise.
 */
class AgentAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in Messenger.WHATSAPP_PACKAGES) return
        if (WhatsAppDriver.busy) rootInActiveWindow?.let { WhatsAppDriver.onEvent(this, it) }
        val pending = PendingSend.current() ?: return
        val root = rootInActiveWindow ?: return

        val entry = findById(root, pkg, "entry")
        if (entry != null && entry.text?.toString()?.trim() != pending.text.trim()) return

        val send = findById(root, pkg, "send") ?: findByDescription(root, SEND_LABELS, 0) ?: return
        val target = clickableSelfOrAncestor(send) ?: return
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            PendingSend.clear()
            AppEvents.emit(AppEvent.MessageSent(pending.contactName))
            handler.postDelayed({ returnToApp() }, 900)
        }
    }

    override fun onServiceConnected() {
        ScreenReader.service = this
    }

    override fun onDestroy() {
        ScreenReader.service = null
        ScreenReader.forget()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun findById(root: AccessibilityNodeInfo, pkg: String, id: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull { it.isVisibleToUser }

    private fun findByDescription(node: AccessibilityNodeInfo, labels: Set<String>, depth: Int): AccessibilityNodeInfo? {
        if (depth > 40) return null
        val description = node.contentDescription?.toString()?.lowercase()
        if (description != null && description in labels && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByDescription(child, labels, depth + 1)?.let { return it }
        }
        return null
    }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val n = current ?: return null
            if (n.isClickable) return n
            current = n.parent
        }
        return null
    }

    private fun returnToApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    companion object {
        private val SEND_LABELS = setOf("send", "göndər", "gönder", "отправить")

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            val me = ComponentName(context, AgentAccessibilityService::class.java)
            return enabled.split(':').any {
                it.equals(me.flattenToString(), ignoreCase = true) || it.equals(me.flattenToShortString(), ignoreCase = true)
            }
        }
    }
}
