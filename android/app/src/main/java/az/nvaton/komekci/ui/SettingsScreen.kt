package az.nvaton.komekci.ui

import androidx.compose.foundation.layout.heightIn
import az.nvaton.komekci.core.QuickCommands
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import android.app.NotificationManager
import androidx.compose.runtime.collectAsState
import az.nvaton.komekci.core.AlarmTime
import az.nvaton.komekci.core.tr
import az.nvaton.komekci.core.Lang
import az.nvaton.komekci.core.I18n
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import az.nvaton.komekci.MainViewModel
import az.nvaton.komekci.UiState
import az.nvaton.komekci.a11y.AgentAccessibilityService
import az.nvaton.komekci.core.FamilyContact
import az.nvaton.komekci.notify.NotificationReaderService
import az.nvaton.komekci.voice.Speaker
import az.nvaton.komekci.wake.WakeState

data class SetupStatus(
    val mic: Boolean,
    val camera: Boolean,
    val call: Boolean,
    val contacts: Boolean = true,
    val notifications: Boolean,
    val listener: Boolean,
    val accessibility: Boolean,
    /** Android 14+: the ringing alarm may cover the screen (otherwise it is only a banner). */
    val fullScreenAlarm: Boolean = true,
) {
    /** What the three demo scenarios need. */
    val complete: Boolean get() = mic && notifications && listener && accessibility
}

fun setupStatus(context: Context): SetupStatus {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return SetupStatus(
        mic = granted(Manifest.permission.RECORD_AUDIO),
        camera = granted(Manifest.permission.CAMERA),
        call = granted(Manifest.permission.CALL_PHONE),
        contacts = granted(Manifest.permission.READ_CONTACTS),
        notifications = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
        listener = NotificationReaderService.isEnabled(context),
        accessibility = AgentAccessibilityService.isEnabled(context),
        fullScreenAlarm = Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
    )
}

/** Increments every time the screen resumes, e.g. after the user comes back from system settings. */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}

@Composable
fun SettingsScreen(state: UiState, contacts: List<FamilyContact>, setup: SetupStatus, vm: MainViewModel) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    var serverUrl by rememberSaveable { mutableStateOf(vm.settings.serverUrl) }
    var token by rememberSaveable { mutableStateOf(vm.settings.appToken) }
    var name by rememberSaveable { mutableStateOf(state.assistantName) }
    var serverVoice by remember { mutableStateOf(vm.settings.useServerVoice) }
    var announceOn by remember { mutableStateOf(vm.settings.announceNotifications) }
    val alarms by vm.alarms.collectAsState()
    var serverSpeech by remember { mutableStateOf(vm.settings.useServerSpeech) }
    var wakeOn by remember { mutableStateOf(vm.settings.wakeWord) }
    var integrationOn by remember { mutableStateOf(vm.settings.contactsIntegration) }
    var askIntegration by remember { mutableStateOf(false) }
    val contactsAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.READ_CONTACTS] == true
        vm.setContactsIntegration(granted)
        integrationOn = granted
    }

    fun open(action: String, withPackage: Boolean = false) = runCatching {
        val intent = Intent(action)
        if (withPackage) intent.data = Uri.fromParts("package", context.packageName, null)
        context.startActivity(intent)
    }

    val steps = buildList {
        add(SetupStep(AppIcons.MicFilled, tr("Mikrofon", "Microphone"), setup.mic) { permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) })
        add(SetupStep(AppIcons.PhotoCameraFilled, tr("Kamera", "Camera"), setup.camera) { permissions.launch(arrayOf(Manifest.permission.CAMERA)) })
        add(SetupStep(AppIcons.CallFilled, tr("Zəng etmək", "Phone calls"), setup.call) { permissions.launch(arrayOf(Manifest.permission.CALL_PHONE)) })
        add(SetupStep(AppIcons.Group, tr("Kontaktlar: istənilən adama yazmaq", "Contacts: message anyone"), setup.contacts) { permissions.launch(arrayOf(Manifest.permission.READ_CONTACTS)) })
        if (Build.VERSION.SDK_INT >= 33) {
            add(SetupStep(AppIcons.NotificationsFilled, tr("Bildiriş göstərmək", "Show notifications"), setup.notifications) { permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) })
        }
        add(SetupStep(AppIcons.NotificationsFilled, tr("Gələn mesajları oxumaq", "Read incoming messages"), setup.listener) { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) })
        add(SetupStep(AppIcons.Chat, tr("WhatsApp-da yazmaq, ekranı oxumaq", "Write on WhatsApp, read the screen"), setup.accessibility) { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
        add(SetupStep(AppIcons.AlarmFilled, tr("Zəngli saat bütün ekranda", "Alarms on the full screen"), setup.fullScreenAlarm) {
            if (Build.VERSION.SDK_INT >= 34) open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, withPackage = true)
        })
    }

    Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            CircleIconButton(AppIcons.ArrowBack, tr("Geri", "Back"), vm::back)
            Spacer(Modifier.width(14.dp))
            Text(tr("Tənzimləmələr", "Settings"), style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // Always in both languages, so someone who can't read the current one still finds it.
            SectionHeader("Dil / Language")
            LanguageSwitch(I18n.lang) { vm.setLanguage(it) }

            val done = steps.count { it.ok }
            SectionHeader(tr("Quraşdırma · $done / ${steps.size}", "Setup · $done of ${steps.size}"))
            AppCard(padding = 16.dp) {
                steps.forEachIndexed { i, step ->
                    if (i > 0) RowDivider()
                    ListRow(
                        title = step.title,
                        icon = step.icon,
                        iconTint = if (step.ok) colors.primary else colors.error,
                        supporting = if (step.ok) tr("Hazırdır", "Ready") else tr("Hələ icazə yoxdur", "Not allowed yet"),
                        trailing = {
                            if (step.ok) {
                                Icon(AppIcons.TaskAlt, contentDescription = tr("Hazırdır", "Ready"), tint = colors.primary, modifier = Modifier.size(28.dp))
                            } else {
                                TonalButton(tr("Aç", "Open"), step.fix, height = 48.dp)
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    tr(
                        "Açar boz görünürsə (Android 13+): Tətbiq haqqında → ⋮ → «Məhdud parametrlərə icazə ver», sonra yenidən cəhd edin.",
                        "If a switch is greyed out (Android 13+): App info → ⋮ → «Allow restricted settings», then try again.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                TonalButton(tr("Tətbiq haqqında", "App info"), { open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true) }, icon = AppIcons.Settings, height = 52.dp)
            }

            SectionHeader(tr("Köməkçi", "Assistant"))
            AppCard(padding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text(tr("Köməkçinin adı", "Assistant's name")) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    PrimaryButton(tr("Saxla", "Save"), { vm.setAssistantName(name) }, height = 56.dp)
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    tr("«${state.assistantName}» deyəndə özü işə düşsün", "Start when I say «${state.assistantName}»"),
                    wakeOn,
                    {
                        wakeOn = it
                        vm.setWakeWord(it)
                    },
                    icon = AppIcons.MicFilled,
                    supporting = wakeText(state.wake, wakeOn),
                )
                Note(
                    tr(
                        "Ad telefonun içində, internetsiz tanınır. Səs serverə yalnız «${state.assistantName}» deyəndən sonra gedir.",
                        "The name is recognised on the phone, offline. Audio goes to the server only after «${state.assistantName}».",
                    ),
                )
                RowDivider()
                SwitchRow(
                    tr("Gələn mesajları səsli oxu", "Read incoming messages aloud"),
                    announceOn,
                    {
                        announceOn = it
                        vm.setAnnounceNotifications(it)
                    },
                    icon = AppIcons.NotificationsFilled,
                    supporting = tr("Mesajlar, buraxılmış zənglər, xatırlatmalar", "Messages, missed calls, reminders"),
                )
                RowDivider()
                SwitchRow(
                    tr("Serverin nitq tanıması", "Server speech recognition"),
                    serverSpeech,
                    {
                        serverSpeech = it
                        vm.setServerSpeech(it)
                    },
                    icon = AppIcons.GraphicEq,
                    supporting = tr("Gemini və ya Azure: Azərbaycan dilini daha yaxşı başa düşür", "Gemini or Azure; off uses the phone's recognizer"),
                )
                RowDivider()
                SwitchRow(
                    tr("Serverin Azərbaycan səsi", "Server's Azerbaijani voice"),
                    serverVoice,
                    {
                        serverVoice = it
                        vm.setServerVoice(it)
                    },
                    icon = AppIcons.VolumeUpFilled,
                    supporting = tr("Azure · telefonun səsi: ", "Azure · phone's voice: ") + engineText(vm.speakerEngine),
                )
            }

            QuickCommands.english?.let { db ->
                var showList by remember { mutableStateOf(false) }
                SectionHeader(tr("Sürətli əmrlər", "Fast commands"))
                AppCard(padding = 16.dp) {
                    ListRow(
                        title = tr("${db.entries.size} əmr telefonda başa düşülür", "${db.entries.size} commands understood on the phone"),
                        icon = AppIcons.BoltFilled,
                        supporting = tr("İngilis dilində: serversiz, internetsiz, bir anda", "In English: no server, no internet, instant"),
                    )
                    Spacer(Modifier.height(8.dp))
                    TonalButton(tr("Siyahını göstər", "Show the list"), { showList = true }, Modifier.fillMaxWidth(), height = 52.dp)
                }
                if (showList) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showList = false },
                        title = { Text(tr("Sürətli əmrlər", "Fast commands"), style = MaterialTheme.typography.headlineSmall) },
                        text = {
                            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 480.dp)) {
                                db.entries.groupBy { it.category }.forEach { (category, list) ->
                                    item {
                                        Text(
                                            category.replaceFirstChar { it.uppercase() },
                                            style = MaterialTheme.typography.titleMedium,
                                            color = colors.primary,
                                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                        )
                                    }
                                    list.forEach { e -> item { Text("“" + e.pattern + "”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) } }
                                }
                            }
                        },
                        confirmButton = { PrimaryButton(tr("Bağla", "Close"), { showList = false }, height = 52.dp) },
                        shape = MaterialTheme.shapes.large,
                        containerColor = colors.surface,
                    )
                }
            }

            SectionHeader(tr("Zəngli saatlar", "Alarms"))
            AppCard(padding = 16.dp) {
                if (alarms.isEmpty()) {
                    ListRow(
                        title = tr("Hələ yoxdur", "None yet"),
                        icon = AppIcons.Alarm,
                        supporting = tr("Deyin: «sabah səhər 7-də oyat»", "Say: «wake me up at 7 tomorrow»"),
                    )
                }
                val now = java.time.ZonedDateTime.now()
                alarms.forEachIndexed { i, alarm ->
                    if (i > 0) RowDivider()
                    ListRow(
                        title = AlarmTime.clock(alarm.nextTime(now.zone)) + if (alarm.label.isBlank()) "" else " · ${alarm.label}",
                        icon = AppIcons.AlarmFilled,
                        supporting = AlarmTime.describe(alarm.nextTime(now.zone), alarm.repeatDaily, "", now),
                        trailing = { DeleteButton(tr("Zəngli saatı sil", "Delete alarm")) { vm.deleteAlarm(alarm.id) } },
                    )
                }
            }

            SectionHeader(tr("Ailə kontaktları", "Family contacts"))
            AppCard(padding = 16.dp) {
                SwitchRow(
                    tr("Telefon kontaktları ilə inteqrasiya", "Use the phone's contacts"),
                    integrationOn,
                    { on ->
                        if (on) askIntegration = true
                        else {
                            integrationOn = false
                            vm.setContactsIntegration(false)
                        }
                    },
                    icon = AppIcons.Group,
                    supporting = tr("Telefondakı adamları əlavə etmək, istənilən adama yazmaq, yeni nömrələri telefona da yazmaq", "Add people from the phone, message anyone, save new numbers to the phone"),
                )
                if (integrationOn) {
                    TonalButton(tr("Telefondan əlavə et", "Add from the phone"), vm::openContactImport, Modifier.fillMaxWidth(), icon = AppIcons.Group, height = 56.dp)
                    Spacer(Modifier.height(8.dp))
                }
                RowDivider()
                contacts.forEachIndexed { i, contact ->
                    if (i > 0) RowDivider()
                    ListRow(
                        title = contact.name,
                        leading = { Avatar(contact.name) },
                        supporting = listOf(contact.phone, contact.aliases.joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · "),
                        trailing = { DeleteButton(tr("Kontaktı sil", "Delete contact")) { vm.removeContact(contact.id) } },
                    )
                }
                if (contacts.isNotEmpty()) Spacer(Modifier.height(8.dp))
                AddContactForm(onAdd = vm::addContact)
            }

            SectionHeader("Server")
            AppCard(padding = 16.dp) {
                val (ok, text) = healthLine(state)
                ListRow(
                    title = when (ok) {
                        true -> tr("Server işləyir", "Server is running")
                        false -> tr("Serverə qoşulmaq olmur", "Can't reach the server")
                        null -> tr("Yoxlanılır…", "Checking…")
                    },
                    icon = AppIcons.Dns,
                    iconTint = if (ok == false) colors.error else colors.primary,
                    supporting = text,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    serverUrl,
                    { serverUrl = it },
                    label = { Text(tr("Server ünvanı", "Server address")) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    token,
                    { token = it },
                    label = { Text(tr("APP_TOKEN (istəyə bağlı)", "APP_TOKEN (optional)")) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                PrimaryButton(tr("Yadda saxla və yoxla", "Save and test"), { vm.saveServer(serverUrl, token) }, Modifier.fillMaxWidth(), icon = AppIcons.Check)
            }

            SectionHeader(tr("Yoxlama", "Tests"))
            AppCard(padding = 16.dp) {
                TonalButton(tr("Səsi yoxla", "Test the voice"), vm::testVoice, Modifier.fillMaxWidth(), icon = AppIcons.VolumeUp)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (askIntegration) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askIntegration = false },
            title = { Text(tr("Telefon kontaktlarından istifadə edək?", "Use your phone's contacts?"), style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    tr(
                        "Köməkçi kontaktlarınızı yalnız telefonda oxuyacaq, heç yerə göndərməyəcək. Telefondakı adamları siyahıya əlavə edə, istənilən adama səslə yaza biləcəksiniz. Səslə dediyiniz yeni nömrələr telefonun kontaktlarına da yazılacaq.",
                        "Köməkçi will read your contacts on the phone only and send them nowhere. You can add people from the phone and message anyone by voice. New numbers you say will be saved to the phone's contacts too.",
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                PrimaryButton(tr("İcazə ver", "Allow"), {
                    askIntegration = false
                    vm.setContactsIntegration(true)
                    integrationOn = true
                    contactsAccess.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
                }, height = 52.dp)
            },
            dismissButton = { TonalButton(tr("İndi yox", "Not now"), { askIntegration = false }, height = 52.dp) },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

private class SetupStep(val icon: ImageVector, val title: String, val ok: Boolean, val fix: () -> Unit)

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 60.dp, bottom = 10.dp),
    )
}

@Composable
private fun DeleteButton(label: String, onClick: () -> Unit) {
    CircleIconButton(AppIcons.Delete, label, onClick, container = Color.Transparent, content = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Two big halves; the chosen one is filled. */
@Composable
private fun LanguageSwitch(current: Lang, onPick: (Lang) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colors.surfaceVariant)
            .padding(6.dp),
    ) {
        listOf(Lang.AZ to "Azərbaycanca", Lang.EN to "English").forEach { (lang, label) ->
            val selected = lang == current
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.primary else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(lang) }),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimary else colors.onSurface)
            }
        }
    }
}

@Composable
private fun AddContactForm(onAdd: (String, String, String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var aliases by rememberSaveable { mutableStateOf("") }
    if (!open) {
        TonalButton(tr("Kontakt əlavə et", "Add a contact"), { open = true }, Modifier.fillMaxWidth(), icon = AppIcons.Add, height = 56.dp)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val field = @Composable { value: String, onChange: (String) -> Unit, label: String, type: KeyboardType ->
            OutlinedTextField(
                value,
                onChange,
                label = { Text(label) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = type),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        field(name, { name = it }, tr("Ad (məs. Murad)", "Name (e.g. Murad)"), KeyboardType.Text)
        field(phone, { phone = it }, tr("Telefon (+994...)", "Phone (+994...)"), KeyboardType.Phone)
        field(aliases, { aliases = it }, tr("Necə çağırırsınız (vergüllə: nəvəm, balaca)", "What you call them (comma-separated: grandson, nəvəm)"), KeyboardType.Text)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TonalButton(tr("Ləğv et", "Cancel"), { open = false }, Modifier.weight(1f), height = 56.dp)
            PrimaryButton(
                tr("Əlavə et", "Add"),
                {
                    onAdd(name, phone, aliases)
                    name = ""
                    phone = ""
                    aliases = ""
                    open = false
                },
                Modifier.weight(1f),
                enabled = name.isNotBlank() && phone.isNotBlank(),
                height = 56.dp,
            )
        }
    }
}

/** null while checking; the line under it says what works. */
private fun healthLine(state: UiState): Pair<Boolean?, String> {
    if (!state.healthChecked) return null to ""
    val health = state.health ?: return false to tr("USB ilə: adb reverse tcp:8787 tcp:8787", "Over USB: adb reverse tcp:8787 tcp:8787")
    val provider = health.provider.replaceFirstChar { it.uppercase() }.ifBlank { "AI" }
    val ai = if (health.ai) tr("hazır", "ready") else tr("açar yoxdur", "no key")
    val voice = if (health.tts && !I18n.english) "Azure" else tr("telefonun səsi", "phone's voice")
    val listening = if (health.stt) tr("serverdə", "on the server") else tr("yalnız telefon", "phone only")
    return true to tr(
        "Süni intellekt ($provider): $ai · Səs: $voice · Nitq tanıma: $listening",
        "AI ($provider): $ai · Voice: $voice · Speech recognition: $listening",
    )
}

private fun wakeText(state: WakeState, enabled: Boolean): String = when {
    !enabled -> tr("Söndürülüb: mikrofon düyməsi ilə danışın", "Off: press the microphone button to speak")
    state == WakeState.LISTENING || state == WakeState.PAUSED -> tr("Adını gözləyir", "Waiting for its name")
    state == WakeState.LOADING -> tr("Hazırlanır…", "Getting ready…")
    state == WakeState.NO_MODEL -> tr("Model tətbiqə daxil edilməyib (scripts/get-wake-model.sh)", "The model isn't in the app (scripts/get-wake-model.sh)")
    state == WakeState.ERROR -> tr("Mikrofonu açmaq alınmadı: icazəni yoxlayın", "Couldn't open the microphone: check the permission")
    else -> tr("İşləmir: mikrofon icazəsini verin və tətbiqi yenidən açın", "Not working: allow the microphone and reopen the app")
}

private fun engineText(engine: Speaker.Engine) = when (engine) {
    Speaker.Engine.AZERBAIJANI -> tr("Azərbaycan dili", "Azerbaijani")
    Speaker.Engine.TURKISH_APPROX -> tr("türk səsi", "Turkish voice")
    Speaker.Engine.ENGLISH -> tr("ingilis dili", "English")
    Speaker.Engine.DEFAULT -> tr("standart", "default")
    Speaker.Engine.NONE -> tr("yoxdur", "none")
}
