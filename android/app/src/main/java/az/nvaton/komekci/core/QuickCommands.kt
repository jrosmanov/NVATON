package az.nvaton.komekci.core

import java.time.LocalDateTime
import java.util.Locale

/** What a fast command does. Each one is handled on the phone, without the AI server. */
enum class QuickIntent {
    EMERGENCY, HELP_ME,
    VIDEO_CALL, CALL,
    READ_MESSAGES, OPEN_CHAT, VOICE_MESSAGE, MESSAGE, MESSAGE_ASK,
    TIME, DATE, BATTERY, INTERNET,
    VOLUME_UP, VOLUME_DOWN, VOLUME_MAX, MUTE, SOUND_ON, FLASHLIGHT_ON, FLASHLIGHT_OFF, BRIGHTNESS, WIFI, BLUETOOTH,
    OPEN_CAMERA, OPEN_APP,
    MEDICINE_REMIND, REMIND_AT, REMIND_IN, ALARM_IN, ALARM_AT, TIMER, LIST_ALARMS, CANCEL_ALARMS,
    MEDICINE_TAKEN, MEDICINE_CHECK,
    READ_SCREEN, CAMERA,
    REPEAT, STOP, SLOWER, FASTER, THANKS, HELLO, HOW_ARE_YOU, NAME, HELP, LANGUAGE_AZ,
}

/** A recognised fast command, with what was said for its slots. */
data class QuickMatch(
    val intent: QuickIntent,
    val pattern: String,
    val contact: String? = null,
    val text: String? = null,
    val alarm: AlarmSpec? = null,
    /** A canonical app key from [QuickCommands.APPS] ("whatsapp", "gallery"…). */
    val app: String? = null,
    /** "ambulance", "police", "fire" or "emergency". */
    val service: String? = null,
)

/**
 * The fast-command database: the most common requests (see assets/commands/en.tsv), matched on the phone in a
 * millisecond, so they need neither the AI server nor the internet. The file is plain text the team can edit:
 * `category | INTENT | pattern`, with `(a/b)` alternatives, `[a/b]` optional words and `{slot}` placeholders.
 */
class QuickCommands private constructor(val entries: List<Entry>) {

    class Entry(val category: String, val intent: QuickIntent, val pattern: String, val elements: List<Element>)

    sealed interface Element {
        data class Words(val options: List<List<String>>, val optional: Boolean) : Element
        data class Slot(val name: String) : Element
    }

    private class Said(val tokens: List<String>, val tomorrow: Boolean, val daily: Boolean)

    /** The first command whose pattern fits [text] completely, or null (then the usual rules and the AI take over). */
    fun match(text: String, now: LocalDateTime = LocalDateTime.now()): QuickMatch? {
        val said = prepare(text)
        if (said.tokens.isEmpty()) return null
        for (entry in entries) {
            val slots = HashMap<String, String>()
            if (!fits(entry.elements, 0, said.tokens, 0, slots, now)) continue
            return build(entry, slots, said, now) ?: continue
        }
        return null
    }

    private fun build(entry: Entry, slots: Map<String, String>, said: Said, now: LocalDateTime): QuickMatch? {
        val alarm = when (entry.intent) {
            QuickIntent.ALARM_AT, QuickIntent.REMIND_AT, QuickIntent.MEDICINE_REMIND -> {
                val morning = entry.pattern.startsWith("wake")
                val (h, m) = clock(slots["time"] ?: return null, now, said.tomorrow, morning) ?: return null
                val label = when (entry.intent) {
                    QuickIntent.MEDICINE_REMIND -> "Take your medicine"
                    QuickIntent.REMIND_AT -> slots["text"].orEmpty().replaceFirstChar { it.uppercase() }
                    else -> ""
                }
                AlarmSpec(
                    hour = h,
                    minute = m,
                    dayOffset = if (said.tomorrow) 1 else 0,
                    repeatDaily = said.daily || entry.intent == QuickIntent.MEDICINE_REMIND, // medicine: every day unless said otherwise
                    label = label,
                )
            }
            QuickIntent.ALARM_IN, QuickIntent.REMIND_IN, QuickIntent.TIMER -> {
                val minutes = minutes(slots["duration"] ?: return null) ?: return null
                val label = when (entry.intent) {
                    QuickIntent.REMIND_IN -> slots["text"].orEmpty().replaceFirstChar { it.uppercase() }
                    QuickIntent.TIMER -> "Timer"
                    else -> ""
                }
                AlarmSpec(inMinutes = minutes, label = label)
            }
            else -> null
        }
        return QuickMatch(
            intent = entry.intent,
            pattern = entry.pattern,
            contact = slots["contact"],
            text = slots["text"],
            alarm = alarm,
            app = slots["app"]?.let { APPS[it] },
            service = slots["service"]?.let { SERVICES[it] },
        )
    }

    /** Pattern elements against the words said, backtracking over optional words and slot lengths. */
    private fun fits(els: List<Element>, i: Int, tokens: List<String>, j: Int, slots: MutableMap<String, String>, now: LocalDateTime): Boolean {
        if (i == els.size) return j == tokens.size
        return when (val el = els[i]) {
            is Element.Words -> {
                el.options.any { opt ->
                    j + opt.size <= tokens.size && opt.indices.all { tokens[j + it] == opt[it] } && fits(els, i + 1, tokens, j + opt.size, slots, now)
                } || (el.optional && fits(els, i + 1, tokens, j, slots, now))
            }
            is Element.Slot -> {
                val left = tokens.size - j
                val lengths = if (el.name == "text") (left downTo 1) else (1..minOf(left, 6))
                lengths.any { len ->
                    val value = tokens.subList(j, j + len).joinToString(" ")
                    if (!valid(el.name, value, now)) return@any false
                    slots[el.name] = value
                    fits(els, i + 1, tokens, j + len, slots, now).also { if (!it) slots.remove(el.name) }
                }
            }
        }
    }

    private fun valid(slot: String, value: String, now: LocalDateTime): Boolean = when (slot) {
        "contact" -> value.split(' ').size <= 4 && value !in NOT_A_PERSON && value.split(' ').none { it in NOT_A_PERSON_WORDS }
        "text" -> value.isNotBlank()
        "time" -> clock(value, now, tomorrow = false, morning = false) != null
        "duration" -> minutes(value) != null
        "app" -> value in APPS
        "service" -> value in SERVICES
        else -> false
    }

    companion object {
        /** The English database, loaded from assets at startup (tests load it from the file). */
        @Volatile
        var english: QuickCommands? = null

        fun parse(tsv: String): QuickCommands = QuickCommands(
            tsv.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split(" | ").map { it.trim() }
                    if (parts.size != 3) return@mapNotNull null
                    val intent = runCatching { QuickIntent.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
                    Entry(parts[0], intent, parts[2], elements(parts[2]))
                }
                .toList(),
        )

        private fun elements(pattern: String): List<Element> {
            val out = ArrayList<Element>()
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == ' ' -> i++
                    c == '(' || c == '[' -> {
                        val close = pattern.indexOf(if (c == '(') ')' else ']', i)
                        val options = pattern.substring(i + 1, close).split('/').map { o -> o.trim().split(' ').filter { it.isNotEmpty() } }
                        out += Element.Words(options, optional = c == '[')
                        i = close + 1
                    }
                    c == '{' -> {
                        val close = pattern.indexOf('}', i)
                        out += Element.Slot(pattern.substring(i + 1, close))
                        i = close + 1
                    }
                    else -> {
                        var end = pattern.indexOf(' ', i)
                        if (end < 0) end = pattern.length
                        out += Element.Words(listOf(listOf(pattern.substring(i, end))), optional = false)
                        i = end
                    }
                }
            }
            return out
        }

        /** Lowercase words, contractions spelled out, "tomorrow" / "every day" lifted out as flags, politeness trimmed. */
        private fun prepare(raw: String): Said {
            var s = " " + raw.lowercase(Locale.US).replace('’', '\'') + " "
            CONTRACTIONS.forEach { (k, v) -> s = s.replace(" $k ", " $v ") }
            s = s.replace("wi-fi", "wifi").replace("wi fi", "wifi").replace("a.m.", "am").replace("p.m.", "pm")
                .replace("o'clock", "").replace("'", "")
            s = s.replace(Regex("[^\\p{L}0-9: ]"), " ").replace(Regex("(?<=\\d)(am|pm)\\b"), " $1")
            var tokens = s.split(' ').map { it.trim(':') }.filter { it.isNotEmpty() }.toMutableList()
            val tomorrow = tokens.remove("tomorrow")
            var daily = false
            for (phrase in listOf(listOf("every", "day"), listOf("each", "day"), listOf("everyday"), listOf("daily"))) {
                val at = (0..tokens.size - phrase.size).firstOrNull { k -> phrase.indices.all { tokens[k + it] == phrase[it] } }
                if (at != null) {
                    repeat(phrase.size) { tokens.removeAt(at) }
                    daily = true
                }
            }
            var trimmed = true
            while (trimmed) {
                trimmed = false
                for (lead in LEADING) {
                    if (tokens.size > lead.size && lead.indices.all { tokens[it] == lead[it] }) {
                        tokens = tokens.drop(lead.size).toMutableList()
                        trimmed = true
                    }
                }
                for (tail in TRAILING) {
                    if (tokens.size > tail.size && tail.indices.all { tokens[tokens.size - tail.size + it] == tail[it] }) {
                        tokens = tokens.dropLast(tail.size).toMutableList()
                        trimmed = true
                    }
                }
            }
            return Said(tokens, tomorrow, daily)
        }

        /**
         * "7", "7:30", "7 pm", "seven thirty", "half past seven", "quarter to 8", "noon", "7 in the evening" → 24-hour
         * clock. Without am/pm the next time it comes is meant ("7" said at 3 pm is 7 pm), except for waking up
         * ([morning]) and for tomorrow, where 6–11 means the morning.
         */
        fun clock(text: String, now: LocalDateTime, tomorrow: Boolean, morning: Boolean): Pair<Int, Int>? {
            var words = text.split(' ').filter { it.isNotEmpty() && it != "at" && it != "the" }
            var period: String? = null
            fun takeMarker(vararg marker: String, value: String) {
                val at = (0..words.size - marker.size).firstOrNull { k -> marker.indices.all { words[k + it] == marker[it] } } ?: return
                words = words.take(at) + words.drop(at + marker.size)
                period = value
            }
            takeMarker("am", value = "am")
            takeMarker("pm", value = "pm")
            takeMarker("in", "morning", value = "am")
            takeMarker("in", "afternoon", value = "pm")
            takeMarker("in", "evening", value = "pm")
            takeMarker("tonight", value = "pm")
            takeMarker("night", value = "night")
            if (words.isEmpty()) return null
            if (words == listOf("noon") || words == listOf("midday")) return 12 to 0
            if (words == listOf("midnight")) return 0 to 0

            var hour: Int
            var minute = 0
            when {
                words.size == 3 && words[0] == "half" && words[1] == "past" -> {
                    hour = number(words.drop(2)) ?: return null
                    minute = 30
                }
                words.size >= 3 && words[0] == "quarter" && words[1] == "past" -> {
                    hour = number(words.drop(2)) ?: return null
                    minute = 15
                }
                words.size >= 3 && words[0] == "quarter" && words[1] == "to" -> {
                    hour = (number(words.drop(2)) ?: return null) - 1
                    minute = 45
                    if (hour < 0) hour = 23
                }
                words.size == 1 && words[0].contains(':') -> {
                    val (h, m) = words[0].split(':').let { (it.getOrNull(0)?.toIntOrNull() ?: return null) to (it.getOrNull(1)?.toIntOrNull() ?: return null) }
                    hour = h
                    minute = m
                }
                else -> {
                    // "7", "seven", "seven thirty", "7 30", "seven forty five"
                    val first = number(words.take(1)) ?: return null
                    hour = first
                    if (words.size > 1) minute = number(words.drop(1)) ?: return null
                }
            }
            if (hour !in 0..23 || minute !in 0..59) return null
            when (period) {
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
                "night" -> if (hour in 6..11) hour += 12 else if (hour == 12) hour = 0
                null -> if (hour in 1..11) {
                    hour = when {
                        morning || tomorrow -> if (hour in 1..5 && !morning) hour + 12 else hour
                        // the next one to come, today
                        now.toLocalTime().isBefore(java.time.LocalTime.of(hour, minute)) -> hour
                        now.toLocalTime().isBefore(java.time.LocalTime.of(hour + 12, minute)) -> hour + 12
                        else -> hour
                    }
                }
            }
            return hour to minute
        }

        /** "10 minutes", "an hour", "half an hour", "1 hour 30 minutes", "twenty five minutes" → minutes. */
        fun minutes(text: String): Int? {
            val words = text.split(' ').filter { it.isNotEmpty() && it != "and" }
            if (words == listOf("half", "an", "hour")) return 30
            var total = 0
            var i = 0
            var any = false
            while (i < words.size) {
                var j = i
                while (j < words.size && words[j] !in UNITS) j++
                if (j == words.size) return null
                val amount = when (val n = words.subList(i, j)) {
                    listOf("a"), listOf("an"), listOf("one") -> 1
                    else -> number(n) ?: return null
                }
                total += amount * UNITS.getValue(words[j])
                any = true
                i = j + 1
            }
            return total.takeIf { any && it in 1..24 * 60 }
        }

        /** "7", "seven", "twenty five", "45" → the number (0–99), or null. */
        fun number(words: List<String>): Int? {
            if (words.isEmpty()) return null
            if (words.size == 1) words[0].toIntOrNull()?.let { return it }
            var total = 0
            for (w in words) {
                val v = NUMBERS[w] ?: return null
                total += v
            }
            return total.takeIf { words.size == 1 || NUMBERS.getValue(words[0]) >= 20 }
        }

        private val NUMBERS = mapOf(
            "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
            "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
            "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
            "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        )
        private val UNITS = mapOf("minute" to 1, "minutes" to 1, "min" to 1, "mins" to 1, "hour" to 60, "hours" to 60)

        /** Spoken app names → canonical keys the app knows how to open. */
        val APPS = mapOf(
            "whatsapp" to "whatsapp", "youtube" to "youtube", "telegram" to "telegram", "facebook" to "facebook",
            "instagram" to "instagram", "gallery" to "gallery", "photos" to "gallery", "pictures" to "gallery",
            "calendar" to "calendar", "clock" to "clock", "maps" to "maps", "google maps" to "maps", "map" to "maps",
            "settings" to "settings", "phone" to "phone", "dialer" to "phone", "messages" to "messages", "sms" to "messages",
            "music" to "music", "contacts" to "contacts", "browser" to "browser", "chrome" to "browser", "internet" to "browser",
            "email" to "email", "mail" to "email", "gmail" to "email", "play store" to "store", "store" to "store",
            "calculator" to "calculator",
        )

        val SERVICES = mapOf(
            "ambulance" to "ambulance", "doctor" to "ambulance", "police" to "police", "fire brigade" to "fire",
            "fire department" to "fire", "firefighters" to "fire", "emergency" to "emergency", "emergency services" to "emergency",
        )

        private val NOT_A_PERSON = setOf("me", "it", "this", "that", "you", "us", "them", "him", "her")
        private val NOT_A_PERSON_WORDS = setOf("alarm", "timer", "message", "messages", "screen", "camera", "time", "volume", "sound")

        private val CONTRACTIONS = listOf(
            "what's" to "what is", "it's" to "it is", "that's" to "that is", "i'm" to "i am", "i've" to "i have",
            "don't" to "do not", "can't" to "cannot", "won't" to "will not", "didn't" to "did not", "isn't" to "is not",
            "let's" to "let us", "i'd" to "i would", "i'll" to "i will", "who's" to "who is", "how's" to "how is",
            "today's" to "todays",
        )
        private val LEADING = listOf(
            listOf("hey", "ali"), listOf("ok", "ali"), listOf("okay", "ali"), listOf("ali"), listOf("eli"), listOf("hey"),
            listOf("please"), listOf("can", "you"), listOf("could", "you"), listOf("would", "you"), listOf("will", "you"),
            listOf("i", "want", "to"), listOf("i", "would", "like", "to"), listOf("just"), listOf("now"),
        )
        private val TRAILING = listOf(listOf("please"), listOf("for", "me"), listOf("now"), listOf("ali"), listOf("thanks"), listOf("thank", "you"))
    }
}
