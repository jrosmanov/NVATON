package az.nvaton.komekci.notify

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import az.nvaton.komekci.core.Notice
import az.nvaton.komekci.core.Notices
import az.nvaton.komekci.komekci

/**
 * Incoming notifications: reads messages, missed calls and reminders aloud ([Announcer]) and keeps the latest
 * messages in memory for «mesajlarımı oxu». The user turns it on in system settings (notification access).
 */
class NotificationReaderService : NotificationListenerService() {
    private val seen = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName || sbn.isOngoing) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val kind = kindOf(sbn.packageName, notification) ?: return

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val latest = style?.messages?.lastOrNull()
        val text = (latest?.text ?: messageText(notification))?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val sender = latest?.person?.name?.toString() ?: title
        val group = style?.conversationTitle?.toString()?.takeIf { style.isGroupConversation }
        if (!firstTime("${sbn.packageName}|$sender|$text")) return

        val notice = Notice(appLabel(sbn.packageName), sender, text, group, kind, System.currentTimeMillis())
        if (kind == Notice.Kind.MESSAGE) Announcer.remember(notice)
        // Do Not Disturb keeps the phone quiet.
        if (komekci.settings.announceNotifications && quietModeAllows()) Announcer.announce(this, Notices.announcement(notice))
    }

    private fun kindOf(pkg: String, n: Notification): Notice.Kind? = when {
        n.category == "missed_call" -> Notice.Kind.MISSED_CALL // Notification.CATEGORY_MISSED_CALL (API 31)
        n.category == Notification.CATEGORY_MESSAGE || pkg in MESSAGING_APPS -> Notice.Kind.MESSAGE
        n.category == Notification.CATEGORY_EMAIL || n.category == Notification.CATEGORY_REMINDER ||
            n.category == Notification.CATEGORY_EVENT -> Notice.Kind.OTHER
        else -> null // promotions, downloads, system status: not worth reading aloud
    }

    private fun quietModeAllows(): Boolean {
        val filter = runCatching { currentInterruptionFilter }.getOrDefault(INTERRUPTION_FILTER_UNKNOWN)
        return filter == INTERRUPTION_FILTER_ALL || filter == INTERRUPTION_FILTER_UNKNOWN
    }

    private fun appLabel(pkg: String): String =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun messageText(n: Notification): CharSequence? =
        n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)

    /** Apps re-post the same notification often; read each message once. */
    private fun firstTime(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        seen.entries.removeAll { now - it.value > 10 * 60_000 }
        if (seen.containsKey(key)) return false
        seen[key] = now
        if (seen.size > 100) seen.remove(seen.keys.first())
        return true
    }

    companion object {
        private val MESSAGING_APPS = setOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
            "com.android.messaging",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.thunderdog.challegram",
            "com.viber.voip",
            "com.facebook.orca",
            "com.imo.android.imoim",
            "org.thoughtcrime.securesms",
            "com.google.android.apps.googlevoice",
        )

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
