package az.nvaton.komekci.core

/** An incoming message or notice, as the notification listener saw it. */
data class Notice(
    val app: String,
    val sender: String?,
    val text: String,
    /** Group chat name, when the message came in a group. */
    val conversation: String? = null,
    val kind: Kind = Kind.MESSAGE,
    val postedAt: Long = 0,
) {
    enum class Kind { MESSAGE, MISSED_CALL, OTHER }
}

/** How the assistant reads notifications aloud. Pure Kotlin, so it is unit-tested. */
object Notices {
    private const val MAX_TEXT = 220

    /** "Yeni mesaj, WhatsApp. Murad: Salam baba." / "New WhatsApp message from Murad: Hi grandpa." */
    fun announcement(n: Notice): String {
        val text = shorten(n.text)
        val who = n.sender?.takeIf { it.isNotBlank() }
        return when (n.kind) {
            Notice.Kind.MISSED_CALL -> tr("Buraxılmış zəng: ${who ?: n.text}.", "Missed call from ${who ?: n.text}.")
            Notice.Kind.OTHER -> tr(
                "Bildiriş, ${n.app}. ${who?.let { "$it. " }.orEmpty()}$text",
                "Notification from ${n.app}. ${who?.let { "$it. " }.orEmpty()}$text",
            )
            Notice.Kind.MESSAGE -> {
                val group = n.conversation?.takeIf { it.isNotBlank() && it != who }
                if (I18n.english) {
                    val from = listOfNotNull(group?.let { "in «$it»" }, who?.let { "from $it" }).joinToString(" ")
                    "New ${n.app} message${if (from.isEmpty()) "" else " $from"}: $text"
                } else {
                    val place = group?.let { ", «$it» qrupu" }.orEmpty()
                    "Yeni mesaj, ${n.app}$place. ${who?.let { "$it: " }.orEmpty()}$text"
                }
            }
        }
    }

    /** "Murad, WhatsApp, 5 dəqiqə əvvəl: Salam baba." for «mesajlarımı oxu». */
    fun summary(list: List<Notice>, now: Long): String {
        if (list.isEmpty()) return tr("Yeni mesaj yoxdur.", "You have no new messages.")
        val head = when (list.size) {
            1 -> tr("Bir yeni mesaj.", "One new message.")
            else -> tr("${list.size} yeni mesaj.", "${list.size} new messages.")
        }
        val lines = list.joinToString(" ") { n ->
            val who = listOfNotNull(n.sender?.takeIf { it.isNotBlank() }, n.conversation?.takeIf { it.isNotBlank() && it != n.sender })
                .joinToString(", ").ifBlank { n.app }
            val text = shorten(n.text)
            "$who, ${n.app}, ${ago(now - n.postedAt)}: $text${if (text.last() in ".!?…") "" else "."}"
        }
        return "$head $lines"
    }

    /** The same messages as plain text, for follow-up questions to the AI model. */
    fun asContext(list: List<Notice>): String =
        list.joinToString("\n") { n -> "${n.app}, ${n.sender ?: "?"}${n.conversation?.let { " ($it)" }.orEmpty()}: ${n.text}" }

    private fun ago(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> tr("indicə", "just now")
            minutes < 60 -> tr("$minutes dəqiqə əvvəl", "$minutes minutes ago")
            else -> tr("${minutes / 60} saat əvvəl", "${minutes / 60} hours ago")
        }
    }

    private fun shorten(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= MAX_TEXT) clean else clean.take(MAX_TEXT).substringBeforeLast(' ') + "…"
    }
}
