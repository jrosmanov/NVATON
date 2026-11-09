package az.nvaton.komekci.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import az.nvaton.komekci.AppEvent
import az.nvaton.komekci.AppEvents
import az.nvaton.komekci.actions.Messenger
import az.nvaton.komekci.core.Az

/**
 * Opens any WhatsApp chat by name, the way a person would: WhatsApp's search, type the name, tap the chat, then
 * type the confirmed message (the «Göndər» press is [PendingSend]'s job). Used for groups and for people who are
 * not in the phone book; phone contacts open directly by number instead. Gives up after [TIMEOUT_MS].
 */
object WhatsAppDriver {
    private enum class Step { HOME, SEARCH, PICK, CHAT }

    private class Job(val name: String, val text: String?, val pkg: String) {
        var step = Step.HOME
        var backs = 0
    }

    private const val TIMEOUT_MS = 20_000L
    private val SEARCH_IDS = listOf("menuitem_search", "my_search_bar", "search_bar", "search_icon", "search_input_layout")
    private val SEARCH_LABELS = setOf("search", "axtar", "axtarış", "поиск", "ara")
    private val RESULT_IDS = listOf("conversations_row_contact_name", "contactpicker_row_name", "search_row_name")

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var job: Job? = null

    val busy: Boolean get() = job != null

    /** Starts WhatsApp on its chat list and drives it to [name]'s chat. [text] null: only open the chat. */
    fun start(context: Context, name: String, text: String?): Boolean {
        val pkg = Messenger.WHATSAPP_PACKAGES.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null } ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        val started = Job(name, text, pkg)
        job = started
        main.removeCallbacksAndMessages(null)
        main.postDelayed({
            if (job === started) {
                job = null
                AppEvents.emit(AppEvent.ChatNotFound(name))
            }
        }, TIMEOUT_MS)
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Called by the accessibility service for every WhatsApp screen change. */
    fun onEvent(service: AccessibilityService, root: AccessibilityNodeInfo) {
        val j = job ?: return
        if (root.packageName?.toString() != j.pkg) return
        when (j.step) {
            Step.HOME -> {
                if (byId(root, j.pkg, "entry") != null) { // inside some other chat: back to the list first
                    if (j.backs++ < 4) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    return
                }
                val search = SEARCH_IDS.firstNotNullOfOrNull { byId(root, j.pkg, it) } ?: byLabel(root, SEARCH_LABELS) ?: return
                if (click(search)) j.step = Step.SEARCH
            }
            Step.SEARCH -> {
                val field = editText(root) ?: return
                if (setText(field, j.name)) j.step = Step.PICK
            }
            Step.PICK -> {
                val want = Az.fold(j.name)
                val row = RESULT_IDS.asSequence()
                    .flatMap { root.findAccessibilityNodeInfosByViewId("${j.pkg}:id/$it").asSequence() }
                    .filter { it.isVisibleToUser }
                    .firstOrNull { node -> Az.fold(node.text?.toString().orEmpty()).let { it == want || it.startsWith(want) } }
                    ?: return
                if (click(row)) j.step = Step.CHAT
            }
            Step.CHAT -> {
                val entry = byId(root, j.pkg, "entry") ?: return
                job = null
                main.removeCallbacksAndMessages(null)
                if (j.text == null) {
                    AppEvents.emit(AppEvent.ChatOpened(j.name))
                } else {
                    PendingSend.arm(j.name, j.text)
                    setText(entry, j.text) // the next event sees the text and presses Send
                }
            }
        }
    }

    private fun byId(root: AccessibilityNodeInfo, pkg: String, id: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull { it.isVisibleToUser }

    private fun byLabel(node: AccessibilityNodeInfo, labels: Set<String>, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 30) return null
        val label = node.contentDescription?.toString()?.trim()?.lowercase()
        if (label != null && label in labels && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { child -> byLabel(child, labels, depth + 1)?.let { return it } }
        return null
    }

    private fun editText(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (node.className?.toString() == "android.widget.EditText" && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { child -> editText(child, depth + 1)?.let { return it } }
        return null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val n = current ?: return false
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = n.parent
        }
        return false
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean =
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
}
