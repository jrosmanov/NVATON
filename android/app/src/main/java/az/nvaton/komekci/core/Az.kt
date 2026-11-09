package az.nvaton.komekci.core

import java.util.Locale

/** Azerbaijani text helpers: case rules, diacritic-insensitive keys, tokens and suffixes. */
object Az {
    val LOCALE: Locale = Locale.forLanguageTag("az")

    private val FOLD = mapOf('ə' to 'e', 'ı' to 'i', 'ö' to 'o', 'ü' to 'u', 'ş' to 's', 'ç' to 'c', 'ğ' to 'g')
    private const val BACK_VOWELS = "aıou"
    private const val FRONT_VOWELS = "eəiöü"
    private val WHITESPACE = Regex("\\s+")

    /** Lowercase with Azerbaijani rules (I → ı, İ → i). */
    fun lower(text: String): String = text.lowercase(LOCALE)

    /**
     * Matching key for one word: lowercase, no Azerbaijani diacritics, letters and digits only.
     * "Nəvəmə," → "neveme". Speech recognition is inconsistent with diacritics, so all rules use keys.
     */
    fun fold(word: String): String {
        val sb = StringBuilder(word.length)
        for (ch in lower(word)) {
            val c = FOLD[ch] ?: ch
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }

    data class Token(val raw: String, val key: String)

    fun tokenize(text: String): List<Token> =
        text.trim().split(WHITESPACE).filter { it.isNotEmpty() }.map { Token(it, fold(it)) }

    /** Dative case of a name, following vowel harmony: Murad → Murada, Aysu → Aysuya, Əli → Əliyə. */
    fun dative(name: String): String {
        val trimmed = name.trim()
        val lower = lower(trimmed)
        val lastVowel = lower.lastOrNull { it in BACK_VOWELS || it in FRONT_VOWELS } ?: return trimmed
        val vowel = if (lastVowel in BACK_VOWELS) "a" else "ə"
        val last = lower.last()
        val buffer = if (last in BACK_VOWELS || last in FRONT_VOWELS) "y" else ""
        return trimmed + buffer + vowel
    }

    fun capitalizeFirst(text: String): String =
        if (text.isEmpty()) text else text.substring(0, 1).uppercase(I18n.lang.locale) + text.substring(1)

    /** ["Murada", "Aysuya"] → "Murada, yoxsa Aysuya" */
    fun orList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + tr(", yoxsa ", " or ") + items.last()
    }
}
