package az.nvaton.komekci.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import az.nvaton.komekci.Draft
import az.nvaton.komekci.VoicePhase
import az.nvaton.komekci.VoiceUi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import android.media.MediaPlayer
import az.nvaton.komekci.Status
import az.nvaton.komekci.UiState
import az.nvaton.komekci.core.tr
import az.nvaton.komekci.wake.WakeState

/**
 * The one screen most people use: what the assistant is doing, the conversation, and three round actions at the
 * bottom (read with the camera, speak, type). The microphone is the biggest thing on the screen.
 */
@Composable
fun HomeScreen(
    state: UiState,
    setupComplete: Boolean,
    onMic: () -> Unit,
    onCamera: () -> Unit,
    onSettings: () -> Unit,
    onChoice: (String) -> Unit,
    onSubmitText: (String) -> Unit,
    onEditDraft: (String) -> Unit = {},
    onEditStart: () -> Unit = {},
    onVoiceStop: () -> Unit = {},
    onVoiceSend: () -> Unit = {},
    onVoiceReRecord: () -> Unit = {},
    onVoiceCancel: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val draft = state.draft
    val voice = state.voice
    // Typing a correction: the keyboard needs the room, so only the message card stays on screen.
    var editing by rememberSaveable(draft) { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(false) }
    val listening = state.status == Status.LISTENING
    // While it talks or prepares an answer, the same button stops it.
    val stoppable = listening || state.speaking || state.status == Status.SPEAKING || state.status == Status.THINKING

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // Who it is, and the way to Settings.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clip(CircleShape).background(colors.primary)) {
                Icon(AppIcons.MicFilled, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(state.assistantName, style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)
                Text(tr("Səsli köməkçi", "Voice assistant"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            CircleIconButton(AppIcons.Settings, tr("Tənzimləmələr", "Settings"), onSettings)
        }

        Spacer(Modifier.height(16.dp))
        StatusLine(state)

        if (!setupComplete && !editing && !typing) { // the keyboard needs the room for the answer
            Spacer(Modifier.height(12.dp))
            Surface(
                color = colors.errorContainer,
                contentColor = colors.onErrorContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onSettings),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Icon(AppIcons.ErrorFilled, contentDescription = null, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        tr("Quraşdırma tamamlanmayıb", "Setup isn't finished"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(AppIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(28.dp))
                }
            }
        }

        // Recording or reviewing a voice message takes over the middle of the screen.
        if (voice != null) {
            VoiceCard(
                voice = voice,
                onStop = onVoiceStop,
                onSend = onVoiceSend,
                onReRecord = onVoiceReRecord,
                onCancel = onVoiceCancel,
                modifier = Modifier.weight(1f).padding(top = 16.dp),
            )
        }

        // The conversation: what you said, small; what it answered, large.
        if (!editing && voice == null) {
            Spacer(Modifier.height(16.dp))
            // With the keyboard up the card is short: bring each new answer into view.
            val conversationScroll = rememberScrollState()
            LaunchedEffect(state.reply, typing) {
                if (typing) {
                    withFrameNanos {} // after the new answer is laid out
                    conversationScroll.animateScrollTo(conversationScroll.maxValue)
                }
            }
            AppCard(modifier = Modifier.weight(1f), padding = 24.dp) {
                Column(Modifier.fillMaxWidth().verticalScroll(conversationScroll)) {
                    val heard = state.partial.ifBlank { state.heard }
                    if (heard.isNotBlank() && state.draft == null) { // the message card below already shows what was understood
                        Text(tr("Siz", "You"), style = MaterialTheme.typography.labelMedium, color = colors.primary)
                        Spacer(Modifier.height(4.dp))
                        Text(heard, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(20.dp))
                        Text(state.assistantName, style = MaterialTheme.typography.labelMedium, color = colors.primary)
                        Spacer(Modifier.height(4.dp))
                    }
                    // With a message on screen to confirm, the question is smaller: the message card below is what matters.
                    Text(
                        state.reply,
                        style = if (state.draft != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                        color = colors.onSurface,
                    )
                    state.localMs?.takeIf { state.draft == null }?.let { ms ->
                        Spacer(Modifier.height(14.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(CircleShape).background(colors.primaryContainer).padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(AppIcons.BoltFilled, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                tr("Telefonda başa düşüldü · $ms ms", "Understood on the phone · $ms ms"),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

        }

        if (voice == null && draft != null) {
            Spacer(Modifier.height(12.dp))
            DraftCard(
                draft = draft,
                editing = editing,
                onEditingChange = {
                    editing = it
                    if (it) onEditStart()
                },
                onSend = { onChoice(tr("Hə", "Yes")) },
                onCancel = { onChoice(tr("Yox", "No")) },
                onSayAgain = { onChoice(tr("dəyiş", "change")) },
                onSave = onEditDraft,
            )
        } else if (voice == null && state.choices.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ChoiceButtons(state.choices, onChoice)
        }
        AnimatedVisibility(visible = typing) {
            CommandField(onSubmit = onSubmitText, modifier = Modifier.padding(top = 12.dp))
        }

        // Read · Speak · Type
        if (!editing && voice == null) {
            Spacer(Modifier.height(20.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RoundAction(
                    icon = AppIcons.PhotoCameraFilled,
                    label = tr("Oxu", "Read"),
                    onClick = onCamera,
                    size = 84.dp,
                    container = colors.secondaryContainer,
                    content = colors.onSecondaryContainer,
                )
                RoundAction(
                    icon = if (stoppable) AppIcons.StopFilled else AppIcons.MicFilled,
                    label = if (stoppable) tr("Dayan", "Stop") else tr("Danış", "Speak"),
                    onClick = onMic,
                    size = 128.dp,
                    container = if (stoppable) colors.error else colors.primary,
                    content = if (stoppable) colors.onError else colors.onPrimary,
                    listening = listening,
                )
                RoundAction(
                    icon = if (typing) AppIcons.Close else AppIcons.Keyboard,
                    label = if (typing) tr("Bağla", "Close") else tr("Yaz", "Type"),
                    onClick = { typing = !typing },
                    size = 84.dp,
                    container = colors.secondaryContainer,
                    content = colors.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun StatusLine(state: UiState) {
    val colors = MaterialTheme.colorScheme
    val name = state.assistantName
    when (state.status) {
        Status.IDLE -> {
            val waiting = state.wake == WakeState.LISTENING
            StatusPill(
                text = if (waiting) tr("Hazıram · «$name» deyin", "Ready · say «$name»") else tr("Hazıram", "Ready"),
                icon = null,
                tint = if (waiting) colors.primary else colors.onSurfaceVariant,
            )
        }
        Status.LISTENING -> StatusPill(tr("Dinləyirəm…", "Listening…"), AppIcons.GraphicEq, colors.error, pulsing = true)
        Status.THINKING -> StatusPill(tr("Düşünürəm…", "Thinking…"), AppIcons.HourglassTop, colors.secondary, pulsing = true)
        Status.SPEAKING -> StatusPill(tr("Danışıram…", "Speaking…"), AppIcons.VolumeUpFilled, colors.primary)
    }
}

/**
 * The message about to be sent, large, so a misheard word is easy to spot. «Göndər» sends it, «Ləğv et» drops it,
 * «Dəyiş» opens it for typing, with the keyboard up and the cursor at the end; «Danış» there says it again instead.
 */
@Composable
private fun DraftCard(
    draft: Draft,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onSayAgain: () -> Unit,
    onSave: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var text by rememberSaveable(draft, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(draft.text, TextRange(draft.text.length)))
    }
    AppCard(padding = 18.dp, color = colors.primaryContainer.copy(alpha = 0.35f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.ChatFilled, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("WhatsApp · ${draft.to}", style = MaterialTheme.typography.labelMedium, color = colors.primary)
        }
        Spacer(Modifier.height(8.dp))
        if (editing) {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            BackHandler { onEditingChange(false) }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.headlineSmall,
                shape = MaterialTheme.shapes.medium,
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) {
                focus.requestFocus()
                keyboard?.show()
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(tr("Saxla", "Save"), {
                    onEditingChange(false)
                    onSave(text.text)
                }, Modifier.weight(1f), icon = AppIcons.Check, height = 60.dp)
                TonalButton(tr("Danış", "Speak"), {
                    onEditingChange(false)
                    onSayAgain()
                }, Modifier.weight(1f), icon = AppIcons.MicFilled, height = 60.dp)
            }
        } else {
            Text(draft.text, style = MaterialTheme.typography.headlineSmall, color = colors.onSurface)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(tr("Göndər", "Send"), onSend, Modifier.weight(1f), icon = AppIcons.SendFilled, height = 64.dp)
                TonalButton(tr("Dəyiş", "Change"), { onEditingChange(true) }, Modifier.weight(1f), icon = AppIcons.Edit, height = 64.dp)
            }
            Spacer(Modifier.height(8.dp))
            TonalButton(tr("Ləğv et", "Cancel"), onCancel, Modifier.fillMaxWidth(), icon = AppIcons.Close, height = 56.dp)
        }
    }
}

/**
 * Recording a WhatsApp voice message, or reviewing the one just recorded. The assistant records it so the stop word
 * «Mellon» works; while recording, a big red circle ends it too. When it's ready: listen, send, record again or drop.
 */
@Composable
private fun VoiceCard(
    voice: VoiceUi,
    onStop: () -> Unit,
    onSend: () -> Unit,
    onReRecord: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    AppCard(modifier = modifier, padding = 20.dp, color = colors.primaryContainer.copy(alpha = 0.35f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.ChatFilled, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("WhatsApp · ${voice.to}", style = MaterialTheme.typography.labelMedium, color = colors.primary)
        }
        when (voice.phase) {
            VoicePhase.RECORDING -> {
                Spacer(Modifier.height(20.dp))
                val minutes = voice.seconds / 60
                val seconds = voice.seconds % 60
                Text(
                    "%d:%02d".format(minutes, seconds),
                    style = MaterialTheme.typography.displaySmall,
                    color = colors.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                // A simple level meter, so it's clear the microphone hears you.
                Box(
                    Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(colors.surfaceVariant),
                ) {
                    Box(Modifier.fillMaxWidth(voice.level.coerceIn(0.03f, 1f)).fillMaxHeight().clip(CircleShape).background(colors.primary))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    tr("Danışın. Qurtaranda «Mellon» deyin.", "Speak. Say «Mellon» when you're done."),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RoundAction(
                        icon = AppIcons.StopFilled,
                        label = tr("Dayan", "Stop"),
                        onClick = onStop,
                        size = 120.dp,
                        container = colors.error,
                        content = colors.onError,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            VoicePhase.REVIEW -> {
                val context = LocalContext.current
                var player by remember { mutableStateOf<MediaPlayer?>(null) }
                DisposableEffect(voice.file) { onDispose { runCatching { player?.release() }; player = null } }
                Spacer(Modifier.height(16.dp))
                Text(
                    tr("Səsli mesaj hazırdır.", "The voice message is ready."),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                TonalButton(
                    tr("Dinlə", "Listen"),
                    {
                        runCatching { player?.release() }
                        player = voice.file?.let { path ->
                            runCatching {
                                MediaPlayer().apply {
                                    setDataSource(path)
                                    setOnCompletionListener { it.release() }
                                    prepare()
                                    start()
                                }
                            }.getOrNull()
                        }
                    },
                    Modifier.fillMaxWidth(),
                    icon = AppIcons.VolumeUpFilled,
                    height = 60.dp,
                )
                Spacer(Modifier.weight(1f))
                PrimaryButton(tr("Göndər", "Send"), onSend, Modifier.fillMaxWidth(), icon = AppIcons.SendFilled, height = 64.dp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    TonalButton(tr("Yenidən", "Again"), onReRecord, Modifier.weight(1f), icon = AppIcons.MicFilled, height = 60.dp)
                    TonalButton(tr("Ləğv et", "Cancel"), onCancel, Modifier.weight(1f), icon = AppIcons.Close, height = 60.dp)
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
