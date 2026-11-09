package az.nvaton.komekci.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import az.nvaton.komekci.core.Az
import az.nvaton.komekci.core.ContactMatcher
import az.nvaton.komekci.core.FamilyContact
import az.nvaton.komekci.core.PhoneNumbers

/**
 * The phone's own contacts, so "Kamrana yaz" works for anyone, not only the family list. People with WhatsApp
 * come first (WhatsApp registers them as contacts with their international number). Read on the phone only;
 * nothing from the address book is sent to the server.
 */
object PhoneContacts {
    private data class Entry(val name: String, val number: String, val words: List<String>, val whatsApp: Boolean)

    private const val WHATSAPP_PROFILE = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
    private const val CACHE_MS = 60_000L

    @Volatile
    private var cache: Pair<Long, List<Entry>>? = null

    /** Contacts access is granted and the user turned the integration on. */
    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            context.applicationContext.let { (it as? az.nvaton.komekci.KomekciApp)?.settings?.contactsIntegration ?: true }

    /**
     * Contacts whose name matches the spoken [phrase] ("Kamrana", "Kamran müəllim", "WhatsApp-da Leylaya"),
     * Azerbaijani case endings allowed. The best matches only, at most four.
     */
    fun find(context: Context, phrase: String): List<FamilyContact> {
        if (!allowed(context)) return emptyList()
        val spoken = Az.tokenize(phrase).map { it.key }.filter { it.length >= 2 && it !in IGNORED && !it.startsWith("whatsap") }
        if (spoken.isEmpty()) return emptyList()
        val scored = load(context).mapNotNull { e ->
            val hits = spoken.count { w -> e.words.any { n -> ContactMatcher.wordMatches(w, n) } }
            if (hits == 0) return@mapNotNull null
            val firstName = e.words.isNotEmpty() && ContactMatcher.wordMatches(spoken[0], e.words[0])
            e to hits * 10 + (if (firstName) 3 else 0) + (if (e.whatsApp) 1 else 0)
        }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }
            .map { it.first }
            .distinctBy { it.name.lowercase() }
            .take(4)
            .map { FamilyContact("phone:${it.number}", it.name, it.number) }
    }

    /** One number in the address book, with every name it is saved under (the same number may be saved twice). */
    data class Group(val number: String, val names: List<String>, val whatsApp: Boolean)

    /** The whole address book grouped by number, alphabetical, for picking people to add. */
    fun groups(context: Context): List<Group> {
        if (!allowed(context)) return emptyList()
        cache = null
        val byNumber = LinkedHashMap<String, MutableList<Entry>>()
        loadAll(context).forEach { e -> byNumber.getOrPut(e.number.takeLast(9)) { mutableListOf() }.add(e) }
        return byNumber.values.map { list ->
            Group(
                number = list.first { it.number.length >= list.maxOf { e -> e.number.length } }.number,
                names = list.map { it.name }.distinctBy { it.lowercase() },
                whatsApp = list.any { it.whatsApp },
            )
        }.sortedBy { it.names.first().lowercase() }
    }

    fun canWrite(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Names the phone already has for this number (empty if none). */
    fun namesFor(context: Context, number: String): List<String> =
        if (!allowed(context)) emptyList() else load(context).filter { PhoneNumbers.same(it.number, number) }.map { it.name }

    /** Adds a person to the phone's own address book, so WhatsApp and the dialer know them too. */
    fun insert(context: Context, name: String, number: String): Boolean {
        if (!canWrite(context)) return false
        val ops = arrayListOf(
            android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
            android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build(),
            android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, "+" + PhoneNumbers.international(number))
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build(),
        )
        return runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }.isSuccess.also { cache = null }
    }

    /** Every entry, duplicates included (the search cache keeps one per number). */
    private fun loadAll(context: Context): List<Entry> {
        val entries = ArrayList<Entry>()
        val resolver = context.contentResolver
        runCatching {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val number = international(c.getString(1).orEmpty())
                    if (name.isNotEmpty() && number.length >= 7) entries.add(entry(name, number, whatsApp = false))
                }
            }
        }
        val whatsApp = load(context).filter { it.whatsApp }.map { it.number.takeLast(9) }.toSet()
        return entries.map { if (it.number.takeLast(9) in whatsApp) it.copy(whatsApp = true) else it }
    }

    private fun load(context: Context): List<Entry> {
        val now = SystemClock.elapsedRealtime()
        cache?.let { (at, list) -> if (now - at < CACHE_MS) return list }
        val entries = LinkedHashMap<String, Entry>() // keyed by the number's last 9 digits
        val resolver = context.contentResolver
        runCatching {
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.DATA1),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(WHATSAPP_PROFILE),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val number = c.getString(1)?.substringBefore('@')?.filter(Char::isDigit).orEmpty() // 994501234567@s.whatsapp.net
                    if (name.isNotEmpty() && number.length >= 7) entries.putIfAbsent(number.takeLast(9), entry(name, number, whatsApp = true))
                }
            }
        }
        runCatching {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val number = international(c.getString(1).orEmpty())
                    if (name.isNotEmpty() && number.length >= 7) entries.putIfAbsent(number.takeLast(9), entry(name, number, whatsApp = false))
                }
            }
        }
        return entries.values.toList().also { cache = now to it }
    }

    private fun entry(name: String, number: String, whatsApp: Boolean) =
        Entry(name, number, Az.tokenize(name).map { it.key }.filter { it.isNotEmpty() }, whatsApp)

    /** "050 123 45 67" → "994501234567": see [PhoneNumbers.international]. */
    fun international(raw: String): String = PhoneNumbers.international(raw)

    private val IGNORED = setOf("da", "de", "ile", "ilə", "ki", "my", "to", "the", "on", "mesaj", "mesaji", "sms")
}
