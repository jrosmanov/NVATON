package az.nvaton.komekci.core

data class FamilyContact(
    val id: String,
    val name: String,
    val phone: String,
    /** How the user refers to this person: "nəvəm", "qızım", "balaca nəvəm"... */
    val aliases: List<String> = emptyList(),
) {
    /** No number: found only by searching WhatsApp for [name] (a group, or someone not in the phone book). */
    val isWhatsAppSearch: Boolean get() = id.startsWith(WHATSAPP_SEARCH)

    /** Match keys for the name and every alias; each key is a list of folded words. */
    val keys: List<List<String>> get() = if (I18n.english) englishKeys else azKeys

    private val azKeys: List<List<String>> by lazy {
        (listOf(name) + aliases)
            .map { phrase -> Az.tokenize(phrase).map { it.key }.filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }
    }

    /** In English, "my grandson" finds the contact saved as "nəvəm". */
    private val englishKeys: List<List<String>> by lazy {
        azKeys + azKeys.flatMap { key -> ENGLISH_RELATIONS[key.joinToString(" ")].orEmpty().map { listOf(it) } }
    }

    companion object {
        private const val WHATSAPP_SEARCH = "wa:"

        fun whatsAppSearch(name: String) = FamilyContact("$WHATSAPP_SEARCH${name.trim()}", name.trim(), "")

        private val ENGLISH_RELATIONS = mapOf(
            "nevem" to listOf("grandson", "granddaughter", "grandchild", "grandkid"),
            "qizim" to listOf("daughter"),
            "oglum" to listOf("son"),
            "heyat yoldasim" to listOf("wife", "husband"),
            "anam" to listOf("mother", "mom", "mum"),
            "atam" to listOf("father", "dad"),
            "bacim" to listOf("sister"),
            "qardasim" to listOf("brother"),
            "dostum" to listOf("friend"),
        )
    }
}

object ContactMatcher {
    /**
     * A spoken word matches a key word when it is the word itself or the word plus a short
     * case/possessive ending: "nəvəmə" → "nəvəm", "Murada" → "Murad", "oğlumla" → "oğlum".
     */
    fun wordMatches(tokenKey: String, word: String): Boolean {
        if (tokenKey == word) return word.isNotEmpty()
        return word.length >= 3 && tokenKey.startsWith(word) && tokenKey.length - word.length <= 4
    }

    data class Match(val start: Int, val end: Int, val contacts: List<FamilyContact>)

    /** First position in tokens[from, until) where one or more contacts are mentioned. */
    fun findFirst(
        tokens: List<Az.Token>,
        contacts: List<FamilyContact>,
        from: Int = 0,
        until: Int = tokens.size,
    ): Match? {
        val limit = minOf(until, tokens.size)
        for (i in from.coerceAtLeast(0) until limit) {
            val hits = LinkedHashSet<FamilyContact>()
            var end = i + 1
            for (contact in contacts) {
                for (key in contact.keys) {
                    if (i + key.size > limit) continue
                    val matches = key.indices.all { j ->
                        val token = tokens[i + j].key
                        if (j == key.lastIndex) wordMatches(token, key[j]) else token == key[j]
                    }
                    if (matches) {
                        hits += contact
                        end = maxOf(end, i + key.size)
                    }
                }
            }
            if (hits.isNotEmpty()) return Match(i, end, hits.toList())
        }
        return null
    }

    /** Every contact mentioned anywhere in the text, in order of appearance. */
    fun findAll(text: String, contacts: List<FamilyContact>): List<FamilyContact> {
        val tokens = Az.tokenize(text)
        val found = LinkedHashSet<FamilyContact>()
        var i = 0
        while (i < tokens.size) {
            val match = findFirst(tokens, contacts, i) ?: break
            found += match.contacts
            i = match.end
        }
        return found.toList()
    }
}
