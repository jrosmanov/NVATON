package az.nvaton.komekci.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saving a spoken number, and what happens when the number is already saved. */
class SaveContactTest {
    private val murad = FamilyContact("murad", "Murad", "+994501112233", listOf("nəvəm"))
    private val sabina = FamilyContact("phone:994552223344", "Səbinə Həsənova", "994552223344")
    private val dm = DialogManager(LocalNlu()) { if (Az.fold(it).startsWith("sebine")) listOf(sabina) else emptyList() }
    private val nlu = LocalNlu()

    private fun spoken(actions: List<Action>) = actions.filterIsInstance<Action.Say>().joinToString(" ") { it.text }
    private fun handle(text: String) = dm.handle(text, listOf(murad), "Əli")

    @Test
    fun `numbers are read, compared and spoken back`() {
        assertEquals("994501234567", PhoneNumbers.international("050 123 45 67"))
        assertTrue(PhoneNumbers.same("+994 50 123 45 67", "050-123-45-67"))
        assertEquals("050 123 45 67", PhoneNumbers.spoken("994501234567"))
        assertEquals("0501234567", PhoneNumbers.find("Kamranın nömrəsi 050 123 45 67, yadda saxla"))
        assertNull(PhoneNumbers.find("saat 7-də"))
        assertEquals("0501234567", PhoneNumbers.fromWords("sıfır əlli, yüz iyirmi üç, qırx beş, altmış yeddi"))
        assertEquals("0551234567", PhoneNumbers.fromWords("zero five five one two three four five six seven"))
        assertNull(PhoneNumbers.fromWords("salam necəsən"))
    }

    @Test
    fun `the sentence gives the name, the number and the relation`() {
        assertEquals(Command.SaveContact("Ramiz", "0501234567", null), nlu.parse("Əli, Ramizin nömrəsini yadda saxla 050 123 45 67", emptyList()))
        assertEquals(Command.SaveContact("Elvin", "0501234567", null), nlu.parse("yeni kontakt Elvin 050 123 45 67 yadda saxla", emptyList()))
        // "Kamranın" is Kamran + ın or Kamra + nın: the AI model decides.
        assertTrue(nlu.parse("Kamranın nömrəsini yadda saxla 050 123 45 67", emptyList()) is Command.Unknown)
        assertEquals(Command.SaveContact("Murad", "0557654321", "nəvəm"), nlu.parse("nəvəm Muradın nömrəsini yadda saxla 055 765 43 21", emptyList()))
        assertEquals(Command.SaveContact(null, "0501234567", null), nlu.parse("0501234567 nömrəsini yadda saxla", emptyList()))
        // A message that only contains a number stays a message.
        assertTrue(nlu.parse("Murada yaz ki mənim nömrəm 050 123 45 67", listOf(murad)) is Command.SendMessage)
        assertEquals(Command.ImportContacts, nlu.parse("telefon kontaktlarımı əlavə et", emptyList()))
    }

    @Test
    fun `reads the number back, saves only after yes`() {
        assertEquals("Ramiz, 050 123 45 67. Yadda saxlayım?", spoken(handle("Ramizin nömrəsini yadda saxla 050 123 45 67")))
        val saved = handle("hə").single() as Action.SaveContact
        assertEquals("Ramiz", saved.contact.name)
        assertEquals("0501234567", saved.contact.phone)
        assertNull(saved.replaceId)
    }

    @Test
    fun `asks for the missing name or number, numbers in words too`() {
        assertEquals("050 123 45 67 nömrəsini hansı adla saxlayım?", spoken(handle("0501234567 nömrəsini yadda saxla")))
        assertEquals("Kamran, 050 123 45 67. Yadda saxlayım?", spoken(handle("Kamran")))
        dm.reset()
        dm.handleServerIntent(ServerIntent("save_contact", emptyList(), "", false, "", recipientName = "Rauf"), listOf(murad), "")
        assertEquals("Rauf, 050 123 45 67. Yadda saxlayım?", spoken(handle("sıfır əlli yüz iyirmi üç qırx beş altmış yeddi")))
    }

    @Test
    fun `a named person without a number comes from the phone book`() {
        val actions = dm.handleServerIntent(ServerIntent("save_contact", emptyList(), "", false, "", recipientName = "Səbinə"), listOf(murad), "")
        assertEquals("Səbinə Həsənova, 055 222 33 44. Yadda saxlayım?", spoken(actions))
        assertEquals(listOf(Action.OpenContactImport), dm.handleServerIntent(ServerIntent("import_contacts", emptyList(), "", false, ""), listOf(murad), ""))
    }

    @Test
    fun `a number already saved under another name asks how to keep it`() {
        assertEquals(
            "Bu nömrə artıq «Murad» adı ilə var. Köhnəni saxlayım, «Muro» ilə əvəz edim, yoxsa adları birləşdirim?",
            spoken(handle("yeni kontakt Muro 050 111 22 33 yadda saxla")),
        )
        assertTrue(dm.pending is DialogManager.Pending.Duplicate)
        val merged = handle("birləşdir").single() as Action.SaveContact
        assertEquals("murad", merged.replaceId)
        assertEquals("Murad", merged.contact.name)
        assertEquals(listOf("nəvəm", "Muro"), merged.contact.aliases)

        handle("yeni kontakt Muro 050 111 22 33 yadda saxla")
        val replaced = handle("əvəz et").single() as Action.SaveContact
        assertEquals("Muro", replaced.contact.name)
        assertEquals("murad", replaced.replaceId)

        handle("yeni kontakt Muro 050 111 22 33 yadda saxla")
        assertEquals("Yaxşı, «Murad» kimi qalır.", spoken(handle("köhnəni saxla")))
        assertNull(dm.pending)
    }

    @Test
    fun `the same name and number is not saved twice`() {
        assertEquals("Murad bu nömrə ilə artıq yadda saxlanıb.", spoken(handle("yeni kontakt Murad 050 111 22 33 yadda saxla")))
    }
}
