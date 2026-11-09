package az.nvaton.komekci

import android.speech.SpeechRecognizer
import az.nvaton.komekci.actions.PhoneDevice
import az.nvaton.komekci.core.PhoneNumbers
import java.time.ZonedDateTime
import az.nvaton.komekci.notify.Announcer
import az.nvaton.komekci.notify.NotificationReaderService
import az.nvaton.komekci.core.Notices
import az.nvaton.komekci.core.AlarmTime
import az.nvaton.komekci.core.AlarmSpec
import az.nvaton.komekci.alarms.Alarms
import az.nvaton.komekci.alarms.Alarm
import android.os.SystemClock
import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import az.nvaton.komekci.a11y.AgentAccessibilityService
import az.nvaton.komekci.a11y.ScreenReader
import az.nvaton.komekci.actions.Messenger
import az.nvaton.komekci.core.Action
import az.nvaton.komekci.core.Az
import az.nvaton.komekci.core.Command
import az.nvaton.komekci.core.DialogManager
import az.nvaton.komekci.core.FamilyContact
import az.nvaton.komekci.core.LocalNlu
import az.nvaton.komekci.core.tr
import az.nvaton.komekci.core.I18n
import az.nvaton.komekci.core.Lang
import az.nvaton.komekci.data.AppSettings
import az.nvaton.komekci.data.PhoneContacts
import az.nvaton.komekci.net.ApiException
import az.nvaton.komekci.net.Health
import az.nvaton.komekci.net.VisionResult
import az.nvaton.komekci.voice.ServerSpeechInput
import az.nvaton.komekci.voice.VoiceMessageRecorder
import az.nvaton.komekci.voice.Speaker
import az.nvaton.komekci.voice.SpeechInput
import az.nvaton.komekci.wake.WakeEvent
import az.nvaton.komekci.wake.WakeState
import az.nvaton.komekci.wake.WakeWord
import az.nvaton.komekci.wake.WakeWordService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

enum class Status { IDLE, LISTENING, THINKING, SPEAKING }

enum class Screen { HOME, CAMERA, SETTINGS, CONTACTS }

sealed interface VisionUi {
    data object Idle : VisionUi
    data object Loading : VisionUi
    data class Done(val result: VisionResult) : VisionUi
    data class Error(val message: String) : VisionUi
}

/** A message read back for confirmation: who it goes to and what it says. */
data class Draft(val to: String, val text: String)

enum class VoicePhase { RECORDING, REVIEW }

/** A WhatsApp voice message being recorded or waiting to be sent. */
data class VoiceUi(val to: String, val phase: VoicePhase, val seconds: Int = 0, val level: Float = 0f, val file: String? = null)

data class UiState(
    val assistantName: String,
    val screen: Screen = Screen.HOME,
    val status: Status = Status.IDLE,
    val heard: String = "",
    val partial: String = "",
    val reply: String = "",
    /** Tappable answers for the current question (Hə/Yox, or names). */
    val choices: List<String> = emptyList(),
    /** The question the next (or current) photo answers; null means "what is this?". */
    val cameraQuestion: String? = null,
    /** Incremented to make the camera screen take a photo. */
    val captureRequest: Int = 0,
    val vision: VisionUi = VisionUi.Idle,
    val wake: WakeState = WakeState.OFF,
    /** The message waiting for «Hə»: shown on screen, where it can be corrected. */
    val draft: Draft? = null,
    /** A WhatsApp voice message being recorded (or recorded and waiting to be sent); null the rest of the time. */
    val voice: VoiceUi? = null,
    /** Milliseconds the last command took to understand on the phone; null when the AI server answered. */
    val localMs: Long? = null,
    /** Text-to-speech is playing: replies, read-outs and announcements alike. The mic button becomes «Dayan». */
    val speaking: Boolean = false,
    val health: Health? = null,
    val healthChecked: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application), SpeechInput.Listener {
    private val app = application as KomekciApp
    private val nlu = LocalNlu()
    private val dialog = DialogManager(nlu) { phrase -> PhoneContacts.find(app, phrase) }
    private val phoneSpeech = SpeechInput(application, this)
    private val serverSpeech = ServerSpeechInput(app.api, this)
    private val _ui = MutableStateFlow(
        UiState(assistantName = app.settings.assistantName, reply = greeting(app.settings.assistantName, app.settings.wakeWord)),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Alarms set by voice, for the list in Settings. */
    val alarms: StateFlow<List<Alarm>> = Alarms.items.also { Alarms.all(app) }

    val settings: AppSettings get() = app.settings
    val speakerEngine: Speaker.Engine get() = app.speaker.engine

    /** Questions and answers on the current camera screen, so follow-ups ("bəs uşaqlara olar?") make sense. */
    private val visionTurns = ArrayDeque<Pair<String, String>>()

    init {
        viewModelScope.launch {
            AppEvents.events.collect { event ->
                when (event) {
                    is AppEvent.MessageSent -> say(tr("Mesajı ${Az.dative(event.contactName)} göndərdim.", "I sent the message to ${event.contactName}."))
                    is AppEvent.ChatNotFound ->
                        say(tr("WhatsApp-da «${event.name}» söhbətini tapmadım.", "I couldn't find a WhatsApp chat called «${event.name}»."))
                    is AppEvent.ChatOpened -> say(tr("Söhbəti açdım. Oxumaq üçün «Əli, ekranı oxu» deyin.", "The chat is open. Say «Ali, read the screen» to hear it."))
                }
            }
        }
        viewModelScope.launch { WakeWord.state.collect { s -> _ui.update { it.copy(wake = s) } } }
        viewModelScope.launch {
            WakeWord.holds.map { WakeWord.HOLD_TTS in it }.distinctUntilChanged().collect { on -> _ui.update { it.copy(speaking = on) } }
        }
        viewModelScope.launch { WakeWord.pending.filterNotNull().collect { onWake(it) } }
        // The wake word stays quiet while the assistant listens, thinks or talks.
        viewModelScope.launch {
            _ui.map { it.status != Status.IDLE }.distinctUntilChanged().collect { busy ->
                if (busy) WakeWord.hold(WakeWord.HOLD_BUSY) else WakeWord.release(WakeWord.HOLD_BUSY)
            }
        }
        refreshHealth()
    }

    // ---------- Voice and text input ----------

    /** The phone's recognizer said it has no Azerbaijani; switch to the server for the rest of the session. */
    private var phoneSpeechUnsupported = false
    private var listenJob: Job? = null

    private fun useServerSpeech(): Boolean {
        // English: the phone's own recognizer works offline and answers faster than a round trip to the server.
        if (I18n.english && !phoneSpeechUnsupported && SpeechRecognizer.isRecognitionAvailable(app)) return false
        return _ui.value.health?.stt == true && (app.settings.useServerSpeech || phoneSpeechUnsupported)
    }

    private val isListening: Boolean get() = phoneSpeech.isListening || serverSpeech.isListening

    private fun startListening() {
        if (isListening || listenJob?.isActive == true) return
        WakeWord.hold(WakeWord.HOLD_LISTEN)
        listenJob = viewModelScope.launch {
            WakeWord.awaitMicFree() // the wake-word service hands over the microphone
            if (useServerSpeech()) serverSpeech.start() else phoneSpeech.start()
        }
    }

    private fun cancelListening() {
        listenJob?.cancel()
        phoneSpeech.cancel()
        serverSpeech.cancel()
        WakeWord.release(WakeWord.HOLD_LISTEN)
    }

    private fun stopSpeaking() {
        app.speaker.stop()
        _ui.update { s -> if (s.status == Status.SPEAKING) s.copy(status = Status.IDLE) else s }
    }

    /** The big button: «Danış» starts listening; while listening, speaking or thinking it is «Dayan». */
    fun onMicPressed() {
        val s = _ui.value
        when {
            recorder.isRecording -> stopVoiceRecording()
            isListening -> {
                phoneSpeech.stop()
                serverSpeech.stop()
            }
            s.speaking || s.status == Status.SPEAKING || s.status == Status.THINKING -> stopTalking()
            else -> {
                stopSpeaking()
                startListening()
            }
        }
    }

    /** Silence at once: the sentence being spoken, the answer being prepared, queued announcements. */
    fun stopTalking() {
        work?.cancel()
        work = null
        app.speaker.stop()
        Announcer.clear()
        _ui.update {
            it.copy(
                status = Status.IDLE,
                partial = "",
                vision = if (it.vision == VisionUi.Loading) VisionUi.Idle else it.vision,
            )
        }
    }

    /** The server request in flight (an answer, a reading, a photo), so «Dayan» can drop it. */
    private var work: Job? = null

    fun submitText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        cancelListening()
        stopSpeaking()
        process(trimmed)
    }

    override fun onListeningStarted() = _ui.update { it.copy(status = Status.LISTENING, partial = "") }

    override fun onPartial(text: String) = _ui.update { it.copy(partial = text) }

    override fun onResult(text: String) = process(text)

    override fun onSpeechError(message: String, languageUnsupported: Boolean) {
        WakeWord.release(WakeWord.HOLD_LISTEN)
        _ui.update { it.copy(partial = "") }
        if (languageUnsupported && !phoneSpeechUnsupported) {
            phoneSpeechUnsupported = true
            if (_ui.value.health?.stt == true) {
                say(
                    tr(
                        "Telefon Azərbaycan dilini tanımır, serverin nitq tanımasına keçdim. Bir də deyin.",
                        "The phone can't recognise this language, so I switched to the server's speech recognition. Please say it again.",
                    ),
                    listen = true,
                )
                return
            }
        }
        say(message)
    }

    override fun onListeningStopped() {
        WakeWord.release(WakeWord.HOLD_LISTEN)
        _ui.update { s -> if (s.status == Status.LISTENING) s.copy(status = Status.IDLE) else s }
    }

    // ---------- Wake word ("Əli") ----------

    private fun onWake(event: WakeEvent) {
        if (!WakeWord.take(event)) return
        cancelListening()
        stopSpeaking()
        if (_ui.value.screen == Screen.SETTINGS) _ui.update { it.copy(screen = Screen.HOME) }
        val audio = event.audio
        if (audio == null || _ui.value.health?.stt != true) {
            answerWake()
            return
        }
        // "Əli, nəvəmə yaz…" in one breath: the command is already recorded.
        _ui.update { it.copy(status = Status.THINKING, partial = "…", choices = emptyList(), draft = null) }
        work = viewModelScope.launch {
            val text = attempt { app.api.stt(audio) }.getOrNull()?.trim().orEmpty()
            if (text.isBlank()) answerWake() else process(text)
        }
    }

    private fun answerWake() {
        _ui.update { it.copy(heard = "", partial = "") }
        say(tr("Bəli?", "Yes?"), listen = true)
    }

    /** Debug builds: start the voice-message recorder for a test person, without the WhatsApp check. */
    fun debugStartVoice(name: String) = startVoiceRecording(FamilyContact("test:voice", name, "+994500000000"))

    /** Debug builds: pretend the wake word was heard. */
    fun simulateWake() {
        WakeWord.post(null)
    }

    /** Debug builds: stop listening and talking, and forget the open question. */
    fun stopEverything() {
        cancelListening()
        stopSpeaking()
        cancelVoiceRecording()
        dialog.reset()
        _ui.update { it.copy(status = Status.IDLE, partial = "", choices = emptyList(), draft = null) }
    }

    // ---------- Commands ----------

    private fun process(text: String) {
        if (_ui.value.screen == Screen.CAMERA && dialog.pending == null) {
            cameraUtterance(text)
            return
        }
        _ui.update { it.copy(heard = text, partial = "", choices = emptyList(), draft = null, status = Status.THINKING) }
        val started = System.nanoTime()
        val actions = dialog.handle(text, app.contacts.all(), app.settings.assistantName)
        // Understood on the phone, without the AI server: show how fast.
        val local = actions.none { it is Action.AskServer }
        _ui.update { it.copy(localMs = if (local) (System.nanoTime() - started) / 1_000_000 else null) }
        execute(actions)
    }

    private fun execute(actions: List<Action>) {
        for (action in actions) {
            when (action) {
                is Action.Say -> say(action.text, action.listen)
                is Action.SendMessage -> sendMessage(action.contact, action.text)
                is Action.Call -> if (!Messenger.call(app, action.contact)) say(tr("Zəng etmək alınmadı.", "I couldn't make the call."))
                is Action.OpenCamera -> openCameraFor(action.question)
                is Action.AskServer -> askServer(action.text)
                is Action.ReadScreen -> readScreen(action.question)
                is Action.SetLanguage -> setLanguage(action.lang)
                is Action.OpenChat -> openChat(action.contact)
                is Action.VoiceMessage -> voiceMessage(action.contact)
                is Action.ReRecordVoice -> startVoiceRecording(action.contact)
                is Action.SendVoice -> sendVoice(action.contact)
                is Action.SaveContact -> saveContact(action.contact, action.replaceId)
                is Action.Device -> say(PhoneDevice.handle(app, action.match))
                Action.Repeat -> say(_ui.value.reply.ifBlank { tr("Hələ heç nə demədim.", "I haven't said anything yet.") })
                Action.Stop -> stopTalking()
                is Action.FixDraft -> fixDraft(action.instruction)
                Action.OpenContactImport -> openContactImport()
                Action.ReadMessages -> readMessages()
                is Action.SetAlarm -> setAlarm(action.spec)
                Action.ListAlarms -> listAlarms()
                is Action.CancelAlarms -> cancelAlarms(action.ids, action.all)
            }
        }
        refreshChoices()
    }

    private fun refreshChoices() {
        val choices = when (val p = dialog.pending) {
            is DialogManager.Pending.Confirm -> listOf(tr("Hə", "Yes"), tr("Yox", "No"))
            is DialogManager.Pending.ChooseContact -> p.candidates.map { it.name }
            is DialogManager.Pending.AskRecipient -> app.contacts.all().map { it.name }
            is DialogManager.Pending.ConfirmSave, is DialogManager.Pending.ConfirmCall,
            is DialogManager.Pending.ConfirmVoice -> listOf(tr("Hə", "Yes"), tr("Yox", "No"))
            is DialogManager.Pending.Duplicate -> DialogManager.DUPLICATE_CHOICES
            is DialogManager.Pending.AskText, is DialogManager.Pending.AskContactName,
            is DialogManager.Pending.AskContactNumber, null -> emptyList()
        }
        val draft = (dialog.pending as? DialogManager.Pending.Confirm)?.let { Draft(it.contact.name, it.text) }
        _ui.update { it.copy(choices = choices, draft = draft) }
    }

    private fun readScreen(question: String) {
        val shot = ScreenReader.recent()
        when {
            !ScreenReader.available -> say(
                tr(
                    "Ekranı oxumaq üçün Tənzimləmələrdə əlçatımlılıq icazəsini açın: «WhatsApp-da avtomatik Göndər» sətri.",
                    "To read the screen, turn on the accessibility permission in Settings: the «Auto-send on WhatsApp» row.",
                ),
            )
            shot == null -> say(
                tr(
                    "Oxumaq istədiyiniz ekranı açın, sonra «${app.settings.assistantName}, ekranı oxu» deyin. " +
                        "Ekranı sizin səsinizlə çağıranda götürürəm.",
                    "Open the screen you want read, then say «${app.settings.assistantName}, read the screen». " +
                        "I take the screen at the moment you call my name.",
                ),
            )
            else -> {
                _ui.update { it.copy(status = Status.THINKING) }
                app.speaker.speak(tr("Oxuyuram, bir az gözləyin.", "Reading it, one moment please."))
                work = viewModelScope.launch {
                    attempt { app.api.screen(shot.text, shot.app, question.takeUnless { LocalNlu().isBareScreenRequest(it) }) }
                        .onSuccess { speech ->
                            remember("${shot.app}:\n${shot.text}\n\n" + tr("Köməkçi dedi: ", "The assistant said: ") + speech)
                            say(speech.ifBlank { tr("Ekranda oxunacaq mətn tapmadım.", "I found no text to read on the screen.") })
                        }
                        // No server: read the start of the text as it is, which still helps.
                        .onFailure { say(tr("${shot.app} ekranında yazılıb: ", "On the ${shot.app} screen it says: ") + shot.text.lineSequence().take(8).joinToString(". ").take(600)) }
                }
            }
        }
    }

    // ---------- What was just read aloud, for follow-up questions ("nə istəyir?") ----------

    private var followUp: String? = null
    private var followUpAt = 0L

    private fun remember(context: String) {
        followUp = context
        followUpAt = SystemClock.elapsedRealtime()
    }

    private fun recentContext(): String? = followUp?.takeIf { SystemClock.elapsedRealtime() - followUpAt < FOLLOW_UP_MS }

    // ---------- Messages from notifications ----------

    private fun readMessages() {
        val now = System.currentTimeMillis()
        val list = Announcer.unread(now)
        if (list.isEmpty() && !NotificationReaderService.isEnabled(app)) {
            say(tr("Mesajları oxumaq üçün Tənzimləmələrdə bildirişlərə giriş icazəsini açın.", "To read your messages, allow notification access in Settings."))
            return
        }
        Announcer.markRead(now)
        if (list.isNotEmpty()) remember(Notices.asContext(list))
        say(Notices.summary(list, now))
    }

    // ---------- Alarms ----------

    private fun setAlarm(spec: AlarmSpec) {
        val alarm = Alarms.add(app, spec)
        if (alarm == null) {
            say(tr("Saatı başa düşmədim. Məsələn deyin: «sabah səhər 7-də oyat».", "I didn't get the time. For example, say: «wake me up at 7 tomorrow»."))
            return
        }
        val now = ZonedDateTime.now()
        val at = alarm.nextTime(now.zone)
        val whenText = if (spec.inMinutes > 0) AlarmTime.inMinutesPhrase(spec.inMinutes, at) else AlarmTime.describe(at, alarm.repeatDaily, alarm.label, now)
        say(tr("Zəngli saat quruldu: $whenText.", "Alarm set: $whenText."))
    }

    private fun listAlarms() {
        val now = ZonedDateTime.now()
        val list = Alarms.all(app)
        if (list.isEmpty()) {
            say(tr("Heç bir zəngli saat qurulmayıb.", "You have no alarms set."))
            return
        }
        val items = list.joinToString("; ") { AlarmTime.describe(it.nextTime(now.zone), it.repeatDaily, it.label, now) }
        say(if (list.size == 1) tr("Bir zəngli saat var: $items.", "You have one alarm: $items.") else tr("${list.size} zəngli saat var: $items.", "You have ${list.size} alarms: $items."))
    }

    private fun cancelAlarms(ids: List<String>, all: Boolean) {
        val now = ZonedDateTime.now()
        val gone = if (all) Alarms.cancelAll(app) else Alarms.cancel(app, ids)
        when {
            gone.isEmpty() && Alarms.all(app).isEmpty() -> say(tr("Heç bir zəngli saat yoxdur.", "There are no alarms."))
            gone.isEmpty() -> say(tr("Belə zəngli saat tapmadım.", "I couldn't find that alarm."))
            all -> say(tr("Bütün zəngli saatları sildim.", "I deleted all alarms."))
            else -> {
                val items = gone.joinToString("; ") { AlarmTime.describe(it.nextTime(now.zone), it.repeatDaily, it.label, now) }
                say(tr("Sildim: $items.", "Deleted: $items."))
            }
        }
    }

    fun deleteAlarm(id: String) {
        Alarms.cancel(app, listOf(id))
    }

    private fun askServer(text: String) {
        _ui.update { it.copy(status = Status.THINKING) }
        work = viewModelScope.launch {
            attempt { app.api.intent(text, app.contacts.all(), app.settings.assistantName, Alarms.all(app), recentContext()) }
                .onSuccess { result -> execute(dialog.handleServerIntent(result, app.contacts.all(), text)) }
                .onFailure { e -> say(serverErrorMessage(e)) }
        }
    }

    private fun serverErrorMessage(e: Throwable): String = when {
        e is ApiException && e.code == 503 ->
            tr(
                "Süni intellekt hələ qoşulmayıb. Sadə əmrləri başa düşürəm, məsələn: «nəvəmə yaz: salam».",
                "The AI isn't connected yet. I can still answer yes or no, open the camera and read the screen.",
            )
        e is ApiException && e.code == 422 -> tr("Bağışlayın, buna cavab verə bilmirəm.", "Sorry, I can't answer that.")
        e is ApiException && e.code == 401 -> tr("Server tətbiqin açarını qəbul etmədi. Tənzimləmələri yoxlayın.", "The server didn't accept the app's token. Check Settings.")
        e is ApiException && e.code in BUSY_CODES -> BUSY_MESSAGE
        e is ApiException -> tr("Bağışlayın, xəta baş verdi.", "Sorry, something went wrong.")
        e is IOException -> tr(
            "Serverə qoşula bilmədim. Sadə əmrləri yenə də başa düşürəm, məsələn: «nəvəmə yaz: salam».",
            "I couldn't reach the server. Check the internet and the server address in Settings.",
        )
        else -> tr("Bağışlayın, xəta baş verdi.", "Sorry, something went wrong.")
    }

    /** [then] runs after the sentence has been spoken (not if it was interrupted). */
    private fun say(text: String, listen: Boolean = false, then: (() -> Unit)? = null) {
        _ui.update { it.copy(reply = text, status = Status.SPEAKING) }
        app.speaker.speak(text) {
            _ui.update { s -> if (s.status == Status.SPEAKING) s.copy(status = Status.IDLE) else s }
            then?.invoke()
            if (listen && _ui.value.screen != Screen.SETTINGS && hasMicPermission()) {
                // "Done" can come a moment before the speaker has played the last syllable: let it finish first.
                viewModelScope.launch {
                    delay(LISTEN_AFTER_SPEECH_MS)
                    val s = _ui.value
                    if (!s.speaking && s.status == Status.IDLE && !isListening) startListening()
                }
            }
        }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---------- Correcting a message before it is sent ----------

    /** «Dəyiş» on the message card: they are typing now, so the assistant stops listening and talking. */
    fun pauseForTyping() {
        cancelListening()
        stopSpeaking()
        _ui.update { s -> if (s.status == Status.LISTENING) s.copy(status = Status.IDLE, partial = "") else s }
    }

    /** The draft typed on the screen. */
    fun editDraft(text: String) {
        cancelListening()
        stopSpeaking()
        execute(dialog.editDraft(text))
    }

    /** "«gəlirəm» yox, «gələcəm»": the AI rewrites the draft; offline, their words become the new text. */
    private fun fixDraft(instruction: String) {
        val p = dialog.pending as? DialogManager.Pending.Confirm ?: return
        val context = "A WhatsApp message to ${p.contact.name} was read back to the user for confirmation: «${p.text}». " +
            "The user is now correcting it."
        _ui.update { it.copy(status = Status.THINKING) }
        work = viewModelScope.launch {
            attempt { app.api.intent(instruction, app.contacts.all(), app.settings.assistantName, context = context) }
                .onSuccess { execute(dialog.applyFix(it, app.contacts.all(), instruction)) }
                .onFailure { execute(dialog.localFix(instruction)) }
        }
    }

    // ---------- Contacts ----------

    /** Saves to the assistant's list and, with the integration on, to the phone's address book too. */
    private fun saveContact(contact: FamilyContact, replaceId: String?) {
        app.contacts.put(contact, replaceId)
        val toPhone = app.settings.contactsIntegration && PhoneContacts.canWrite(app) &&
            PhoneContacts.namesFor(app, contact.phone).isEmpty() && PhoneContacts.insert(app, contact.name, contact.phone)
        val number = PhoneNumbers.spoken(contact.phone)
        say(
            tr("Yadda saxladım: ${contact.name}, $number.", "Saved: ${contact.name}, $number.") +
                if (toPhone) tr(" Telefonun kontaktlarına da yazdım.", " I added it to the phone's contacts too.") else "",
        )
    }

    fun openContactImport() {
        cancelListening()
        stopSpeaking()
        _ui.update { it.copy(screen = Screen.CONTACTS) }
    }

    /** The user agreed on the import screen or in Settings, and Android granted access. */
    fun setContactsIntegration(on: Boolean) {
        app.settings.contactsIntegration = on
    }

    /** People picked on the import screen, after any duplicate questions were answered there. */
    fun importContacts(picked: List<Pair<FamilyContact, String?>>) {
        picked.forEach { (contact, replaceId) -> app.contacts.put(contact, replaceId) }
        _ui.update { it.copy(screen = Screen.SETTINGS) }
        val n = picked.size
        if (n > 0) say(if (n == 1) tr("Bir kontakt əlavə etdim.", "I added one contact.") else tr("$n kontakt əlavə etdim.", "I added $n contacts."))
    }

    private fun openChat(contact: FamilyContact) {
        if (contact.isWhatsAppSearch && !AgentAccessibilityService.isEnabled(app)) {
            say(tr("WhatsApp-da axtarmaq üçün Tənzimləmələrdə əlçatımlılıq icazəsini açın.", "To search WhatsApp, turn on the accessibility permission in Settings."))
            return
        }
        if (!Messenger.openChat(app, contact)) {
            say(tr("WhatsApp-ı açmaq alınmadı.", "I couldn't open WhatsApp."))
        } else if (!contact.isWhatsAppSearch) {
            say(tr("${contact.name} ilə söhbəti açdım.", "I opened the chat with ${contact.name}."))
        }
    }

    // ---------- WhatsApp voice messages ----------

    private val recorder = VoiceMessageRecorder(app)

    /** The recorded voice message waiting for «Hə», and who it goes to. */
    private var pendingVoice: Pair<FamilyContact, File>? = null

    /**
     * "Əzizə səsli mesaj göndər": the assistant records the message itself, so the stop word «Mellon» can work (while
     * WhatsApp records, Android gives it the microphone and nothing else can hear the word). It says what to do, then
     * records; «Mellon» or the Stop button ends it; then it reads it back and, on «Hə», hands it to WhatsApp to send.
     */
    private fun voiceMessage(contact: FamilyContact) {
        if (Messenger.WHATSAPP_PACKAGES.none { app.packageManager.getLaunchIntentForPackage(it) != null }) {
            say(tr("Səsli mesaj üçün telefonda WhatsApp olmalıdır.", "Voice messages need WhatsApp on this phone."))
            return
        }
        if (!hasMicPermission()) {
            say(tr("Səsli mesaj üçün mikrofon icazəsi lazımdır.", "A voice message needs microphone permission."))
            return
        }
        say(
            tr(
                "${Az.dative(contact.name)} səsli mesaj. İndi danışın, qurtaranda «Mellon» deyin.",
                "A voice message to ${contact.name}. Speak now, and say «Mellon» when you're done.",
            ),
        ) { startVoiceRecording(contact) }
    }

    private fun startVoiceRecording(contact: FamilyContact) {
        cancelListening()
        stopSpeaking()
        pendingVoice = null
        dialog.reset()
        WakeWord.hold(WakeWord.HOLD_VOICE_NOTE) // our recorder owns the microphone; keep the wake word off it
        _ui.update { it.copy(status = Status.IDLE, voice = VoiceUi(contact.name, VoicePhase.RECORDING), choices = emptyList(), draft = null) }
        recorder.start(object : VoiceMessageRecorder.Listener {
            override fun onProgress(level: Float, seconds: Int) {
                _ui.update { s -> s.voice?.takeIf { it.phase == VoicePhase.RECORDING }?.let { s.copy(voice = it.copy(seconds = seconds, level = level)) } ?: s }
            }

            override fun onFinished(file: File?, stoppedByWord: Boolean) {
                WakeWord.release(WakeWord.HOLD_VOICE_NOTE)
                if (file == null) {
                    _ui.update { it.copy(voice = null) }
                    say(tr("Səsinizi yaza bilmədim. Yenidən cəhd edək.", "I couldn't record that. Let's try again."))
                    return
                }
                pendingVoice = contact to file
                _ui.update { it.copy(voice = VoiceUi(contact.name, VoicePhase.REVIEW, file = file.absolutePath)) }
                execute(dialog.reviewVoice(contact))
            }
        })
    }

    /** The big button (or the card's Stop) while recording: keep what was said and move to the review. */
    fun stopVoiceRecording() {
        if (recorder.isRecording) recorder.stop()
    }

    private fun cancelVoiceRecording() {
        recorder.cancel()
        pendingVoice = null
        WakeWord.release(WakeWord.HOLD_VOICE_NOTE)
        _ui.update { it.copy(voice = null) }
    }

    /** «Hə»: hand the recorded message to WhatsApp. The user taps WhatsApp's own send button. */
    private fun sendVoice(contact: FamilyContact) {
        val file = pendingVoice?.takeIf { it.first.id == contact.id }?.second
        pendingVoice = null
        _ui.update { it.copy(voice = null) }
        if (file == null) {
            say(tr("Səsli mesaj hazır deyil.", "The voice message isn't ready."))
            return
        }
        when (Messenger.sendVoice(app, contact, file)) {
            Messenger.Result.WHATSAPP -> _ui.update {
                it.copy(
                    reply = tr("${Az.dative(contact.name)} səsli mesajı WhatsApp-da açdım. Göndər düyməsini basın.", "I opened the voice message to ${contact.name} in WhatsApp. Press the send button."),
                    status = Status.IDLE,
                )
            }
            else -> say(tr("Səsli mesajı göndərmək alınmadı.", "I couldn't send the voice message."))
        }
    }

    /** From the review card: discard and start over, or drop it. */
    fun reRecordVoice() {
        val contact = pendingVoice?.first ?: (dialog.pending as? DialogManager.Pending.ConfirmVoice)?.contact ?: return
        startVoiceRecording(contact)
    }

    fun cancelVoice() {
        dialog.reset()
        cancelVoiceRecording()
        say(tr("Yaxşı, göndərmirəm.", "Okay, I won't send it."))
    }

    fun sendVoiceNow() {
        val contact = (dialog.pending as? DialogManager.Pending.ConfirmVoice)?.contact ?: pendingVoice?.first ?: return
        dialog.reset()
        execute(listOf(Action.SendVoice(contact)))
    }

    private fun sendMessage(contact: FamilyContact, text: String) {
        if (contact.isWhatsAppSearch && !AgentAccessibilityService.isEnabled(app)) {
            say(tr("WhatsApp-da axtarmaq üçün Tənzimləmələrdə əlçatımlılıq icazəsini açın.", "To search WhatsApp, turn on the accessibility permission in Settings."))
            return
        }
        when (Messenger.sendMessage(app, contact, text)) {
            Messenger.Result.WHATSAPP ->
                if (AgentAccessibilityService.isEnabled(app)) {
                    _ui.update { it.copy(reply = tr("${Az.dative(contact.name)} göndərirəm…", "Sending to ${contact.name}…"), status = Status.IDLE) }
                } else {
                    say(tr("Mesajı WhatsApp-da yazdım. Göndər düyməsini basın.", "I wrote the message in WhatsApp. Press the Send button."))
                }
            Messenger.Result.SMS -> say(tr("WhatsApp tapılmadı. SMS-i hazırladım, göndər düyməsini basın.", "WhatsApp isn't installed. I prepared an SMS, press Send."))
            Messenger.Result.FAILED -> say(tr("Mesaj göndərmək alınmadı.", "I couldn't send the message."))
        }
    }

    // ---------- Camera ----------

    /** The camera button: open it and wait for a question or a tap on «Oxu». */
    fun openCamera() {
        cancelListening()
        stopSpeaking()
        dialog.reset()
        openCameraFor(null)
    }

    private fun openCameraFor(question: String?) {
        visionTurns.clear()
        _ui.update { it.copy(screen = Screen.CAMERA, cameraQuestion = question, vision = VisionUi.Idle, choices = emptyList(), draft = null) }
        if (question == null) {
            say(CAMERA_HINT)
            return
        }
        // "Bu dərman nə üçündür?" from the home screen: give them a moment to aim, then take the photo.
        say(tr("Kameranı tutun, şəkil çəkirəm.", "Hold the camera steady, I'm taking a photo.")) {
            viewModelScope.launch {
                delay(AIM_DELAY_MS)
                val s = _ui.value
                if (s.screen == Screen.CAMERA && s.vision == VisionUi.Idle && s.cameraQuestion == question) askAboutView(question)
            }
        }
    }

    /** What was said on the camera screen: a question about the picture, unless it closes the camera or is a clear command. */
    private fun cameraUtterance(text: String) {
        val question = DialogManager.stripAssistantName(text, app.settings.assistantName)
        _ui.update { it.copy(heard = text, partial = "", status = Status.IDLE) }
        when {
            question.isBlank() -> say(tr("Bəli? Sualınızı deyin.", "Yes? Ask your question."), listen = true)
            nlu.isCloseCamera(question) -> back()
            else -> {
                val cmd = nlu.parse(question, app.contacts.all())
                if ((cmd is Command.SendMessage && cmd.recipients.isNotEmpty()) || cmd is Command.Call) {
                    back()
                    process(question)
                } else {
                    askAboutView(question)
                }
            }
        }
    }

    /** The «Oxu» button: read whatever is in view. */
    fun readView() {
        cancelListening()
        stopSpeaking()
        askAboutView(null)
    }

    private fun askAboutView(question: String?) {
        _ui.update { it.copy(cameraQuestion = question, vision = VisionUi.Idle, captureRequest = it.captureRequest + 1) }
    }

    fun analyzeImage(jpeg: ByteArray) {
        val question = _ui.value.cameraQuestion
        val history = visionTurns.toList()
        _ui.update { it.copy(vision = VisionUi.Loading, status = Status.THINKING) }
        app.speaker.speak(tr("Baxıram, bir az gözləyin.", "Let me look, one moment please."))
        work = viewModelScope.launch {
            attempt { app.api.vision(jpeg, question, history) }
                .onSuccess { result ->
                    visionTurns.addLast((question ?: tr("Bu nədir?", "What is this?")) to summary(result))
                    while (visionTurns.size > MAX_VISION_TURNS) visionTurns.removeFirst()
                    _ui.update { it.copy(vision = VisionUi.Done(result)) }
                    say(result.speech)
                }
                .onFailure { e ->
                    val message = visionErrorMessage(e)
                    _ui.update { it.copy(vision = VisionUi.Error(message)) }
                    say(message)
                }
        }
    }

    private fun summary(result: VisionResult): String = buildString {
        append(result.speech)
        result.details.forEach { (label, value) -> append(" $label: $value.") }
    }.take(1500)

    fun captureFailed() {
        val message = tr("Şəkil çəkmək alınmadı. Yenidən cəhd edin.", "I couldn't take the photo. Please try again.")
        _ui.update { it.copy(vision = VisionUi.Error(message)) }
        say(message)
    }

    fun repeatVision() {
        (_ui.value.vision as? VisionUi.Done)?.let { say(it.result.speech) }
    }

    /** A fresh start on the camera screen, for another item. */
    fun retakePhoto() {
        stopSpeaking()
        visionTurns.clear()
        _ui.update { it.copy(vision = VisionUi.Idle, cameraQuestion = null) }
    }

    private fun visionErrorMessage(e: Throwable): String = when {
        e is ApiException && e.code == 503 -> tr("Kamera ilə oxumaq üçün serverə süni intellekt açarı qoşulmalıdır.", "To read with the camera, the server needs an AI key.")
        e is ApiException && e.code == 422 -> tr("Bağışlayın, bu şəkil haqqında danışa bilmirəm.", "Sorry, I can't talk about this photo.")
        e is ApiException && e.code in BUSY_CODES -> BUSY_MESSAGE
        e is ApiException -> tr("Bağışlayın, şəkli oxumaq alınmadı.", "Sorry, I couldn't read the photo.")
        e is IOException -> tr("Serverə qoşula bilmədim. İnterneti və server ünvanını yoxlayın.", "I couldn't reach the server. Check the internet and the server address.")
        else -> tr("Bağışlayın, şəkli oxumaq alınmadı.", "Sorry, I couldn't read the photo.")
    }

    // ---------- Navigation ----------

    fun openSettings() {
        cancelListening()
        _ui.update { it.copy(screen = Screen.SETTINGS) }
        refreshHealth()
    }

    fun back() {
        cancelListening()
        stopSpeaking()
        visionTurns.clear()
        val to = if (_ui.value.screen == Screen.CONTACTS) Screen.SETTINGS else Screen.HOME
        _ui.update { it.copy(screen = to, vision = VisionUi.Idle, cameraQuestion = null) }
    }

    // ---------- Settings ----------

    fun refreshHealth() {
        viewModelScope.launch {
            val health = app.api.health()
            app.speaker.onServerHealth(health)
            _ui.update { it.copy(health = health, healthChecked = true) }
        }
    }

    fun saveServer(url: String, token: String) {
        app.settings.serverUrl = url
        app.settings.appToken = token
        _ui.update { it.copy(healthChecked = false) }
        refreshHealth()
    }

    fun setLanguage(lang: Lang) {
        if (lang == I18n.lang) {
            say(tr("Artıq Azərbaycan dilində danışıram.", "I'm already speaking English."))
            return
        }
        app.settings.language = lang
        I18n.lang = lang
        app.speaker.applyLanguage()
        phoneSpeechUnsupported = false // the phone's recognizer may know this language
        dialog.reset()
        visionTurns.clear()
        val name = app.settings.assistantName
        _ui.update { it.copy(choices = emptyList(), draft = null, heard = "", partial = "") }
        say(tr("Azərbaycan dilinə keçdim.", "I've switched to English.") + " " + greeting(name, app.settings.wakeWord))
        WakeWordService.stop(app) // the notification text is in the old language
        if (app.settings.wakeWord) WakeWordService.start(app)
    }

    fun setAssistantName(name: String) {
        app.settings.assistantName = name
        _ui.update { it.copy(assistantName = app.settings.assistantName) }
    }

    fun setServerVoice(on: Boolean) {
        app.settings.useServerVoice = on
    }

    fun setAnnounceNotifications(on: Boolean) {
        app.settings.announceNotifications = on
    }

    fun setServerSpeech(on: Boolean) {
        app.settings.useServerSpeech = on
    }

    fun setWakeWord(on: Boolean) {
        app.settings.wakeWord = on
        if (on) WakeWordService.start(app) else WakeWordService.stop(app)
    }

    fun addContact(name: String, phone: String, aliases: String) {
        if (name.isBlank() || phone.isBlank()) return
        app.contacts.add(name, phone, aliases.split(','))
    }

    fun removeContact(id: String) = app.contacts.remove(id)

    fun testVoice() = say(tr("Salam! Mən ${app.settings.assistantName}. Səsimi yaxşı eşidirsiniz?", "Hello! I'm ${app.settings.assistantName}. Can you hear me well?"))

    override fun onCleared() {
        phoneSpeech.destroy()
        serverSpeech.cancel()
        WakeWord.release(WakeWord.HOLD_LISTEN)
        WakeWord.release(WakeWord.HOLD_BUSY)
        super.onCleared()
    }

    companion object {
        val CAMERA_HINT get() = tr("Kameranı tutun və sualınızı deyin, ya da «Oxu» düyməsini basın.", "Point the camera and ask your question, or press «Read».")
        private const val AIM_DELAY_MS = 800L
        private const val FOLLOW_UP_MS = 3 * 60_000L
        private const val LISTEN_AFTER_SPEECH_MS = 400L
        private const val MAX_VISION_TURNS = 4

        /** The server is up but the AI behind it is overloaded, rate-limited or briefly failing. */
        private val BUSY_CODES = setOf(429, 502, 504)
        private val BUSY_MESSAGE get() = tr("Süni intellekt hazırda çox məşğuldur. Bir azdan yenidən soruşun.", "The AI is very busy right now. Please ask again in a moment.")

        fun greeting(name: String, wakeWord: Boolean) =
            if (wakeWord) {
                tr(
                    "Salam! Mən sizin köməkçiniz, $name. «$name» deyin və ya mikrofon düyməsini basıb danışın.",
                    "Hello! I'm your assistant, $name. Say «$name» or press the microphone button and speak.",
                )
            } else {
                tr("Salam! Mən sizin köməkçiniz, $name. Mikrofon düyməsini basıb danışın.", "Hello! I'm your assistant, $name. Press the microphone button and speak.")
            }
    }
}

/** runCatching that lets coroutine cancellation through. */
private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
