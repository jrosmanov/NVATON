package az.nvaton.komekci.data

import android.content.Context
import androidx.core.content.edit
import az.nvaton.komekci.core.FamilyContact
import az.nvaton.komekci.core.PhoneNumbers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Family contacts with the words the user calls them by. Stored locally on the phone. */
class ContactsRepo(context: Context) {
    private val prefs = context.getSharedPreferences("contacts", Context.MODE_PRIVATE)
    private val _contacts = MutableStateFlow(load())
    val contacts: StateFlow<List<FamilyContact>> = _contacts

    fun all(): List<FamilyContact> = _contacts.value

    fun add(name: String, phone: String, aliases: List<String>) = put(FamilyContact("", name, phone, aliases))

    /** Saves [contact] as a new entry (it gets its own id), or in place of [replaceId]. */
    fun put(contact: FamilyContact, replaceId: String? = null) {
        val clean = contact.copy(
            id = replaceId ?: UUID.randomUUID().toString().take(8),
            name = contact.name.trim(),
            phone = "+" + PhoneNumbers.international(contact.phone), // "050 123 45 67" → "+994501234567": WhatsApp needs the country code
            aliases = contact.aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() },
        )
        save(if (replaceId != null && all().any { it.id == replaceId }) all().map { if (it.id == replaceId) clean else it } else all() + clean)
    }

    fun remove(id: String) = save(all().filterNot { it.id == id })

    private fun load(): List<FamilyContact> {
        val raw = prefs.getString(KEY, null) ?: return DEMO
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                val aliases = o.optJSONArray("aliases") ?: JSONArray()
                FamilyContact(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    phone = o.getString("phone"),
                    aliases = List(aliases.length()) { j -> aliases.getString(j) },
                )
            }
        }.getOrDefault(DEMO)
    }

    private fun save(list: List<FamilyContact>) {
        val array = JSONArray()
        list.forEach { c ->
            array.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("phone", c.phone)
                    .put("aliases", JSONArray(c.aliases)),
            )
        }
        prefs.edit { putString(KEY, array.toString()) }
        _contacts.value = list
    }

    companion object {
        private const val KEY = "list"

        /** Placeholder numbers (+994 00 ...) belong to no one. Replace them with real numbers before the demo. */
        val DEMO = listOf(
            FamilyContact("murad", "Murad", "+994000000001", listOf("nəvəm")),
            FamilyContact("aysu", "Aysu", "+994000000002", listOf("nəvəm")),
            FamilyContact("leyla", "Leyla", "+994000000003", listOf("qızım")),
            FamilyContact("reshad", "Rəşad", "+994000000004", listOf("oğlum")),
        )
    }
}
