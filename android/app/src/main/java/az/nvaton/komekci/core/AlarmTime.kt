package az.nvaton.komekci.core

import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** An alarm as the AI model understood it: a clock time (hour/minute, maybe a day ahead) or a delay in minutes. */
data class AlarmSpec(
    val hour: Int = -1,
    val minute: Int = 0,
    val inMinutes: Int = 0,
    val dayOffset: Int = 0,
    val repeatDaily: Boolean = false,
    val label: String = "",
) {
    val isValid: Boolean get() = inMinutes > 0 || (hour in 0..23 && minute in 0..59)
}

/** Alarm times and how the assistant says them. Pure Kotlin, so it is unit-tested. */
object AlarmTime {
    /** When [spec] should first ring. A clock time that has already passed today means tomorrow. */
    fun firstTrigger(spec: AlarmSpec, now: ZonedDateTime): ZonedDateTime? {
        if (!spec.isValid) return null
        if (spec.inMinutes > 0) return now.plusMinutes(spec.inMinutes.toLong()).truncatedTo(ChronoUnit.SECONDS)
        var at = now.toLocalDate().plusDays(spec.dayOffset.coerceIn(0, 366).toLong())
            .atTime(spec.hour, spec.minute).atZone(now.zone)
        while (!at.isAfter(now)) at = at.plusDays(1)
        return at
    }

    /** The next day's ring for a daily alarm. */
    fun nextDaily(previous: ZonedDateTime, now: ZonedDateTime): ZonedDateTime {
        var at = previous.plusDays(1)
        while (!at.isAfter(now)) at = at.plusDays(1)
        return at
    }

    fun clock(at: ZonedDateTime): String =
        if (I18n.english) at.format(DateTimeFormatter.ofPattern("h:mm a", Lang.EN.locale)) else at.format(DateTimeFormatter.ofPattern("HH:mm"))

    /** "sabah, saat 07:30" / "tomorrow at 7:30 AM"; "hər gün, saat 21:00" / "every day at 9:00 PM". */
    fun describe(at: ZonedDateTime, repeatDaily: Boolean, label: String, now: ZonedDateTime): String {
        val time = clock(at)
        val days = Duration.between(now.toLocalDate().atStartOfDay(now.zone), at.toLocalDate().atStartOfDay(at.zone)).toDays()
        val day = when {
            repeatDaily -> tr("hər gün", "every day")
            days == 0L -> tr("bu gün", "today")
            days == 1L -> tr("sabah", "tomorrow")
            else -> at.format(DateTimeFormatter.ofPattern("d MMMM", I18n.lang.locale))
        }
        val base = if (I18n.english) "$day at $time" else "$day, saat $time"
        return if (label.isBlank()) base else "$base, «${label.trim()}»"
    }

    fun inMinutesPhrase(minutes: Int, at: ZonedDateTime): String =
        tr("$minutes dəqiqə sonra (saat ${clock(at)})", "in $minutes minutes (at ${clock(at)})")
}
