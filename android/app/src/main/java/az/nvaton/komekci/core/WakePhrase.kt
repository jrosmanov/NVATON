package az.nvaton.komekci.core

import java.text.Normalizer

/**
 * Decides whether an offline transcript is the user calling the assistant. The transcript comes from
 * a small Turkish speech model on the phone (there is no Azerbaijani one), which writes "Əli" as
 * "ali" or "eli".
 *  - "ali" → wake up and ask "Bəli?";
 *  - "ali neveme yaz …" → wake up, the command was already spoken;
 *  - "onun eli ağrıyor", "allah razı olsun", "elimde …" → not addressed to the assistant.
 */
object WakePhrase {
    private val WHITESPACE = Regex("\\s+")
    private val MARKS = Regex("\\p{M}+")
    private val GREETINGS = setOf("hey", "hay", "ey", "ay", "salam", "selam", "merhaba")

    /**
     * null when the transcript is not addressed to the assistant; otherwise how many words followed the name.
     * The name must come first (after an optional "hey" / "salam"), or second in a two-word transcript:
     * a breath or a noise before it often comes out as a stray word ("başkan ali").
     */
    fun match(transcript: String, assistantName: String): Int? {
        val name = key(assistantName)
        if (name.isEmpty()) return null
        val words = transcript.split(WHITESPACE).map(::key).filter { it.isNotEmpty() }
        var i = 0
        while (i < words.size && i < 2 && words[i] in GREETINGS) i++
        val first = words.getOrNull(i) ?: return null
        return when {
            sameName(first, name) -> words.size - i - 1
            words.size == 2 && i == 0 && sameName(words[1], name) -> 0
            else -> null
        }
    }

    /** "eli" = "ali": a Turkish ear hears the Azerbaijani "ə" either way. */
    private fun sameName(word: String, name: String): Boolean =
        word == name ||
            (word.length == name.length && word.length > 1 && word[0] in "ae" && name[0] in "ae" && word.drop(1) == name.drop(1))

    /** Folded key that also bridges Turkish spelling: no ə, x is written h, q is written k ("Xanım" → "hanim"). */
    internal fun key(word: String): String {
        val plain = Normalizer.normalize(word, Normalizer.Form.NFD).replace(MARKS, "")
        return Az.fold(plain).map {
            when (it) {
                'x' -> 'h'
                'q' -> 'k'
                else -> it
            }
        }.joinToString("")
    }
}
