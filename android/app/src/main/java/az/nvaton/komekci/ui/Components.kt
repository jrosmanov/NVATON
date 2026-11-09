package az.nvaton.komekci.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import az.nvaton.komekci.core.tr

// The app's few building blocks. Every screen uses these, so there is one card, one button pair, one row.

/** The one card: white on the off-white ground, a hairline edge, large corners. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** A section's name above its card. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** The main action: a teal pill. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 64.dp,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = modifier.heightIn(min = height),
    ) { ButtonContent(text, icon) }
}

/** The second action next to it: a quiet tonal pill. */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 64.dp,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        contentPadding = PaddingValues(horizontal = 24.dp),
        modifier = modifier.heightIn(min = height),
    ) { ButtonContent(text, icon) }
}

@Composable
private fun RowScope.ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A round icon button for top bars: back, settings, the torch. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(container)
            .semantics { contentDescription = label },
    ) { Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(28.dp)) }
}

/**
 * A big round action with its name underneath: the microphone, the camera. While [listening], soft rings spread
 * out from it, so it is obvious from across the room that it is listening.
 */
@Composable
fun RoundAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    size: Dp,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onBackground,
    listening: Boolean = false,
) {
    val ring = container
    val progress = if (listening) {
        rememberInfiniteTransition(label = "rings").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
            label = "ring",
        ).value
    } else {
        0f
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.drawBehind {
                if (listening) {
                    for (offset in listOf(0f, 0.5f)) {
                        val p = (progress + offset) % 1f
                        drawCircle(
                            color = ring.copy(alpha = 0.35f * (1f - p)),
                            radius = this.size.minDimension / 2f * (1f + 0.45f * p),
                            style = Stroke(width = 6.dp.toPx()),
                        )
                    }
                }
            },
        ) {
            Button(
                onClick = onClick,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
                contentPadding = PaddingValues(0.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
                modifier = Modifier
                    .size(size)
                    .semantics { contentDescription = label },
            ) { Icon(icon, contentDescription = null, modifier = Modifier.size(size * 0.42f)) }
        }
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = labelColor, textAlign = TextAlign.Center)
    }
}

/** What the assistant is doing right now, as a small pill. */
@Composable
fun StatusPill(text: String, icon: ImageVector?, tint: Color, modifier: Modifier = Modifier, pulsing: Boolean = false) {
    val alpha = if (pulsing) {
        rememberInfiniteTransition(label = "dot").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "alpha",
        ).value
    } else {
        1f
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint.copy(alpha = alpha), modifier = Modifier.size(22.dp))
        } else {
            Box(Modifier.size(12.dp).clip(CircleShape).background(tint.copy(alpha = alpha)))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Big tappable answers, so nobody has to speak if they'd rather tap. */
@Composable
fun ChoiceButtons(choices: List<String>, onChoice: (String) -> Unit) {
    val yes = tr("Hə", "Yes")
    val no = tr("Yox", "No")
    if (choices == listOf(yes, no)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(yes, { onChoice(yes) }, Modifier.weight(1f), icon = AppIcons.Check, height = 72.dp)
            TonalButton(no, { onChoice(no) }, Modifier.weight(1f), icon = AppIcons.Close, height = 72.dp)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            choices.forEach { choice -> TonalButton(choice, { onChoice(choice) }, Modifier.fillMaxWidth()) }
        }
    }
}

/** Typing instead of talking, for a noisy room. Diacritics are optional: "neveme yaz salam" works. */
@Composable
fun CommandField(onSubmit: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = {
        if (text.isNotBlank()) {
            onSubmit(text)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        placeholder = { Text(tr("nəvəmə yaz: salam", "text my grandson hello"), style = MaterialTheme.typography.bodyLarge) },
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = CircleShape,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        ),
        trailingIcon = {
            IconButton(onClick = submit, modifier = Modifier.semantics { contentDescription = tr("Göndər", "Send") }) {
                Icon(AppIcons.SendFilled, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { submit() }),
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).focusRequester(focus),
    )
}

/** A row inside a card: an icon, a title with an optional line under it, and something on the right. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 6.dp),
    ) {
        when {
            leading != null -> {
                leading()
                Spacer(Modifier.width(16.dp))
            }
            icon != null -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(iconTint.copy(alpha = 0.12f)),
                ) { Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp)) }
                Spacer(Modifier.width(16.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (!supporting.isNullOrBlank()) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A switch row: the whole row toggles. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    supporting: String? = null,
) {
    ListRow(
        title = title,
        icon = icon,
        supporting = supporting,
        modifier = Modifier.semantics { role = Role.Switch },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
            )
        },
    )
}

/** A person's first letter in a tinted circle. */
@Composable
fun Avatar(name: String, size: Dp = 44.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Text(
            name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** A thin line between rows of one card. */
@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
