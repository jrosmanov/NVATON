package az.nvaton.komekci.a11y

import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import az.nvaton.komekci.core.tr

/**
 * "Əli, ekranı oxu": the visible text of the app the user was looking at.
 *
 * The app's own screen covers that app as soon as the name is heard, so the text is taken at that moment
 * ([capture], called by the wake word) and kept in memory only, for [MAX_AGE_MS]. Nothing is read in the
 * background otherwise, password fields are skipped, and the text leaves the phone only when they ask.
 */
object ScreenReader {
    data class Snapshot(val app: String, val text: String, val takenAt: Long)

    @Volatile
    internal var service: AgentAccessibilityService? = null

    @Volatile
    private var last: Snapshot? = null

    /** Needs the accessibility service, the same switch as the WhatsApp «Göndər» button. */
    val available: Boolean get() = service != null

    /** Remembers what is on screen now, unless it is this app. Returns false when there is nothing to keep. */
    fun capture(): Boolean {
        val svc = service ?: return false
        return try {
            val root = foregroundRoot(svc) ?: return false
            val pkg = root.packageName?.toString() ?: return false
            val tree = Node.of(root)
            val text = (if (pkg in WHATSAPP) whatsAppChat(tree, pkg) else null) ?: collect(tree)
            if (text.isBlank()) return false
            last = Snapshot(label(svc.packageManager, pkg), text, SystemClock.elapsedRealtime())
            true
        } catch (e: Exception) {
            Log.w("ScreenReader", "Cannot read the screen", e)
            false
        }
    }

    fun recent(): Snapshot? = last?.takeIf { SystemClock.elapsedRealtime() - it.takenAt < MAX_AGE_MS }

    fun forget() {
        last = null
    }

    private fun foregroundRoot(svc: AgentAccessibilityService): AccessibilityNodeInfo? {
        val own = svc.packageName
        val apps = svc.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val window = apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused } ?: apps.firstOrNull()
        val root = window?.root ?: svc.rootInActiveWindow ?: return null
        return root.takeUnless { it.packageName?.toString() == own }
    }

    /** The visible part of the window, copied once so it can be searched without more calls to the app. */
    private class Node(val id: String, val text: String, val parent: Node?) {
        val children = ArrayList<Node>()

        fun all(): Sequence<Node> = sequence {
            yield(this@Node)
            children.forEach { yieldAll(it.all()) }
        }

        fun find(idSuffix: String): Node? = all().firstOrNull { it.id.endsWith(idSuffix) }

        companion object {
            fun of(root: AccessibilityNodeInfo): Node {
                var count = 0
                fun copy(info: AccessibilityNodeInfo, parent: Node?, depth: Int): Node? {
                    if (depth > 60 || count > 2000 || !info.isVisibleToUser) return null
                    count++
                    val text = if (info.isPassword) "" else (info.text ?: info.contentDescription)?.toString()?.trim().orEmpty()
                    val node = Node(info.viewIdResourceName.orEmpty(), text, parent)
                    for (i in 0 until info.childCount) info.getChild(i)?.let { child -> copy(child, node, depth + 1)?.let(node.children::add) }
                    return node
                }
                return copy(root, null, 0) ?: Node("", "", null)
            }
        }
    }

    private fun collect(tree: Node): String {
        val lines = LinkedHashSet<String>() // screen order, each line once
        tree.all().map { it.text }.filter { it.isNotEmpty() }.forEach { lines.add(it) }
        return lines.joinToString("\n").take(MAX_CHARS)
    }

    /**
     * An open WhatsApp chat as a transcript: who wrote each visible message, when, and whether it was the user.
     * "Söhbət: Murad / Murad (12:40): Salam baba / Siz (12:41): Salam". Null on other WhatsApp screens.
     */
    private fun whatsAppChat(tree: Node, pkg: String): String? {
        val messages = tree.all().filter { it.id == "$pkg:id/message_text" && it.text.isNotEmpty() }.toList()
        if (messages.isEmpty()) return null
        val title = tree.find(":id/conversation_contact_name")?.text?.ifBlank { null }
        val lines = messages.map { message ->
            // The message's row: the highest ancestor that still holds only this one message.
            var row = message
            var up = message.parent
            var depth = 0
            while (up != null && depth < 6 && up.all().count { it.id == "$pkg:id/message_text" } == 1) {
                row = up
                up = up.parent
                depth++
            }
            val mine = row.find(":id/status") != null // the ticks: sent by the user
            val sender = row.find(":id/name_in_group_tv")?.text?.ifBlank { null }
            val time = row.find(":id/date")?.text?.ifBlank { null }
            val who = when {
                mine -> tr("Siz", "You")
                sender != null -> sender
                else -> title ?: "?"
            }
            "$who${time?.let { " ($it)" }.orEmpty()}: ${message.text}"
        }
        return (listOf(tr("WhatsApp söhbəti: ", "WhatsApp chat: ") + (title ?: "?")) + lines).joinToString("\n").take(MAX_CHARS)
    }

    private fun label(pm: PackageManager, pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private const val MAX_CHARS = 6000
    private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
    private const val MAX_AGE_MS = 3 * 60_000L
}
