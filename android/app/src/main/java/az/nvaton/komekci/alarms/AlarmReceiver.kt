package az.nvaton.komekci.alarms

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import az.nvaton.komekci.R
import az.nvaton.komekci.core.AlarmSpec
import az.nvaton.komekci.core.AlarmTime
import az.nvaton.komekci.core.tr

/**
 * Rings an alarm: a full-screen notification that opens [AlarmActivity] (also over the lock screen), with
 * «Söndür» and «5 dəqiqə sonra» buttons. Not exported: only our own PendingIntents reach it.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RING -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                val alarm = Alarms.onRang(context, id) ?: return
                ring(context, alarm)
            }
            ACTION_STOP -> stop(context)
            ACTION_SNOOZE -> {
                stop(context)
                snooze(context, intent.getStringExtra(EXTRA_LABEL).orEmpty())
            }
        }
    }

    @SuppressLint("MissingPermission") // checked below
    private fun ring(context: Context, alarm: Alarm) {
        ensureChannel(context)
        val title = alarm.label.ifBlank { tr("Zəngli saat", "Alarm") }
        val time = AlarmTime.clock(alarm.nextTime())
        val screen = Intent(context, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .putExtra(EXTRA_LABEL, alarm.label)
            .putExtra(EXTRA_TIME, time)
        val full = PendingIntent.getActivity(context, 1, screen, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("⏰ $time · $title")
            .setContentText(tr("Söndürmək üçün toxunun", "Tap to turn it off"))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, tr("Söndür", "Stop"), action(context, ACTION_STOP, alarm.label, 2))
            .addAction(0, tr("5 dəqiqə sonra", "In 5 minutes"), action(context, ACTION_SNOOZE, alarm.label, 3))
            .build()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
        // Over another app the notification shows only as a banner; with the accessibility service on, open the screen too.
        try {
            context.startActivity(screen)
        } catch (e: Exception) {
            Log.i("Alarms", "Alarm screen left to the notification", e)
        }
    }

    private fun action(context: Context, action: String, label: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            code,
            Intent(context, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_LABEL, label),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val ACTION_RING = "az.nvaton.komekci.alarms.RING"
        const val ACTION_STOP = "az.nvaton.komekci.alarms.STOP"
        const val ACTION_SNOOZE = "az.nvaton.komekci.alarms.SNOOZE"
        const val EXTRA_ID = "id"
        const val EXTRA_LABEL = "label"
        const val EXTRA_TIME = "time"
        private const val CHANNEL_ID = "alarms"
        private const val NOTIFICATION_ID = 21
        const val SNOOZE_MINUTES = 5

        /** Silences the ringing screen and removes the notification. */
        fun stop(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            AlarmActivity.finishRinging()
        }

        fun snooze(context: Context, label: String) {
            Alarms.add(context, AlarmSpec(inMinutes = SNOOZE_MINUTES, label = label))
        }

        private fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, tr("Zəngli saatlar", "Alarms"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tr("Səslə qurduğunuz zəngli saatlar və xatırlatmalar", "Alarms and reminders you set by voice")
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

/** The system forgets alarms on reboot and app update, and a clock change moves them: arm them again. */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Alarms.rescheduleAll(context)
        }
    }
}
