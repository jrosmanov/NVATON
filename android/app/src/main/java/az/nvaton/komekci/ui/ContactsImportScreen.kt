package az.nvaton.komekci.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import az.nvaton.komekci.MainViewModel
import az.nvaton.komekci.core.Az
import az.nvaton.komekci.core.DialogManager
import az.nvaton.komekci.core.FamilyContact
import az.nvaton.komekci.core.PhoneNumbers
import az.nvaton.komekci.core.tr
import az.nvaton.komekci.data.PhoneContacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adding people from the phone's address book. Asks first (what is read, that nothing leaves the phone), then
 * lists the contacts to tick. A number saved under several names, or one already in the list, gets a question
 * before anything is saved.
 */
@Composable
fun ContactsImportScreen(existing: List<FamilyContact>, vm: MainViewModel) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var allowed by remember { mutableStateOf(PhoneContacts.allowed(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.READ_CONTACTS] == true) vm.setContactsIntegration(true)
        allowed = PhoneContacts.allowed(context)
    }
    var groups by remember { mutableStateOf<List<PhoneContacts.Group>?>(null) }
    LaunchedEffect(allowed) { if (allowed) groups = withContext(Dispatchers.IO) { PhoneContacts.groups(context) } }
    var query by rememberSaveable { mutableStateOf("") }
    val picked = remember { mutableStateMapOf<String, Boolean>() } // by number

    // Questions about duplicates, one at a time, then everything is saved together.
    val queue = remember { mutableStateListOf<PhoneContacts.Group>() }
    val results = remember { mutableStateListOf<Pair<FamilyContact, String?>>() }
    var question by remember { mutableStateOf<Question?>(null) }

    fun owner(contact: FamilyContact) = existing.firstOrNull { PhoneNumbers.same(it.phone, contact.phone) }

    fun consider(contact: FamilyContact) {
        val old = owner(contact)
        when {
            old == null -> results.add(contact to null)
            !old.name.equals(contact.name, ignoreCase = true) -> question = Question.Existing(contact, old)
            // Already there under the same name: keep it, only learn any other names it has in the phone.
            contact.aliases.any { a -> old.aliases.none { it.equals(a, ignoreCase = true) } } ->
                results.add(old.copy(aliases = (old.aliases + contact.aliases).distinctBy { it.lowercase() }) to old.id)
            else -> Unit
        }
    }

    fun advance() {
        while (question == null && queue.isNotEmpty()) {
            val g = queue.removeAt(0)
            if (g.names.size > 1) question = Question.Names(g)
            else consider(FamilyContact(DialogManager.NEW_ID, g.names.first(), "+" + g.number))
        }
        if (question == null && queue.isEmpty()) vm.importContacts(results.toList())
    }

    Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            CircleIconButton(AppIcons.ArrowBack, tr("Geri", "Back"), vm::back)
            Spacer(Modifier.width(14.dp))
            Text(tr("Telefon kontaktları", "Phone contacts"), style = MaterialTheme.typography.headlineSmall)
        }
        when {
            !allowed -> Consent(
                onAllow = {
                    vm.setContactsIntegration(true)
                    ask.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
                    allowed = PhoneContacts.allowed(context)
                },
                onCancel = vm::back,
            )
            groups == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.primary)
            }
            else -> {
                val all = groups.orEmpty()
                val q = Az.fold(query)
                val shown = if (q.isEmpty()) all else all.filter { g -> g.names.any { Az.fold(it).contains(q) } || g.number.contains(query.filter(Char::isDigit).ifEmpty { "-" }) }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(tr("Ad və ya nömrə axtarın", "Search a name or number"), style = MaterialTheme.typography.bodyLarge) },
                    leadingIcon = { Icon(AppIcons.Person, contentDescription = null) },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 60.dp),
                )
                Spacer(Modifier.height(8.dp))
                if (all.isEmpty()) {
                    Text(
                        tr("Telefonda kontakt tapmadım.", "No contacts on this phone."),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                    items(shown, key = { it.number }) { g ->
                        val already = existing.firstOrNull { PhoneNumbers.same(it.phone, g.number) }
                        ImportRow(g, picked[g.number] == true, already) { picked[g.number] = it }
                    }
                }
                val count = picked.count { it.value }
                PrimaryButton(
                    text = if (count == 0) tr("Kontakt seçin", "Pick contacts") else tr("Əlavə et ($count)", "Add ($count)"),
                    onClick = {
                        queue.clear()
                        results.clear()
                        queue.addAll(all.filter { picked[it.number] == true })
                        advance()
                    },
                    enabled = count > 0,
                    icon = AppIcons.Add,
                    height = 68.dp,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                )
            }
        }
    }

    when (val current = question) {
        is Question.Names -> ChoiceDialog(
            title = tr("Eyni nömrə bir neçə adla yazılıb", "One number, several names"),
            text = tr(
                "${PhoneNumbers.spoken(current.group.number)} telefonda belə yazılıb: ${current.group.names.joinToString(", ")}. Hansı adla saxlayım? Digər adlar da tanınacaq.",
                "${PhoneNumbers.spoken(current.group.number)} is saved as: ${current.group.names.joinToString(", ")}. Which name should I use? The others will still be understood.",
            ),
            options = current.group.names + tr("Bunu atla", "Skip this one"),
            onPick = { i ->
                question = null
                val names = current.group.names
                if (i < names.size) consider(FamilyContact(DialogManager.NEW_ID, names[i], "+" + current.group.number, names.filterIndexed { j, _ -> j != i }))
                advance()
            },
        )
        is Question.Existing -> ChoiceDialog(
            title = tr("Bu nömrə artıq var", "This number is already saved"),
            text = tr(
                "${PhoneNumbers.spoken(current.contact.phone)} siyahıda «${current.old.name}» adı ilə var. Necə saxlayım?",
                "${PhoneNumbers.spoken(current.contact.phone)} is already in the list as «${current.old.name}». How should I save it?",
            ),
            options = listOf(
                tr("«${current.old.name}» qalsın", "Keep «${current.old.name}»"),
                tr("«${current.contact.name}» ilə əvəz et", "Replace with «${current.contact.name}»"),
                tr("Adları birləşdir", "Merge the names"),
            ),
            onPick = { i ->
                question = null
                when (i) {
                    1 -> results.add(current.contact.copy(aliases = current.contact.aliases) to current.old.id)
                    2 -> results.add(
                        current.old.copy(
                            aliases = (current.old.aliases + current.contact.name + current.contact.aliases)
                                .filter { !it.equals(current.old.name, ignoreCase = true) },
                        ) to current.old.id,
                    )
                }
                advance()
            },
        )
        null -> Unit
    }
}

private sealed interface Question {
    data class Names(val group: PhoneContacts.Group) : Question
    data class Existing(val contact: FamilyContact, val old: FamilyContact) : Question
}

@Composable
private fun Consent(onAllow: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.padding(20.dp)) {
        AppCard(padding = 24.dp) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            ) { Icon(AppIcons.Group, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.height(16.dp))
            Text(tr("Telefon kontaktlarını əlavə edək?", "Add people from your phone?"), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Text(
                tr(
                    "Köməkçi kontaktlarınızı yalnız telefonda oxuyur, heç yerə göndərmir. Seçdiyiniz adamlara səslə yaza və zəng edə biləcəksiniz, səslə dediyiniz yeni nömrələr isə telefonun kontaktlarına da yazılacaq.",
                    "Köməkçi reads your contacts on the phone only and sends them nowhere. You will be able to message and call the people you pick by voice, and new numbers you say will be saved to the phone's contacts too.",
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(tr("İcazə ver", "Allow"), onAllow, Modifier.fillMaxWidth(), icon = AppIcons.Check)
            Spacer(Modifier.height(10.dp))
            TonalButton(tr("İndi yox", "Not now"), onCancel, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ImportRow(group: PhoneContacts.Group, checked: Boolean, already: FamilyContact?, onCheck: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onCheck(!checked) }
            .heightIn(min = 72.dp)
            .padding(vertical = 6.dp),
    ) {
        Avatar(group.names.first())
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(group.names.joinToString(" · "), style = MaterialTheme.typography.titleMedium)
            Text(PhoneNumbers.spoken(group.number), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            val notes = listOfNotNull(
                if (group.whatsApp) "WhatsApp" else null,
                if (group.names.size > 1) tr("${group.names.size} ad", "${group.names.size} names") else null,
                already?.let { tr("artıq var: ${it.name}", "already saved: ${it.name}") },
            )
            if (notes.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    notes.forEach { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onPrimaryContainer,
                            modifier = Modifier.clip(CircleShape).background(colors.primaryContainer).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onCheck,
            colors = CheckboxDefaults.colors(checkedColor = colors.primary),
            modifier = Modifier.size(48.dp),
        )
    }
    RowDivider()
}

/** A question with big tappable answers (no tiny dialog buttons). */
@Composable
private fun ChoiceDialog(title: String, text: String, options: List<String>, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                options.forEachIndexed { i, option ->
                    if (i == 0) PrimaryButton(option, { onPick(i) }, Modifier.fillMaxWidth(), height = 56.dp)
                    else TonalButton(option, { onPick(i) }, Modifier.fillMaxWidth(), height = 56.dp)
                }
            }
        },
        confirmButton = {},
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface, // so the tonal answers stand out
    )
}
