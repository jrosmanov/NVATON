package az.nvaton.komekci.core

/** What the app should do next. Produced by [DialogManager], carried out by the ViewModel. */
sealed interface Action {
    data class Say(val text: String, val listen: Boolean = false) : Action
    data class SendMessage(val contact: FamilyContact, val text: String) : Action
    data class Call(val contact: FamilyContact, val video: Boolean) : Action
    data class OpenCamera(val question: String?) : Action
    /** Save [contact]; with [replaceId] it takes the place of that contact (a replaced or merged duplicate). */
    data class SaveContact(val contact: FamilyContact, val replaceId: String? = null) : Action
    /** A fast command that needs the phone itself: the time, the battery, the torch, an app… */
    data class Device(val match: QuickMatch) : Action
    /** Say the last answer again. */
    data object Repeat : Action
    /** "«gəlirəm» yox, «gələcəm»": let the AI rewrite the message waiting for confirmation. */
    data class FixDraft(val instruction: String) : Action
    /** Be quiet now. */
    data object Stop : Action
    /** Show the phone's contacts to pick people from (asks first if the integration is off). */
    data object OpenContactImport : Action
    /** Open the WhatsApp chat with this person or group, without writing anything. */
    data class OpenChat(val contact: FamilyContact) : Action
    /** Record a voice message in the app; the user speaks and "Mellon" (or Stop) ends it. */
    data class VoiceMessage(val contact: FamilyContact) : Action
    /** Record again for the same person, after they didn't like the first take. */
    data class ReRecordVoice(val contact: FamilyContact) : Action
    /** Send the voice message just recorded for [contact] (the file is kept in the view model). */
    data class SendVoice(val contact: FamilyContact) : Action
    /** Read the screen the user was on when they called the assistant. */
    data class ReadScreen(val question: String) : Action
    data class SetLanguage(val lang: Lang) : Action
    /** Read the latest incoming messages (from notifications) aloud. */
    data object ReadMessages : Action
    data class SetAlarm(val spec: AlarmSpec) : Action
    data object ListAlarms : Action
    /** Delete these alarms; an empty list with [all] deletes every alarm. */
    data class CancelAlarms(val ids: List<String>, val all: Boolean) : Action
    /** The local rules did not understand; ask the AI model through the backend. */
    data class AskServer(val text: String) : Action
}

/** The AI model's reading of an utterance, as returned by the backend's /api/intent. */
data class ServerIntent(
    val intent: String,
    val contactIds: List<String>,
    val messageText: String,
    val videoCall: Boolean,
    val reply: String,
    /** set_language: "az" or "en". */
    val language: String = "",
    val alarm: AlarmSpec = AlarmSpec(),
    val alarmIds: List<String> = emptyList(),
    /** Who they named when no family contact fits ("Kamran", "Ailə qrupu"): looked up in the phone book and WhatsApp. */
    val recipientName: String = "",
    /** save_contact: the number as digits, and the relation word if they said one ("nəvəm"). */
    val phoneNumber: String = "",
    val contactAlias: String = "",
)

/**
 * The conversation state machine: who to write to, what to write, and the mandatory
 * "Murada «Salam» yazım?" confirmation. Pure Kotlin, so the whole flow is unit-tested.
 */
/**
 * [phoneBook] finds people outside the family list, in the phone's contacts and WhatsApp ("Kamrana yaz"):
 * name or spoken phrase in, matching contacts out. Empty by default so tests stay offline.
 */
class DialogManager(
    private val nlu: LocalNlu = LocalNlu(),
    private val phoneBook: (String) -> List<FamilyContact> = { emptyList() },
) {

    sealed interface Next {
        data class Message(val text: String?) : Next
        data class Call(val video: Boolean) : Next
        data object OpenChat : Next
        data object VoiceMessage : Next
        /** A phone-book person was chosen to be saved. */
        data class Save(val alias: String?) : Next
    }

    sealed interface Pending {
        /** "Call the ambulance, 103?": a call that needs a yes first. */
        data class ConfirmCall(val contact: FamilyContact) : Pending
        data class ConfirmSave(val contact: FamilyContact) : Pending
        data class AskContactName(val number: String, val alias: String?) : Pending
        data class AskContactNumber(val name: String, val alias: String?) : Pending
        /** The number is already saved as [existing]: keep it, replace it, or merge the names. */
        data class Duplicate(val contact: FamilyContact, val existing: FamilyContact) : Pending
        data class Confirm(val contact: FamilyContact, val text: String) : Pending
        /** A voice message was recorded for [contact] and read back ("Göndərim?"): waiting for yes, no or "again". */
        data class ConfirmVoice(val contact: FamilyContact) : Pending
        data class ChooseContact(val candidates: List<FamilyContact>, val next: Next) : Pending
        data class AskText(val contact: FamilyContact) : Pending
        data class AskRecipient(val next: Next) : Pending
    }

    var pending: Pending? = null
        private set
    private var retries = 0

    fun reset() {
        pending = null
        retries = 0
    }

    fun handle(utterance: String, contacts: List<FamilyContact>, assistantName: String): List<Action> {
        val text = stripAssistantName(utterance, assistantName)
        if (text.isBlank()) return listOf(Action.Say(tr("Buyurun, sizi dinləyirəm.", "Yes? I'm listening."), listen = true))
        pending?.let { current -> handlePending(current, text, contacts)?.let { return it } }
        return handleCommand(nlu.parse(text, contacts), text, contacts)
    }

    fun handleServerIntent(result: ServerIntent, contacts: List<FamilyContact>, originalText: String): List<Action> {
        val found = result.contactIds.mapNotNull { id -> contacts.firstOrNull { it.id == id } }.distinct()
        val message = nlu.cleanMessage(result.messageText)
        return when (result.intent) {
            "send_message", "edit_message" -> route(
                found.ifEmpty { outside(result.recipientName, whatsAppSearch = true) },
                Next.Message(message),
                tr("Kontaktlarınızda belə adam tapmadım. Kimə yazım?", "I couldn't find that person in your contacts. Who should I write to?"),
            )
            "call" -> route(
                found.ifEmpty { outside(result.recipientName, whatsAppSearch = false) },
                Next.Call(result.videoCall),
                tr("Kontaktlarınızda belə adam tapmadım. Kimə zəng edim?", "I couldn't find that person in your contacts. Who should I call?"),
            )
            "open_chat" -> route(
                found.ifEmpty { outside(result.recipientName, whatsAppSearch = true) },
                Next.OpenChat,
                tr("Kimin söhbətini açım?", "Whose chat should I open?"),
            )
            "voice_message" -> route(
                found.ifEmpty { outside(result.recipientName, whatsAppSearch = true) },
                Next.VoiceMessage,
                whoQuestion(Next.VoiceMessage),
            )
            "read_camera" -> openCamera(originalText)
            "read_screen" -> listOf(Action.ReadScreen(originalText))
            "read_messages" -> listOf(Action.ReadMessages)
            "list_alarms" -> listOf(Action.ListAlarms)
            "cancel_alarms" -> listOf(Action.CancelAlarms(result.alarmIds, all = result.alarmIds.isEmpty()))
            "set_alarm" ->
                if (result.alarm.isValid) listOf(Action.SetAlarm(result.alarm))
                else listOf(Action.Say(tr("Saat neçəyə qurum?", "For what time?"), listen = true))
            "save_contact" -> saveContact(result.recipientName, result.phoneNumber, result.contactAlias, contacts)
            "import_contacts" -> listOf(Action.OpenContactImport)
            "set_language" -> when (result.language) {
                "en" -> listOf(Action.SetLanguage(Lang.EN))
                "az" -> listOf(Action.SetLanguage(Lang.AZ))
                else -> listOf(Action.Say(NOT_UNDERSTOOD))
            }
            "chat" -> listOf(Action.Say(result.reply.ifBlank { tr("Bağışlayın, cavab tapa bilmədim.", "Sorry, I couldn't find an answer.") }))
            else -> if (result.reply.isNotBlank()) listOf(Action.Say(result.reply, listen = true)) else listOf(Action.Say(NOT_UNDERSTOOD))
        }
    }

    /** Returns null when the utterance should be treated as a fresh command instead. */
    private fun handlePending(p: Pending, text: String, contacts: List<FamilyContact>): List<Action>? {
        // A dictated message may contain "yox" or "don't"; there only a short answer cancels.
        val shortAnswer = text.trim().split(Regex("\\s+")).size <= 2
        val cancels = when (p) {
            is Pending.AskText -> nlu.isNo(text) && shortAnswer
            is Pending.Confirm -> nlu.isCancel(text) // "yox, gələcəm yaz" corrects the message instead
            else -> nlu.isNo(text)
        }
        if (cancels) {
            reset()
            return listOf(
                Action.Say(
                    when (p) {
                        is Pending.Confirm, is Pending.ConfirmVoice -> tr("Yaxşı, göndərmirəm.", "Okay, I won't send it.")
                        is Pending.ConfirmSave, is Pending.Duplicate -> tr("Yaxşı, saxlamıram.", "Okay, I won't save it.")
                        else -> tr("Yaxşı, ləğv etdim.", "Okay, cancelled.")
                    },
                ),
            )
        }
        return when (p) {
            is Pending.Confirm -> when {
                nlu.isEditRequest(text) -> {
                    pending = Pending.AskText(p.contact)
                    retries = 0
                    listOf(Action.Say(tr("Yaxşı. Mesajı yenidən deyin.", "Okay. Say the message again."), listen = true))
                }
                nlu.isYes(text) -> {
                    reset()
                    listOf(Action.SendMessage(p.contact, p.text))
                }
                // "Aysuya göndər" sends it to someone else; a clear new command replaces it. Anything else is a
                // correction for the AI: "yox, gələcəm yaz", "«gəlirəm» yox «gələcəm»", "sonuna sağ ol əlavə et".
                else -> withoutLeadingNo(text).let { rest ->
                    redirect(p, rest, contacts) ?: newCommandOrNull(rest, contacts)
                        ?: if (nlu.isUnclear(text)) reask(askAgain(p)) else listOf(Action.FixDraft(text))
                }
            }

            is Pending.ChooseContact -> {
                val chosen = choose(p.candidates, text)
                if (chosen != null) {
                    reset()
                    proceed(chosen, p.next)
                } else {
                    newCommandOrNull(text, contacts) ?: reask(whichOne(p.candidates, p.next))
                }
            }

            is Pending.AskText -> {
                reset()
                confirm(p.contact, nlu.cleanMessage(text) ?: Az.capitalizeFirst(text.trim()))
            }

            is Pending.ConfirmCall ->
                if (nlu.isYes(text)) {
                    reset()
                    proceed(p.contact, Next.Call(false))
                } else {
                    newCommandOrNull(text, contacts) ?: reask(tr("${p.contact.name} zəng edim? «Hə» və ya «yox» deyin.", "Call ${p.contact.name}? Say yes or no."))
                }

            is Pending.ConfirmVoice -> when {
                nlu.isRedo(text) -> { reset(); listOf(Action.ReRecordVoice(p.contact)) } // "yenidən de", "again"
                nlu.isYes(text) -> { reset(); listOf(Action.SendVoice(p.contact)) }
                else -> newCommandOrNull(text, contacts)
                    ?: reask(tr("${Az.dative(p.contact.name)} səsli mesajı göndərim? «Hə», «yox» və ya «yenidən» deyin.", "Send the voice message to ${p.contact.name}? Say yes, no or again."))
            }

            is Pending.ConfirmSave ->
                if (nlu.isYes(text)) {
                    reset()
                    listOf(Action.SaveContact(p.contact))
                } else {
                    newCommandOrNull(text, contacts)
                        ?: reask(tr("${p.contact.name}, ${PhoneNumbers.spoken(p.contact.phone)}. Yadda saxlayım? «Hə» və ya «yox» deyin.", "${p.contact.name}, ${PhoneNumbers.spoken(p.contact.phone)}. Save it? Say yes or no."))
                }

            is Pending.AskContactName -> {
                val name = Az.capitalizeFirst(text.trim().trim('.', ',', '!'))
                reset()
                askSave(FamilyContact(NEW_ID, name, p.number, listOfNotNull(p.alias)), contacts)
            }

            is Pending.AskContactNumber -> {
                val number = PhoneNumbers.find(text) ?: PhoneNumbers.fromWords(text)
                if (number == null) {
                    reask(tr("Nömrəni rəqəm-rəqəm deyin, məsələn: sıfır əlli, yüz iyirmi üç, qırx beş, altmış yeddi.", "Say the number digit by digit, for example: zero five zero, one two three, four five, six seven."))
                } else {
                    reset()
                    askSave(FamilyContact(NEW_ID, p.name, number, listOfNotNull(p.alias)), contacts)
                }
            }

            is Pending.Duplicate -> when (duplicateAnswer(text)) {
                DuplicateAnswer.KEEP -> {
                    reset()
                    listOf(Action.Say(tr("Yaxşı, «${p.existing.name}» kimi qalır.", "Okay, it stays as «${p.existing.name}».")))
                }
                DuplicateAnswer.REPLACE -> {
                    reset()
                    listOf(Action.SaveContact(p.contact.copy(id = p.existing.id), replaceId = p.existing.id))
                }
                DuplicateAnswer.MERGE -> {
                    reset()
                    val names = (p.existing.aliases + p.contact.name + p.contact.aliases)
                        .filter { !it.equals(p.existing.name, ignoreCase = true) }
                        .distinctBy { it.lowercase() }
                    listOf(Action.SaveContact(p.existing.copy(aliases = names), replaceId = p.existing.id))
                }
                null -> newCommandOrNull(text, contacts) ?: reask(duplicateQuestion(p.contact, p.existing))
            }

            is Pending.AskRecipient -> {
                val found = ContactMatcher.findAll(text, contacts).ifEmpty { phoneBook(text) }
                when {
                    found.size == 1 -> {
                        reset()
                        proceed(found[0], p.next)
                    }
                    found.size > 1 -> askToChoose(found, p.next)
                    else -> newCommandOrNull(text, contacts)
                        ?: reask(tr("Bu adda adam tapmadım. ", "I couldn't find anyone by that name. ") + whoQuestion(p.next))
                }
            }
        }
    }

    private fun newCommandOrNull(text: String, contacts: List<FamilyContact>): List<Action>? {
        val cmd = nlu.parse(text, contacts)
        val isClearCommand = (cmd is Command.SendMessage && cmd.recipients.isNotEmpty()) || cmd is Command.VoiceMessage ||
            cmd is Command.Call || cmd is Command.ReadWithCamera || cmd is Command.ReadScreen ||
            cmd is Command.SetLanguage || cmd == Command.ReadMessages || cmd == Command.ListAlarms
        if (!isClearCommand) return null
        reset()
        return handleCommand(cmd, text, contacts)
    }

    private fun reask(question: String): List<Action> {
        retries++
        if (retries > 2) {
            reset()
            return listOf(Action.Say(tr("Yaxşı, ləğv etdim. Yenidən deyə bilərsiniz.", "Okay, I've cancelled it. You can say it again.")))
        }
        return listOf(Action.Say(question, listen = true))
    }

    private fun choose(candidates: List<FamilyContact>, text: String): FamilyContact? {
        val byName = ContactMatcher.findAll(text, candidates)
        if (byName.size == 1) return byName[0]
        val keys = Az.tokenize(text).map { it.key }
        val index = when {
            keys.any { it.startsWith("birinci") || it == "first" } -> 0
            keys.any { it.startsWith("ikinci") || it == "second" } -> 1
            keys.any { it.startsWith("ucuncu") || it == "third" } -> 2
            keys.any { it.startsWith("sonuncu") || it == "last" } -> candidates.lastIndex
            else -> -1
        }
        return candidates.getOrNull(index)
    }

    private fun handleCommand(cmd: Command, text: String, contacts: List<FamilyContact>): List<Action> = when (cmd) {
        // "Kamrana yaz salam": not family, so try the phone's contacts; still nothing → the AI reads the sentence.
        is Command.SendMessage -> when {
            cmd.recipients.isNotEmpty() || cmd.recipientPhrase == null -> route(cmd.recipients, Next.Message(cmd.text), whoQuestion(Next.Message(null)))
            else -> phoneBook(cmd.recipientPhrase).let { phone ->
                if (phone.isEmpty()) listOf(Action.AskServer(text)) else route(phone, Next.Message(cmd.text), whoQuestion(Next.Message(null)))
            }
        }
        is Command.Call -> when {
            cmd.recipients.isNotEmpty() || cmd.recipientPhrase == null -> route(cmd.recipients, Next.Call(cmd.video), whoQuestion(Next.Call(false)))
            else -> phoneBook(cmd.recipientPhrase).filter { it.phone.isNotBlank() }.let { phone ->
                if (phone.isEmpty()) listOf(Action.AskServer(text)) else route(phone, Next.Call(cmd.video), whoQuestion(Next.Call(false)))
            }
        }
        // "Kamrana səsli mesaj": the phone book handles case endings; a name it doesn't know goes to the AI for a clean name.
        is Command.VoiceMessage -> when {
            cmd.recipients.isNotEmpty() || cmd.recipientPhrase == null -> route(cmd.recipients, Next.VoiceMessage, whoQuestion(Next.VoiceMessage))
            else -> phoneBook(cmd.recipientPhrase).let { phone ->
                if (phone.isEmpty()) listOf(Action.AskServer(text)) else route(phone, Next.VoiceMessage, whoQuestion(Next.VoiceMessage))
            }
        }
        is Command.ReadWithCamera -> openCamera(cmd.question)
        is Command.ReadScreen -> listOf(Action.ReadScreen(cmd.question))
        is Command.SetLanguage -> listOf(Action.SetLanguage(cmd.lang))
        Command.ReadMessages -> listOf(Action.ReadMessages)
        Command.ListAlarms -> listOf(Action.ListAlarms)
        Command.CancelAllAlarms -> listOf(Action.CancelAlarms(emptyList(), all = true))
        is Command.SaveContact -> saveContact(cmd.name, cmd.number, cmd.alias, contacts)
        is Command.Quick -> quick(cmd.match, text, contacts)
        Command.ImportContacts -> listOf(Action.OpenContactImport)
        Command.Help -> listOf(Action.Say(HELP))
        Command.Yes, Command.No -> listOf(Action.Say(tr("Buyurun, nə edim?", "Yes, what can I do for you?"), listen = true))
        is Command.Unknown -> listOf(Action.AskServer(text))
    }

    private fun route(found: List<FamilyContact>, next: Next, notFound: String): List<Action> = when {
        found.isEmpty() -> {
            pending = Pending.AskRecipient(next)
            retries = 0
            listOf(Action.Say(notFound, listen = true))
        }
        found.size > 1 -> askToChoose(found, next)
        else -> proceed(found[0], next)
    }

    /** The message waiting for confirmation, typed or corrected on the screen. */
    fun editDraft(text: String): List<Action> {
        val p = pending as? Pending.Confirm ?: return emptyList()
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        return confirm(p.contact, Az.capitalizeFirst(clean))
    }

    /**
     * What the AI made of their words about the waiting message: the corrected text (and a new recipient, if they
     * named one), something that is not a correction (the question is asked again), or another command altogether.
     */
    fun applyFix(result: ServerIntent, contacts: List<FamilyContact>, instruction: String): List<Action> {
        val p = pending as? Pending.Confirm ?: return emptyList()
        val to = result.contactIds.firstNotNullOfOrNull { id -> contacts.firstOrNull { it.id == id } }
            ?: result.recipientName.takeIf { it.isNotBlank() }?.let { phoneBook(it).singleOrNull() }
            ?: p.contact
        val text = result.messageText.trim()
        return when (result.intent) {
            "edit_message", "send_message" -> when {
                text.isNotEmpty() -> confirm(to, nlu.cleanMessage(text) ?: Az.capitalizeFirst(text))
                to != p.contact -> confirm(to, p.text)
                else -> localFix(instruction)
            }
            // Never the AI's own words here: "Okay, I won't send it" while the message still waits would mislead.
            "chat", "unknown" -> reask(askAgain(p))
            else -> {
                reset()
                handleServerIntent(result, contacts, instruction)
            }
        }
    }

    /**
     * Without the AI: a whole new text said as "yox, axşam gələcəm yaz" or "belə yaz: …" becomes the message, without
     * the "yox" and the command word. Anything else ("gələcəm" for one word, "sonuna sağ ol əlavə et") needs the AI,
     * so they are asked for the whole message again rather than sending their instruction as the message.
     */
    fun localFix(instruction: String): List<Action> {
        val p = pending as? Pending.Confirm ?: return emptyList()
        val words = instruction.trim().split(Regex("\\s+")).map { it.trim(',', '.', '!', ':', ';') }.filter { it.isNotEmpty() }.toMutableList()
        val before = words.size
        while (words.isNotEmpty() && Az.fold(words.first()) in LEADING_NO) words.removeAt(0)
        val saidNo = words.size < before
        val beforeVerb = words.size
        while (words.isNotEmpty() && Az.fold(words.last()) in TRAILING_VERB) words.removeAt(words.lastIndex)
        while (words.isNotEmpty() && Az.fold(words.first()) in TRAILING_VERB + "bele") words.removeAt(0)
        val saidWrite = words.size < beforeVerb
        val instructs = Az.tokenize(instruction).any { t -> EDIT_VERBS.any { t.key.startsWith(it) } }
        if (instructs || !(saidNo || saidWrite)) {
            pending = Pending.AskText(p.contact)
            retries = 0
            return listOf(
                Action.Say(tr("Bunu dəyişə bilmədim. Mesajı əvvəldən deyin.", "I couldn't make that change. Please say the whole message again."), listen = true),
            )
        }
        val text = nlu.cleanMessage(words.joinToString(" ")) ?: return reask(askAgain(p))
        return confirm(p.contact, text)
    }

    /** "Aysuya göndər" while a message waits: the same message to someone else; "Murada göndər" to the same person sends it. */
    private fun redirect(p: Pending.Confirm, text: String, contacts: List<FamilyContact>): List<Action>? {
        val cmd = nlu.parse(text, contacts) as? Command.SendMessage ?: return null
        val to = cmd.recipients.singleOrNull() ?: return null
        if (!cmd.text.isNullOrBlank()) return null
        if (to != p.contact) return confirm(to, p.text)
        reset()
        return listOf(Action.SendMessage(p.contact, p.text))
    }

    private fun withoutLeadingNo(text: String): String =
        text.trim().replace(Regex("^(yox|xeyr|no|nope)[\\s,.!]+", RegexOption.IGNORE_CASE), "").ifBlank { text }

    private fun askAgain(p: Pending.Confirm) = tr(
        "${Az.dative(p.contact.name)} «${p.text}» yazım? «Hə», «yox» və ya «dəyiş» deyin.",
        "Send «${p.text}» to ${p.contact.name}? Say yes, no or change.",
    )

    /** A fast command from the database: most are answered right here, the rest by the phone ([Action.Device]). */
    private fun quick(m: QuickMatch, text: String, contacts: List<FamilyContact>): List<Action> = when (m.intent) {
        QuickIntent.CALL, QuickIntent.VIDEO_CALL ->
            route(person(m.contact.orEmpty(), contacts, needsNumber = true), Next.Call(m.intent == QuickIntent.VIDEO_CALL), whoQuestion(Next.Call(false)))
        QuickIntent.MESSAGE ->
            route(person(m.contact.orEmpty(), contacts, needsNumber = false), Next.Message(m.text?.let { nlu.cleanMessage(it) }), whoQuestion(Next.Message(null)))
        QuickIntent.MESSAGE_ASK -> route(person(m.contact.orEmpty(), contacts, needsNumber = false), Next.Message(null), whoQuestion(Next.Message(null)))
        QuickIntent.OPEN_CHAT -> route(person(m.contact.orEmpty(), contacts, needsNumber = false), Next.OpenChat, whoQuestion(Next.OpenChat))
        QuickIntent.VOICE_MESSAGE -> route(person(m.contact.orEmpty(), contacts, needsNumber = false), Next.VoiceMessage, whoQuestion(Next.VoiceMessage))
        QuickIntent.READ_MESSAGES -> listOf(Action.ReadMessages)
        QuickIntent.READ_SCREEN -> listOf(Action.ReadScreen(text))
        QuickIntent.CAMERA -> openCamera(text)
        QuickIntent.OPEN_CAMERA -> openCamera(null)
        QuickIntent.LIST_ALARMS -> listOf(Action.ListAlarms)
        QuickIntent.CANCEL_ALARMS -> listOf(Action.CancelAlarms(emptyList(), all = true))
        QuickIntent.ALARM_AT, QuickIntent.ALARM_IN, QuickIntent.TIMER, QuickIntent.REMIND_AT, QuickIntent.REMIND_IN,
        QuickIntent.MEDICINE_REMIND,
        -> m.alarm?.let { listOf(Action.SetAlarm(it)) } ?: listOf(Action.Say(tr("Saat neçəyə qurum?", "For what time?"), listen = true))
        QuickIntent.EMERGENCY -> {
            val service = emergency(m.service ?: "emergency")
            pending = Pending.ConfirmCall(service)
            retries = 0
            listOf(Action.Say(tr("${service.name}, ${service.phone}. Zəng edim?", "${service.name}, ${service.phone}. Shall I call?"), listen = true))
        }
        QuickIntent.HELP_ME -> {
            val people = listOf(emergency("ambulance"), emergency("emergency")) + contacts.take(3)
            pending = Pending.ChooseContact(people, Next.Call(false))
            retries = 0
            listOf(
                Action.Say(
                    tr("Buradayam. Kimə zəng edim: ", "I'm here. Who should I call: ") + Az.orList(people.map { it.name }) + "?",
                    listen = true,
                ),
            )
        }
        QuickIntent.HELP -> listOf(Action.Say(HELP))
        QuickIntent.LANGUAGE_AZ -> listOf(Action.SetLanguage(Lang.AZ))
        QuickIntent.THANKS -> listOf(Action.Say(tr("Buyurun, həmişə.", "You're welcome.")))
        QuickIntent.HELLO -> listOf(Action.Say(tr("Salam! Sizə necə kömək edim?", "Hello! How can I help you?"), listen = true))
        QuickIntent.HOW_ARE_YOU -> listOf(Action.Say(tr("Yaxşıyam, sağ olun. Siz necəsiniz?", "I'm well, thank you. How are you feeling today?")))
        QuickIntent.NAME -> listOf(Action.Say(tr("Mən sizin səsli köməkçinizəm.", "I'm your voice assistant.")))
        QuickIntent.REPEAT -> listOf(Action.Repeat)
        QuickIntent.STOP -> listOf(Action.Stop)
        else -> listOf(Action.Device(m)) // the time, the battery, volume, light, apps, medicine notes, speech rate
    }

    /** "my son", "Kamran", "050 123 45 67": the family list, then the phone book, then (for chats) a WhatsApp search. */
    private fun person(said: String, contacts: List<FamilyContact>, needsNumber: Boolean): List<FamilyContact> {
        PhoneNumbers.find(said)?.let { n -> return listOf(FamilyContact("phone:$n", PhoneNumbers.spoken(n), n)) }
        ContactMatcher.findAll(said, contacts).takeIf { it.isNotEmpty() }?.let { return it }
        val name = said.removePrefix("my ").trim()
        phoneBook(name).filter { !needsNumber || it.phone.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { return it }
        return if (needsNumber || name.isBlank()) emptyList() else listOf(FamilyContact.whatsAppSearch(Az.capitalizeFirst(name)))
    }

    /** Azerbaijan's emergency numbers. */
    private fun emergency(service: String): FamilyContact = when (service) {
        "ambulance" -> FamilyContact("sos:103", tr("Təcili yardım", "Ambulance"), "103")
        "police" -> FamilyContact("sos:102", tr("Polis", "Police"), "102")
        "fire" -> FamilyContact("sos:101", tr("Yanğın xidməti", "Fire service"), "101")
        else -> FamilyContact("sos:112", tr("Fövqəladə hallar xidməti", "Emergency services"), "112")
    }

    /**
     * "Kamranın nömrəsini yadda saxla: 050…": asks for what is missing, warns about a number already saved, and
     * reads the number back before saving, because a wrong digit is easy to mishear.
     */
    private fun saveContact(name: String?, number: String?, alias: String?, contacts: List<FamilyContact>): List<Action> {
        val digits = number?.let { PhoneNumbers.find(it) ?: it.filter(Char::isDigit) }?.takeIf { it.count(Char::isDigit) >= 7 }
        val who = name?.trim()?.trim('.', ',')?.takeIf { it.isNotEmpty() }?.let(Az::capitalizeFirst)
        val relation = alias?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            digits == null && who == null ->
                listOf(Action.Say(tr("Kimin nömrəsini saxlayım? Adını və nömrəsini deyin.", "Whose number should I save? Say the name and the number."), listen = true))
            digits == null -> {
                val known = phoneBook(who!!).filter { it.phone.isNotBlank() }
                when {
                    known.size == 1 -> askSave(known[0].copy(id = NEW_ID, aliases = listOfNotNull(relation)), contacts)
                    known.size > 1 -> askToChoose(known, Next.Save(relation))
                    else -> {
                        pending = Pending.AskContactNumber(who, relation)
                        retries = 0
                        listOf(Action.Say(tr("$who üçün nömrəni deyin.", "Tell me $who's number."), listen = true))
                    }
                }
            }
            who == null -> {
                pending = Pending.AskContactName(digits, relation)
                retries = 0
                listOf(Action.Say(tr("${PhoneNumbers.spoken(digits)} nömrəsini hansı adla saxlayım?", "What name should I save ${PhoneNumbers.spoken(digits)} under?"), listen = true))
            }
            else -> askSave(FamilyContact(NEW_ID, who, digits, listOfNotNull(relation)), contacts)
        }
    }

    /** The number already in the list → ask how to keep it; otherwise read it back and confirm. */
    private fun askSave(contact: FamilyContact, contacts: List<FamilyContact>): List<Action> {
        val existing = contacts.firstOrNull { PhoneNumbers.same(it.phone, contact.phone) }
        retries = 0
        if (existing != null && !existing.name.equals(contact.name, ignoreCase = true)) {
            pending = Pending.Duplicate(contact, existing)
            return listOf(Action.Say(duplicateQuestion(contact, existing), listen = true))
        }
        if (existing != null) {
            pending = null
            return listOf(Action.Say(tr("${existing.name} bu nömrə ilə artıq yadda saxlanıb.", "${existing.name} is already saved with this number.")))
        }
        pending = Pending.ConfirmSave(contact)
        return listOf(Action.Say(tr("${contact.name}, ${PhoneNumbers.spoken(contact.phone)}. Yadda saxlayım?", "${contact.name}, ${PhoneNumbers.spoken(contact.phone)}. Shall I save it?"), listen = true))
    }

    private fun duplicateQuestion(contact: FamilyContact, existing: FamilyContact) = tr(
        "Bu nömrə artıq «${existing.name}» adı ilə var. Köhnəni saxlayım, «${contact.name}» ilə əvəz edim, yoxsa adları birləşdirim?",
        "This number is already saved as «${existing.name}». Keep the old one, replace it with «${contact.name}», or merge the names?",
    )

    private enum class DuplicateAnswer { KEEP, REPLACE, MERGE }

    private fun duplicateAnswer(text: String): DuplicateAnswer? {
        val keys = Az.tokenize(text).map { it.key }
        fun has(vararg starts: String) = keys.any { k -> starts.any { k.startsWith(it) } }
        return when {
            has("birles", "ikisi", "merge", "both", "combine") -> DuplicateAnswer.MERGE
            has("evez", "yeni", "deyis", "replace", "new", "change") -> DuplicateAnswer.REPLACE
            has("kohne", "saxla", "qalsin", "keep", "old", "leave") -> DuplicateAnswer.KEEP
            else -> null
        }
    }

    /** Someone outside the family list: the phone's contacts, else (for chats) a WhatsApp search by that name. */
    private fun outside(name: String, whatsAppSearch: Boolean): List<FamilyContact> {
        if (name.isBlank()) return emptyList()
        val phone = phoneBook(name).filter { whatsAppSearch || it.phone.isNotBlank() }
        return when {
            phone.isNotEmpty() -> phone
            whatsAppSearch -> listOf(FamilyContact.whatsAppSearch(name))
            else -> emptyList()
        }
    }

    private fun proceed(contact: FamilyContact, next: Next): List<Action> = when (next) {
        Next.OpenChat -> listOf(Action.OpenChat(contact))
        Next.VoiceMessage -> listOf(Action.VoiceMessage(contact))
        is Next.Save -> askSave(contact.copy(id = NEW_ID, aliases = listOfNotNull(next.alias)), emptyList())
        is Next.Message ->
            if (next.text.isNullOrBlank()) {
                pending = Pending.AskText(contact)
                retries = 0
                listOf(Action.Say(tr("${Az.dative(contact.name)} nə yazım?", "What should I write to ${contact.name}?"), listen = true))
            } else {
                confirm(contact, next.text)
            }
        is Next.Call -> listOf(
            Action.Say(
                if (next.video) {
                    tr(
                        "Görüntülü zəng hələ hazır deyil. ${Az.dative(contact.name)} adi zəng edirəm.",
                        "Video calls aren't ready yet. Calling ${contact.name} with a normal call.",
                    )
                } else {
                    tr("${Az.dative(contact.name)} zəng edirəm.", "Calling ${contact.name}.")
                },
            ),
            Action.Call(contact, next.video),
        )
    }

    /** The voice message is recorded: read it back and wait for yes / no / again. */
    fun reviewVoice(contact: FamilyContact): List<Action> {
        pending = Pending.ConfirmVoice(contact)
        retries = 0
        return listOf(Action.Say(tr("${Az.dative(contact.name)} səsli mesajı göndərim?", "Send the voice message to ${contact.name}?"), listen = true))
    }

    private fun confirm(contact: FamilyContact, text: String): List<Action> {
        pending = Pending.Confirm(contact, text)
        retries = 0
        return listOf(Action.Say(tr("${Az.dative(contact.name)} «$text» yazım?", "Send «$text» to ${contact.name}?"), listen = true))
    }

    private fun askToChoose(candidates: List<FamilyContact>, next: Next): List<Action> {
        pending = Pending.ChooseContact(candidates, next)
        retries = 0
        return listOf(Action.Say(whichOne(candidates, next), listen = true))
    }

    private fun whichOne(candidates: List<FamilyContact>, next: Next): String {
        val question = whoQuestion(next).dropLast(1) // without the "?"
        val names = if (I18n.english) Az.orList(candidates.map { it.name }) else Az.orList(candidates.map { Az.dative(it.name) })
        return "$question: $names?"
    }

    private fun whoQuestion(next: Next): String = when (next) {
        is Next.Call -> tr("Kimə zəng edim?", "Who should I call?")
        Next.OpenChat -> tr("Kimin söhbətini açım?", "Whose chat should I open?")
        Next.VoiceMessage -> tr("Kimə səsli mesaj göndərim?", "Who should get the voice message?")
        is Next.Save -> tr("Hansını saxlayım?", "Which one should I save?")
        is Next.Message -> tr("Kimə yazım?", "Who should I write to?")
    }

    /** A real question ("bu dərman nə üçündür?") is answered from a photo right away; "kameranı aç" just opens it. */
    private fun openCamera(text: String?): List<Action> =
        listOf(Action.OpenCamera(text?.trim()?.takeUnless { it.isEmpty() || nlu.isJustOpenCamera(it) }))

    companion object {
        val NOT_UNDERSTOOD get() = tr("Bağışlayın, başa düşmədim. Bir də deyin.", "Sorry, I didn't understand. Please say it again.")
        val HELP
            get() = tr(
                "Mən mesaj yaza, səsli mesaj göndərə, zəng edə, gələn mesajları oxuya, zəngli saat qura, kamera ilə və ekrandakı yazıları " +
                    "oxuya bilərəm. Məsələn, deyin: «nəvəmə yaz: salam», «sabah səhər 7-də oyat» " +
                    "və ya «mesajlarımı oxu».",
                "I can send messages and voice messages, make calls, read your new messages, set alarms, read things with the camera and " +
                    "on your screen. For example, say: «text my grandson hello», «wake me up " +
                    "at 7 tomorrow» or «read my messages».",
            )
        private val GREETINGS = setOf("salam", "hey", "ay", "ey", "hi", "hello")
        private val LEADING_NO = setOf("yox", "xeyr", "no", "nope", "not")
        private val TRAILING_VERB = setOf("yaz", "yazin", "gonder", "gonderin", "write", "send", "say")
        private val EDIT_VERBS = listOf("elave", "sil", "evez", "add", "remov", "delet", "replac", "instead")
        /** The id of a contact not saved yet; the list gives it a real one. */
        const val NEW_ID = "new"

        /** The tappable answers to [Pending.Duplicate], in the user's language. */
        val DUPLICATE_CHOICES get() = listOf(tr("Köhnəni saxla", "Keep the old one"), tr("Əvəz et", "Replace"), tr("Birləşdir", "Merge names"))

        /** The assistant's name as speech recognition writes it: "Əli", "Eli" or "Ali" (never "Əliyə"). */
        fun isAssistantName(word: String, assistantName: String): Boolean {
            val key = Az.fold(word)
            val name = Az.fold(assistantName)
            return name.isNotEmpty() && (key == name || (name.startsWith("e") && key == "a" + name.drop(1)))
        }

        /** "Əli, nəvəmə yaz" → "nəvəmə yaz". Only an exact name is removed, so "Əliyə zəng et" stays intact. */
        fun stripAssistantName(utterance: String, assistantName: String): String {
            val words = utterance.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            fun isName(word: String) = isAssistantName(word, assistantName)
            val drop = when {
                words.size >= 2 && Az.fold(words[0]) in GREETINGS && isName(words[1]) -> 2
                words.isNotEmpty() && isName(words[0]) -> 1
                else -> 0
            }
            return words.drop(drop).joinToString(" ").trimStart(',', ':', '-', ' ', '!').trim()
        }
    }
}
