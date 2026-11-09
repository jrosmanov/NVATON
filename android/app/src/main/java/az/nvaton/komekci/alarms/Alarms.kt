package az.nvaton.komekci.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import az.nvaton.komekci.MainActivity
import az.nvaton.komekci.core.AlarmSpec
import az.nvaton.komekci.core.AlarmTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** One alarm or reminder set by voice. [nextAt] is when it rings next (epoch millis). */
data class Alarm(
    val id: String,
    val hour: Int,
    val minute: Int,
    val label: String,
    val repeatDaily: Boolean,
    val nextAt: Long,
) {
    fun nextTime(zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime = Instant.ofEpochMilli(nextAt).atZone(zone)
}

/**
 * The app's own alarms, so they can be set, listed and deleted by voice (the phone's Clock app offers no way to
 * read or delete its alarms). Stored in preferences, rung with AlarmManager.setAlarmClock (exact, shown in the
 * status bar, survives Doze), re-armed after a reboot or an app update by [AlarmReceiver].
 */
object Alarms {
    private const val PREFS = "alarms"
    private const val KEY = "list"
    private const val TAG = "Alarms"

    private val _items = MutableStateFlow<List<Alarm>>(emptyList())
    val items: StateFlow<List<Alarm>> = _items

    @Volatile
    private var loaded = false

    fun all(context: Context): List<Alarm> {
        ensureLoaded(context)
        return _items.value.sortedBy { it.nextAt }
    }

    fun get(context: Context, id: String): Alarm? = all(context).firstOrNull { it.id == id }

    /** Schedules [spec]; null when it has no usable time. */
    fun add(context: Context, spec: AlarmSpec, now: ZonedDateTime = ZonedDateTime.now()): Alarm? {
        val at = AlarmTime.firstTrigger(spec, now) ?: return null
        val alarm = Alarm(
            id = UUID.randomUUID().toString().take(8),
            hour = at.hour,
            minute = at.minute,
            label = spec.label.trim().take(100),
            repeatDaily = spec.repeatDaily && spec.inMinutes <= 0,
            nextAt = at.toInstant().toEpochMilli(),
        )
        save(context, all(context) + alarm)
        schedule(context, alarm)
        return alarm
    }

    /** Removes the alarms with these ids; returns the ones that existed. */
    fun cancel(context: Context, ids: Collection<String>): List<Alarm> {
        val (gone, kept) = all(context).partition { it.id in ids }
        gone.forEach { unschedule(context, it) }
        save(context, kept)
        return gone
    }

    fun cancelAll(context: Context): List<Alarm> = cancel(context, all(context).map { it.id })

    /** Called when an alarm rings: a daily one moves to tomorrow, a one-off one is removed. */
    fun onRang(context: Context, id: String, now: ZonedDateTime = ZonedDateTime.now()): Alarm? {
        val alarm = get(context, id) ?: return null
        if (alarm.repeatDaily) {
            val next = alarm.copy(nextAt = AlarmTime.nextDaily(alarm.nextTime(now.zone), now).toInstant().toEpochMilli())
            save(context, all(context).map { if (it.id == id) next else it })
            schedule(context, next)
        } else {
            save(context, all(context).filterNot { it.id == id })
        }
        return alarm
    }

    /** After a reboot or an app update the system forgets alarms: arm them again, dropping one-offs that passed. */
    fun rescheduleAll(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val nowMs = now.toInstant().toEpochMilli()
        val fresh = all(context).mapNotNull { alarm ->
            when {
                alarm.nextAt > nowMs -> alarm
                alarm.repeatDaily -> alarm.copy(nextAt = AlarmTime.nextDaily(alarm.nextTime(now.zone), now).toInstant().toEpochMilli())
                else -> null
            }
        }
        save(context, fresh)
        fresh.forEach { schedule(context, it) }
    }

    private fun schedule(context: Context, alarm: Alarm) {
        val am = context.getSystemService(AlarmManager::class.java)
        val ring = ringIntent(context, alarm.id)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.nextAt, ring) // may ring a few minutes late
            } else {
                val show = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(alarm.nextAt, show), ring)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarms not allowed; using an inexact one", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.nextAt, ring)
        }
    }

    private fun unschedule(context: Context, alarm: Alarm) {
        context.getSystemService(AlarmManager::class.java).cancel(ringIntent(context, alarm.id))
    }

    private fun ringIntent(context: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_RING).putExtra(AlarmReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            _items.value = runCatching { parse(raw) }.getOrDefault(emptyList())
            loaded = true
        }
    }

    private fun save(context: Context, list: List<Alarm>) {
        _items.value = list.sortedBy { it.nextAt }
        val json = JSONArray()
        list.forEach {
            json.put(
                JSONObject().put("id", it.id).put("hour", it.hour).put("minute", it.minute).put("label", it.label)
                    .put("daily", it.repeatDaily).put("next", it.nextAt),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }

    private fun parse(raw: String?): List<Alarm> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Alarm(o.getString("id"), o.getInt("hour"), o.getInt("minute"), o.optString("label"), o.optBoolean("daily"), o.getLong("next"))
        }
    }
}
