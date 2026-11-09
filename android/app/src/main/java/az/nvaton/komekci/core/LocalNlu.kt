package az.nvaton.komekci.core

sealed interface Command {
    data class SendMessage(
        val recipients: List<FamilyContact>,
        /** Words that seem to name the recipient when no contact matched ("Murata"). */
        val recipientPhrase: String?,
        val text: String?,
    ) : Command

    data class Call(val recipients: List<FamilyContact>, val recipientPhrase: String?, val video: Boolean) : Command
    /** "Əzizə səsli mesaj göndər": WhatsApp opens the chat and records while they speak. */
    data class VoiceMessage(val recipients: List<FamilyContact>, val recipientPhrase: String?) : Command
    data class ReadWithCamera(val question: String) : Command
    data class ReadScreen(val question: String) : Command
    data class SetLanguage(val lang: Lang) : Command
    data object ReadMessages : Command
    data object ListAlarms : Command
    data object CancelAllAlarms : Command
    /** "Kamranın nömrəsini yadda saxla: 050 123 45 67". Any part may be missing; the dialogue asks for it. */
    data class SaveContact(val name: String?, val number: String?, val alias: String?) : Command
    /** "Telefon kontaktlarımı əlavə et": choose people from the phone's address book. */
    data object ImportContacts : Command
    /** One of the fast commands in the database (English), understood on the phone. */
    data class Quick(val match: QuickMatch) : Command
    data object Help : Command
    data object Yes : Command
    data object No : Command
    data class Unknown(val text: String) : Command
}

/**
 * Rule-based understanding of the most common Azerbaijani commands. Works offline and instantly;
 * anything it does not recognise goes to the AI model through the backend.
 * Every rule compares folded keys, so "neveme yaz salam" works like "nəvəmə yaz salam".
 */
class LocalNlu {

    fun parse(text: String, contacts: List<FamilyContact>): Command {
        val tokens = Az.tokenize(text)
        if (tokens.isEmpty()) return Command.Unknown(text)
        val keys = tokens.map { it.key }
        saveContact(text, tokens)?.let { return it } // before messages: "nömrəsini yadda saxla 050…" is not a message
        if (isImportContacts(keys)) return Command.ImportContacts
        if (I18n.english) {
            QuickCommands.english?.match(text)?.let { return Command.Quick(it) } // the fast database first
            return parseEnglish(text, keys)
        }

        parseVoiceMessage(tokens, contacts)?.let { return it } // before messages: "səsli mesaj göndər" is not the text "Səsli mesaj"
        // A send verb plus a known recipient wins, so "nəvəmə yaz ki dərmanımı içdim" stays a message.
        val send = parseSend(tokens, contacts)
        if (send != null && send.recipients.isNotEmpty()) return send
        languageTarget(keys)?.let { return Command.SetLanguage(it) } // before calls: "ingiliscə danış" is not "danış" = call
        parseCall(tokens, contacts)?.let { return it }
        if (keys.none { it in SEND_VERBS }) {
            if (isReadMessages(keys)) return Command.ReadMessages // before the camera: "mesajlarımı oxu"
            alarmCommand(keys)?.let { return it }
        }
        if (keys.any { it.startsWith("ekran") }) return Command.ReadScreen(text.trim()) // before the camera: "ekranı oxu"
        if (isCamera(keys)) return Command.ReadWithCamera(text.trim())
        if (send != null) return send
        if (isNo(text)) return Command.No
        if (isYes(text)) return Command.Yes
        if (isHelp(keys)) return Command.Help
        return Command.Unknown(text)
    }

    /**
     * English: only what needs no AI is handled here (yes/no, camera, screen, help). Messages and calls go to the
     * AI model through the backend, which understands free English and the contacts' Azerbaijani relation words.
     */
    private fun parseEnglish(text: String, keys: List<String>): Command {
        languageTarget(keys)?.let { return Command.SetLanguage(it) }
        if (isReadMessages(keys)) return Command.ReadMessages
        alarmCommand(keys)?.let { return it }
        val command = keys.any { it in EN_SEND_OR_CALL } // "text my grandson what is this" is a message, not the camera
        if (!command && keys.any { it.startsWith("screen") }) return Command.ReadScreen(text.trim())
        if (!command && isEnglishCamera(keys)) return Command.ReadWithCamera(text.trim())
        if (isNo(text)) return Command.No
        if (isYes(text)) return Command.Yes
        if ((keys.size <= 3 && "help" in keys) || phrase(keys, "what", "can", "you", "do")) return Command.Help
        return Command.Unknown(text)
    }

    /**
     * A number in digits plus a save word: "Kamranın nömrəsini yadda saxla 050 123 45 67", "save Kamran 0501234567".
     * The name is what is left once the command words are gone ("Kamranın" → "Kamran"). Spoken number words go to
     * the AI model, which turns them into digits.
     */
    private fun saveContact(text: String, tokens: List<Az.Token>): Command? {
        val number = PhoneNumbers.find(text) ?: return null
        val keys = tokens.map { it.key }
        if (keys.none { k -> SAVE_WORDS.any { k.startsWith(it) } }) return null
        var alias: String? = null
        // "Ramizin nömrəsini": a name right before "nömrə…" carries the possessive ending; "Elvin 050…" does not.
        val possessive = tokens.zipWithNext().any { (a, b) -> b.key.startsWith("nomre") && a.key.isNotEmpty() && SAVE_WORDS.none { a.key.startsWith(it) } }
        val nameWords = tokens.filter { t ->
            val k = t.key
            when {
                k.isEmpty() || k.any(Char::isDigit) -> false
                RELATIONS.any { k.startsWith(it) } -> {
                    alias = t.raw.trim(',', '.', ':')
                    false
                }
                SAVE_WORDS.any { k.startsWith(it) } || CONTACT_WORDS.any { k.startsWith(it) } -> false
                k in FILLER || k in EN_FILLER || k in NAME_GLUE || k == "eli" || k == "ali" -> false
                else -> true
            }
        }.map { it.raw.trim(',', '.', ':', '«', '»', '"').removeSuffix("'s").removeSuffix("’s") }
        val joined = nameWords.joinToString(" ")
        // "Kamranın" may be Kamran + ın or Kamra + nın; only the AI model (or a dictionary) can tell, so ask it.
        val name = (if (possessive) withoutGenitive(joined) ?: return Command.Unknown(text) else joined).trim().ifBlank { null }
        return Command.SaveContact(name, number, alias)
    }

    /**
     * The possessive ending on a name, when it is unambiguous: "Ramizin" → "Ramiz". After a vowel and n
     * ("Kamranın", "Leylanın") the stem could end either way, so this returns null.
     */
    private fun withoutGenitive(name: String): String? {
        if (name.length < 5) return name
        val lower = name.lowercase(Lang.AZ.locale)
        if (listOf("nın", "nin", "nun", "nün").any { lower.endsWith(it) } && lower[lower.length - 4] in "aıoueəiöü") return null
        val ending = listOf("ın", "in", "un", "ün").firstOrNull { lower.endsWith(it) } ?: return name
        return name.dropLast(ending.length)
    }

    /** "telefon kontaktlarımı əlavə et", "import my phone contacts". */
    private fun isImportContacts(keys: List<String>): Boolean =
        keys.any { it.startsWith("kontakt") || it.startsWith("contact") } &&
            keys.any { it.startsWith("telefon") || it == "phone" || it.startsWith("hamis") || it.startsWith("butun") || it == "all" } &&
            keys.any { k -> listOf("elave", "kocur", "import", "add", "bagla").any { k.startsWith(it) } } &&
            keys.none { it.any(Char::isDigit) }

    /** "dili ingilis dilinə dəyiş", "ingiliscə danış", "switch to Azerbaijani": a language word plus a switch word. */
    private fun languageTarget(keys: List<String>): Lang? {
        val switch = keys.any { k -> LANGUAGE_SWITCH.any { k.startsWith(it) } }
        if (!switch) return null
        val en = keys.any { it.startsWith("ingilis") || it.startsWith("english") }
        val az = keys.any { it.startsWith("azerbaycan") || it.startsWith("azerbaij") || it.startsWith("azeri") }
        return when {
            en && !az -> Lang.EN
            az && !en -> Lang.AZ
            else -> null
        }
    }

    /** "mesajlarımı oxu", "yeni mesaj var?", "kim yazıb?", "read my messages", "who texted me". */
    private fun isReadMessages(keys: List<String>): Boolean {
        val messages = keys.any { it.startsWith("mesaj") || it.startsWith("ismaric") || it == "messages" || it == "sms" }
        val cue = keys.any { it.startsWith("oxu") || it in READ_MESSAGE_CUES }
        return (messages && cue) || phrase(keys, "kim", "yazib") || phrase(keys, "kim", "yazmisdi") ||
            phrase(keys, "who", "texted") || phrase(keys, "who", "wrote") || phrase(keys, "who", "messaged") ||
            (keys.any { it == "message" } && keys.any { it == "new" || it == "any" || it == "unread" } && keys.none { it in EN_COMPOSE })
    }

    /**
     * Only what needs no clock parsing: "zəngli saatlarım hansılardır" lists, "bütün zəngli saatları sil" deletes all.
     * Setting one ("sabah 7-də oyat") goes to the AI model, which reads times in any wording.
     */
    private fun alarmCommand(keys: List<String>): Command? {
        val alarm = keys.any { k -> ALARM_WORDS.any { k.startsWith(it) } } ||
            (keys.any { it == "zengli" } && keys.any { it.startsWith("saat") })
        if (!alarm) return null
        val delete = keys.any { k -> ALARM_DELETE.any { k.startsWith(it) } }
        val all = keys.any { it.startsWith("hamis") || it.startsWith("butun") || it == "all" || it == "every" }
        if (delete && all) return Command.CancelAllAlarms
        if (delete) return null // which one: the AI matches it against the alarm list
        val set = keys.any { k -> ALARM_SET.any { k.startsWith(it) } } || keys.any { k -> k.any(Char::isDigit) }
        val list = keys.any { it in ALARM_LIST_CUES || it.endsWith("larim") || it.endsWith("lerim") || it.startsWith("hansi") }
        return if (list && !set) Command.ListAlarms else null
    }

    private fun isEnglishCamera(keys: List<String>): Boolean {
        if (keys.any { it == "camera" || it.startsWith("magnif") }) return true
        val pointsAtSomething = keys.any { it == "this" || it == "these" || it == "it" }
        return pointsAtSomething && keys.any { it in EN_LOOK_WORDS }
    }

    /**
     * "hə", "bəli, göndər", "yaz", "onu göndər", "send it". Longer sentences only count when they start with a clear
     * yes, and "gələcəm yaz" is new text for the message, not a yes.
     */
    fun isYes(text: String): Boolean {
        val keys = keysOf(text)
        if (keys.isEmpty() || hasNo(keys)) return false
        if (keys.first() in STRONG_YES) return true
        val words = keys.filter { it !in FILLER && it !in EN_FILLER && it !in YES_OBJECTS }
        return words.isNotEmpty() && words.size <= 2 && words.all { it in YES }
    }

    fun isNo(text: String): Boolean = hasNo(keysOf(text))

    /**
     * Calling the message off, as opposed to correcting it: "yox", "yox, göndərmə", "ləğv et", "lazım deyil",
     * "cancel". "Yox, gələcəm yaz" is a correction, so it does not count.
     */
    fun isCancel(text: String): Boolean {
        val keys = keysOf(text)
        if (!hasNo(keys)) return false
        if (keys.any { it.startsWith("legv") || it in CANCEL_WORDS } || phrase(keys, "lazim", "deyil") ||
            phrase(keys, "bos", "ver") || phrase(keys, "dont", "send") || phrase(keys, "never", "mind")
        ) return true
        // "yox", "yox yox", "yox, sağ ol", "no thank you", "not now": nothing is left that could be the new text.
        return keys.none { it !in NO && it !in FILLER && it !in EN_FILLER && it !in DECLINE_WORDS }
    }

    /** "hmm", "nə?", "bilmirəm", "başa düşmədim", "what?": neither an answer nor new text. */
    fun isUnclear(text: String): Boolean {
        val keys = keysOf(text).filter { it !in FILLER && it !in EN_FILLER }
        return keys.all { k -> k in UNCLEAR || k.all { it in "hmea" } }
    }

    /** "yenidən", "yenidən de", "təzədən", "again", "record again": they want to record once more. */
    fun isRedo(text: String): Boolean =
        keysOf(text).any { it.startsWith("yeniden") || it.startsWith("tezeden") || it == "again" || it.startsWith("redo") }

    /** "dəyiş", "düzəlt", "mesajı yenidən deyim", "change it", "fix the message": they want to say it again. */
    fun isEditRequest(text: String): Boolean {
        val keys = keysOf(text).filter { it !in FILLER && it !in EN_FILLER && it !in NO }
        return keys.isNotEmpty() && keys.size <= 4 && keys.any { k -> EDIT_WORDS.any { k.startsWith(it) } } &&
            keys.all { k -> EDIT_WORDS.any { k.startsWith(it) } || k in EDIT_OBJECTS }
    }

    /** "kameranı aç", "lupa": open the camera without a question yet, so nothing is photographed automatically. */
    fun isJustOpenCamera(text: String): Boolean {
        val keys = keysOf(text).filter { it !in FILLER && it !in EN_FILLER }
        fun isCameraWord(k: String) = k.startsWith("kamera") || k.startsWith("lupa") || k == "camera" || k.startsWith("magnif")
        return keys.any(::isCameraWord) && keys.all { isCameraWord(it) || it in OPEN_VERBS || it in EN_OPEN_VERBS }
    }

    /** "bağla", "kameranı bağla", "geri": leave the camera screen. A question ("necə bağlayım?") is not a command. */
    fun isCloseCamera(text: String): Boolean {
        val keys = keysOf(text)
        if (keys.isEmpty() || keys.size > 4 || keys.any { it in QUESTION_WORDS }) return false
        return keys.any { it in CLOSE_WORDS }
    }

    private fun keysOf(text: String) = Az.tokenize(text).map { it.key }.filter { it.isNotEmpty() }

    private fun hasNo(keys: List<String>): Boolean =
        keys.any { it in NO } || phrase(keys, "lazim", "deyil") || phrase(keys, "bos", "ver") || phrase(keys, "never", "mind")

    private fun parseSend(tokens: List<Az.Token>, contacts: List<FamilyContact>): Command.SendMessage? {
        val keys = tokens.map { it.key }
        var verb = -1
        for (i in keys.indices) {
            val k = keys[i]
            if (k in SEND_VERBS ||
                (k in SAY_VERBS && keys.getOrNull(i + 1) == "ki") || // "de ki", not the particle "də"
                (k == "at" && keys.getOrNull(i - 1) in MESSAGE_WORDS) // "mesaj at"
            ) {
                verb = i
                break
            }
        }
        val mentionsMessage = keys.any { it in MESSAGE_WORDS }
        if (verb < 0 && !mentionsMessage) return null

        val beforeVerb = if (verb >= 0) verb else tokens.size
        var match = ContactMatcher.findFirst(tokens, contacts, 0, beforeVerb)
        var textRange: IntRange = IntRange.EMPTY
        if (verb >= 0) {
            if (match != null) {
                textRange = (verb + 1) until tokens.size
                // "nəvəmə salam yaz": nothing after the verb, so the message sits between recipient and verb.
                if (messageText(tokens, textRange) == null) textRange = match.end until verb
            } else {
                // "yaz Murada salam"
                match = ContactMatcher.findFirst(tokens, contacts, verb + 1, verb + 3)
                textRange = (match?.end ?: (verb + 1)) until tokens.size
            }
        }
        val phrase = if (match == null && verb > 0) {
            tokens.subList(0, verb)
                .filter { it.key.isNotEmpty() && it.key !in FILLER && it.key !in MESSAGE_WORDS }
                .joinToString(" ") { it.raw }
                .ifBlank { null }
        } else {
            null
        }
        return Command.SendMessage(match?.contacts ?: emptyList(), phrase, messageText(tokens, textRange))
    }

    /** "Əli, ekranı oxu" / "read the screen": a plain request to read it, with no question the AI should answer. */
    fun isBareScreenRequest(text: String): Boolean =
        keysOf(text).all { k ->
            k in FILLER || k in LOOK_VERBS || k.startsWith("ekran") || k.startsWith("oxu") || k.startsWith("eli") ||
                k in setOf("ne", "nedir", "var", "yazilib", "yazilan", "de", "deyin", "soyle", "telefonda", "telefonu") ||
                k.startsWith("screen") || k == "ali" || k in EN_FILLER || k in EN_BARE_SCREEN
        }

    /** Strips command words left around a message by the AI model or a reply: "salam və göndər" → "Salam". */
    fun cleanMessage(text: String): String? {
        val tokens = Az.tokenize(text)
        return messageText(tokens, tokens.indices)
    }

    private fun messageText(tokens: List<Az.Token>, range: IntRange): String? {
        if (range.isEmpty()) return null
        val words = tokens.slice(range).toMutableList()
        fun edge(key: String) = key.isEmpty() || key in MESSAGE_WORDS || isMessenger(key)
        // "salam yaz və göndər": the tail repeats the command. Only "və göndər" is cut, so "de ki pulu göndər" keeps its verb.
        while (words.isNotEmpty()) {
            val last = words.last().key
            when {
                edge(last) || last == "ve" -> {
                    words.removeAt(words.lastIndex)
                    // "hello on WhatsApp": the preposition goes with the app
                    if (isMessenger(last) && words.lastOrNull()?.key in EN_CHANNEL_WORDS) words.removeAt(words.lastIndex)
                }
                last in SEND_VERBS && words.getOrNull(words.lastIndex - 1)?.key == "ve" -> repeat(2) { words.removeAt(words.lastIndex) }
                // English: "hello and send it", "hello on WhatsApp"
                last == "it" && words.getOrNull(words.lastIndex - 1)?.key == "send" &&
                    words.getOrNull(words.lastIndex - 2)?.key == "and" -> repeat(3) { words.removeAt(words.lastIndex) }
                last == "send" && words.getOrNull(words.lastIndex - 1)?.key == "and" -> repeat(2) { words.removeAt(words.lastIndex) }
                else -> break
            }
        }
        while (words.isNotEmpty() && (edge(words.first().key) || words.first().key == "ki" || words.first().key == "ve")) {
            words.removeAt(0)
        }
        if (words.isEmpty()) return null
        val text = words.joinToString(" ") { it.raw }
            .trim()
            .trimStart(':', ',', '-', '–', '—', '"', '«')
            .trimEnd(',', '.', '"', '»') // cloud recognisers add a final period: "salam." → "salam"
            .trim()
        return if (text.isEmpty()) null else Az.capitalizeFirst(text)
    }

    /** "WhatsApp-dan", "vatsapla": the app is a channel, not part of the message. */
    private fun isMessenger(key: String) = MESSENGERS.any { key.startsWith(it) }

    /**
     * "Əzizə səsli mesaj göndər", "WhatsApp üzərindən Əzizə səsli mesaj göndərmək istəyirəm", "nəvəmə voys at": a voice
     * word plus a send or message word. "Səsli zəng" is a call, and "səsi artır" is the volume, so neither counts.
     */
    private fun parseVoiceMessage(tokens: List<Az.Token>, contacts: List<FamilyContact>): Command.VoiceMessage? {
        val keys = tokens.map { it.key }
        val voiceAt = keys.indices.firstOrNull { i -> isVoiceWord(keys[i], keys.getOrNull(i + 1)) } ?: return null
        if (keys.any { it.startsWith("zeng") }) return null
        // "Əzizə yaz ki səsli mesajını aldım": after the verb, the voice words are the text of a message.
        val verbAt = keys.indexOfFirst { it in SEND_VERBS || it.startsWith("gonder") }
        if (verbAt in 0 until voiceAt) return null
        val sends = keys.any { k -> k in SEND_VERBS || k == "at" || k.startsWith("gonder") || k.startsWith("yolla") || k.startsWith("mesaj") }
        if (!sends) return null
        val match = ContactMatcher.findFirst(tokens, contacts, 0, tokens.size)
        val phrase = if (match == null) {
            tokens.filterIndexed { i, t ->
                val k = t.key
                k.isNotEmpty() && !isVoiceWord(k, keys.getOrNull(i + 1)) && !(k.startsWith("mesaj") || k.startsWith("yazi")) &&
                    k !in SEND_VERBS && !k.startsWith("gonder") && !k.startsWith("yolla") && k != "at" && k !in FILLER &&
                    k !in MESSAGE_WORDS && !isMessenger(k) && k !in VOICE_GLUE
            }.joinToString(" ") { it.raw.trim(',', '.', ':') }.ifBlank { null }
        } else {
            null
        }
        return Command.VoiceMessage(match?.contacts ?: emptyList(), phrase)
    }

    /** "səsli", "voys", "voice", "голосовое", or "səs" before "mesajı"/"yazısı". */
    private fun isVoiceWord(key: String, next: String?): Boolean =
        key.startsWith("sesli") || key.startsWith("voys") || key == "voice" || key.startsWith("golosov") || key == "audio" ||
            key == "sesimi" || (key == "ses" && next != null && (next.startsWith("mesaj") || next.startsWith("yazi")))

    private fun parseCall(tokens: List<Az.Token>, contacts: List<FamilyContact>): Command.Call? {
        val keys = tokens.map { it.key }
        var at = -1
        for (i in keys.indices) {
            val k = keys[i]
            if ((k.startsWith("zeng") && keys.getOrNull(i + 1) in CALL_VERBS) || k.startsWith("danis")) {
                at = i
                break
            }
        }
        if (at < 0) return null
        val video = keys.any { it.startsWith("goruntu") || it == "video" }
        val match = ContactMatcher.findFirst(tokens, contacts, 0, at) ?: ContactMatcher.findFirst(tokens, contacts, at + 1)
        val phrase = if (match == null) {
            tokens.subList(0, at)
                .filter { it.key.isNotEmpty() && it.key !in FILLER && !it.key.startsWith("goruntu") && it.key != "video" }
                .joinToString(" ") { it.raw }
                .ifBlank { null }
        } else {
            null
        }
        return Command.Call(match?.contacts ?: emptyList(), phrase, video)
    }

    private fun isCamera(keys: List<String>): Boolean {
        if (keys.any { it.startsWith("oxu") || it == "yazilib" || it == "yazilan" || it.startsWith("kamera") || it == "lupa" }) {
            return true
        }
        val pointsAtSomething = keys.any { it == "bu" || it == "bunun" || it == "buna" || it == "bunu" }
        return pointsAtSomething && keys.any {
            it.startsWith("derman") || it.startsWith("qutu") || it.startsWith("nedir") ||
                it == "ucundur" || it.startsWith("sened") || it.startsWith("qebz") || it in LOOK_VERBS
        }
    }

    // Kept narrow on purpose: "kömək edin, ürəyim ağrıyır" must reach the AI model, which tells them to call 103.
    private fun isHelp(keys: List<String>): Boolean =
        (keys.size <= 2 && keys.any { it.startsWith("komek") }) ||
            phrase(keys, "ne", "ede", "bilirsen") ||
            keys.any { it.startsWith("bacarirsan") }

    private fun phrase(keys: List<String>, vararg words: String): Boolean =
        (0..keys.size - words.size).any { i -> words.indices.all { j -> keys[i + j] == words[j] } }

    private companion object {
        val SEND_VERBS = setOf(
            "yaz", "yazin", "yazarsan", "yazsana", "gonder", "gonderin", "gondersene",
            "yolla", "yollayin", "catdir", "catdirin",
        )
        val SAY_VERBS = setOf("de", "deyin", "desene", "soyle", "soyleyin")
        val VOICE_GLUE = setOf(
            "isteyirem", "isterdim", "gondermek", "atmaq", "yazmaq", "yollamaq", "uzerinden", "vasitesile", "ile", "ucun",
            "eli", "ali", "bir", "zehmet", "olmasa", "ve", "de", "da", "ona",
        )
        val MESSAGE_WORDS = setOf("mesaj", "mesaji", "sms", "smsi", "ismaric", "ismarici")
        val MESSENGERS = listOf("whatsap", "watsap", "vatsap", "votsap", "vatsab", "wotsap")
        val EN_SEND_OR_CALL = setOf("text", "message", "tell", "send", "write", "call", "ring", "phone", "sms")
        val EN_LOOK_WORDS = setOf(
            "what", "whats", "read", "look", "medicine", "pill", "pills", "box", "letter", "bill", "written", "say", "says",
            "expire", "expiry", "dose",
        )
        val EN_FILLER = setOf("please", "the", "a", "my", "me", "can", "could", "you", "now", "for", "to", "it")
        val EN_OPEN_VERBS = setOf("open", "start", "turn", "on", "show", "use")
        val EN_BARE_SCREEN = setOf("read", "what", "whats", "is", "on", "there", "tell", "says", "say", "written", "this")
        val EN_CHANNEL_WORDS = setOf("on", "via", "through", "in", "by", "over")
        val EN_COMPOSE = setOf("send", "text", "tell", "write", "to")
        val UNCLEAR = setOf(
            "ne", "nece", "neyi", "nedir", "bilmirem", "efendim", "bagislayin", "anlamadim", "basa", "dusmedim",
            "what", "huh", "pardon", "sorry", "um", "uh",
        )
        val DECLINE_WORDS = setOf(
            "sag", "sagol", "sagolun", "ol", "olun", "cox", "tesekkur", "tesekkurler", "sonra", "hele",
            "thanks", "thank", "later", "not", "sorry",
        )
        val YES_OBJECTS = setOf("onu", "bunu", "mesaji", "mesaj", "getsin", "this", "that", "message")
        val CANCEL_WORDS = setOf("gonderme", "gondermeyin", "yazma", "yazmayin", "istemirem", "cancel", "stop")
        val EDIT_WORDS = listOf("deyis", "duzelt", "yeniden", "basqa", "change", "edit", "fix", "correct", "redo")
        val EDIT_OBJECTS = setOf("mesaji", "mesaj", "metni", "metn", "sozu", "it", "that", "message", "text", "again", "deyim", "deyin", "cur", "yaz", "et")
        val SAVE_WORDS = listOf("saxla", "elave", "qeyd", "save", "add", "store")
        val CONTACT_WORDS = listOf("nomre", "kontakt", "telefon", "number", "contact", "yadda", "yeni", "new", "phone")
        val NAME_GLUE = setOf("adi", "adla", "adiyla", "kimi", "ile", "as", "for", "is", "et", "edin", "de", "da", "bu", "this")
        val RELATIONS = listOf("nevem", "qizim", "oglum", "anam", "atam", "bacim", "qardasim", "dostum", "yoldasim")
        val LANGUAGE_SWITCH = listOf("dil", "kec", "deyis", "danis", "language", "switch", "change", "speak", "talk")
        val READ_MESSAGE_CUES = setOf("yeni", "son", "gelen", "var", "read", "new", "latest", "unread", "any", "check")
        val ALARM_WORDS = listOf("zenglisaat", "budilnik", "alarm", "xatirlatma", "reminder", "oyadici")
        val ALARM_DELETE = listOf("sil", "legv", "delete", "cancel", "remove", "clear", "turn")
        val ALARM_SET = listOf("qur", "kur", "qoy", "xatirlat", "oyat", "set", "remind", "wake", "make", "add")
        val ALARM_LIST_CUES = setOf("goster", "oxu", "siyahi", "var", "nece", "list", "show", "which", "what", "my", "read")
        val FILLER = setOf("zehmet", "olmasa", "xahis", "edirem", "bir", "ki", "indi", "tez", "ele", "mene", "bele")
        val LOOK_VERBS = setOf("bax", "baxin", "baxsana", "baxarsan")
        val OPEN_VERBS = setOf("ac", "acin", "acsana", "acarsan", "ise", "sal", "salin", "yandir", "yandirin")
        val CLOSE_WORDS = setOf(
            "bagla", "baglayin", "baglasana", "cix", "cixin", "geri", "besdir", "kifayetdir", "bitdi", "yeter",
            "close", "back", "exit", "done", "finish", "finished", "enough", "home",
        )
        val QUESTION_WORDS = setOf(
            "ne", "nece", "hansi", "niye", "olar", "mi", "mu", "kim", "harada", "nedir",
            "what", "whats", "how", "which", "why", "who", "where", "is", "can", "does", "do",
        )
        val CALL_VERBS = setOf(
            "et", "edin", "ele", "eleyin", "elesene", "vur", "vurun", "vursana",
            "etmek", "vurmaq", "edim", "vurum", "etsene",
        )
        val STRONG_YES = setOf(
            "he", "hee", "heee", "ha", "beli", "ok", "okay", "okey", "elbette",
            "duzdur", "dogrudur", "raziyam", "ela", "oldu", "olar", "да",
            "yes", "yeah", "yep", "yup", "sure", "correct", "right", "absolutely", "definitely",
        )
        val YES = STRONG_YES + setOf("yaz", "yazin", "gonder", "gonderin", "duz", "dogru", "razi", "eledir", "send", "go", "ahead")
        val NO = setOf(
            "yox", "yoxx", "xeyr", "yok", "dayan", "dayandir", "legv", "istemirem",
            "gonderme", "gondermeyin", "yazma", "yazmayin", "нет",
            "no", "nope", "cancel", "stop", "dont", "wait",
        )
    }
}
