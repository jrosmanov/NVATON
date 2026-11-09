package az.nvaton.komekci.core

import az.nvaton.komekci.data.PhoneContacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messages to anyone: the phone book first, then a WhatsApp search by name (groups, people without a number). */
class AnyChatTest {
    private val leyla = FamilyContact("leyla", "Leyla", "+994000000003", listOf("qızım"))
    private val kamran = FamilyContact("phone:994501112233", "Kamran Əliyev", "994501112233")
    private val phoneBook: (String) -> List<FamilyContact> = { phrase ->
        if (Az.fold(phrase).startsWith("kamran")) listOf(kamran) else emptyList()
    }
    private val dm = DialogManager(LocalNlu(), phoneBook)

    private fun spoken(actions: List<Action>) = actions.filterIsInstance<Action.Say>().joinToString(" ") { it.text }
    private fun server(intent: String, name: String, message: String = "") =
        dm.handleServerIntent(ServerIntent(intent, emptyList(), message, false, "", recipientName = name), listOf(leyla), "")

    @Test
    fun `a phone contact found from the spoken phrase`() {
        assertEquals("Kamran Əliyevə «Salam» yazım?", spoken(dm.handle("Əli, Kamrana yaz salam", listOf(leyla), "Əli")))
        assertEquals(listOf(Action.SendMessage(kamran, "Salam")), dm.handle("hə", listOf(leyla), "Əli"))
    }

    @Test
    fun `the AI's recipient name goes to the phone book, then to a WhatsApp search`() {
        assertEquals("Kamran Əliyevə «Sabah görüşək» yazım?", spoken(server("send_message", "Kamran", "Sabah görüşək")))
        dm.reset()
        server("send_message", "Ailə qrupu", "Axşam gəlin")
        val pending = dm.pending as DialogManager.Pending.Confirm
        assertTrue(pending.contact.isWhatsAppSearch)
        assertEquals("Ailə qrupu", pending.contact.name)
    }

    @Test
    fun `open a chat, and calls need a number`() {
        assertEquals(listOf(Action.OpenChat(kamran)), server("open_chat", "Kamran"))
        assertEquals(listOf(Action.OpenChat(FamilyContact.whatsAppSearch("Ailə qrupu"))), server("open_chat", "Ailə qrupu"))
        val call = server("call", "Ailə qrupu")
        assertEquals("Kontaktlarınızda belə adam tapmadım. Kimə zəng edim?", spoken(call))
    }

    @Test
    fun `a name the user gives when asked is looked up too`() {
        dm.handle("mesaj yaz", listOf(leyla), "Əli") // "Kimə yazım?"
        assertEquals("Kamran Əliyevə nə yazım?", spoken(dm.handle("Kamran", listOf(leyla), "Əli")))
    }

    @Test
    fun `phone numbers become international`() {
        assertEquals("994501234567", PhoneContacts.international("050 123 45 67"))
        assertEquals("994501234567", PhoneContacts.international("+994 50 123 45 67"))
        assertEquals("994501234567", PhoneContacts.international("00994501234567"))
        assertEquals("994501234567", PhoneContacts.international("501234567"))
    }
}
