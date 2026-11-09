package az.nvaton.komekci.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A message read back before sending can be corrected: said again, fixed by voice (with the AI), or typed. */
class DraftCorrectionTest {
    private val murad = FamilyContact("murad", "Murad", "+994000000001", listOf("nəvəm"))
    private val aysu = FamilyContact("aysu", "Aysu", "+994000000002", listOf("nəvəm"))
    private val leyla = FamilyContact("leyla", "Leyla", "+994000000003", listOf("qızım"))
    private val kamran = FamilyContact("phone:994501112233", "Kamran", "994501112233")
    private val family = listOf(murad, aysu, leyla)
    private val dm = DialogManager(phoneBook = { phrase -> if (phrase.contains("Kamran", ignoreCase = true)) listOf(kamran) else emptyList() })

    private fun spoken(actions: List<Action>) = actions.filterIsInstance<Action.Say>().joinToString(" ") { it.text }
    private fun handle(text: String) = dm.handle(text, family, "Əli")
    private fun ai(intent: String, text: String = "", ids: List<String> = emptyList(), name: String = "") =
        ServerIntent(intent, ids, text, false, "", recipientName = name)

    /** "Leylaya yaz axşam gəlirəm" → waiting for «Hə». */
    private fun draft() {
        dm.reset()
        handle("Leylaya yaz axşam gəlirəm")
        assertEquals(DialogManager.Pending.Confirm(leyla, "Axşam gəlirəm"), dm.pending)
    }

    @Test
    fun `dəyiş asks for the message again and takes the new one`() {
        for (ask in listOf("dəyiş", "mesajı düzəlt", "yenidən deyim", "change the message", "yox, dəyiş")) {
            draft()
            assertEquals(ask, "Yaxşı. Mesajı yenidən deyin.", spoken(handle(ask)))
            assertEquals(ask, DialogManager.Pending.AskText(leyla), dm.pending)
        }
        assertEquals("Leylaya «Axşam gələcəm» yazım?", spoken(handle("axşam gələcəm")))
        assertEquals(listOf(Action.SendMessage(leyla, "Axşam gələcəm")), handle("hə"))
    }

    @Test
    fun `a spoken correction goes to the AI and comes back to be confirmed`() {
        draft()
        assertEquals(listOf(Action.FixDraft("yox, axşam gələcəm yaz")), handle("yox, axşam gələcəm yaz"))
        assertEquals(DialogManager.Pending.Confirm(leyla, "Axşam gəlirəm"), dm.pending) // nothing sent, still waiting
        val fixed = dm.applyFix(ai("edit_message", "Axşam gələcəm"), family, "yox, axşam gələcəm yaz")
        assertEquals("Leylaya «Axşam gələcəm» yazım?", spoken(fixed))
        assertEquals(listOf(Action.SendMessage(leyla, "Axşam gələcəm")), handle("hə"))
    }

    @Test
    fun `new words with yaz are a correction, not a yes`() {
        draft()
        val actions = handle("gələcəm yaz")
        assertTrue(actions.none { it is Action.SendMessage })
        assertEquals(listOf(Action.FixDraft("gələcəm yaz")), actions)
        for (yes in listOf("hə", "yaz", "göndər", "onu göndər", "bəli, göndər", "send it", "yes")) {
            draft()
            assertEquals(yes, listOf(Action.SendMessage(leyla, "Axşam gəlirəm")), handle(yes))
        }
    }

    @Test
    fun `no, cancel and a polite no still call it off`() {
        for (no in listOf("yox", "yox yox", "yox, göndərmə", "göndərmə", "ləğv et", "yox, sağ ol", "lazım deyil", "boş ver", "no thank you", "cancel")) {
            draft()
            assertEquals(no, "Yaxşı, göndərmirəm.", spoken(handle(no)))
            assertNull(no, dm.pending)
        }
    }

    @Test
    fun `without the AI their words become the message`() {
        draft()
        assertEquals("Leylaya «Axşam gələcəm» yazım?", spoken(dm.localFix("yox, axşam gələcəm yaz")))
        draft()
        assertEquals("Leylaya «Gələcəm» yazım?", spoken(dm.localFix("yox, belə yaz gələcəm")))
        // An instruction about the text needs the AI: ask for the whole message instead of sending the instruction.
        draft()
        assertEquals("Bunu dəyişə bilmədim. Mesajı əvvəldən deyin.", spoken(dm.localFix("sonuna sağ ol əlavə et")))
        assertEquals(DialogManager.Pending.AskText(leyla), dm.pending)
        // One word may replace one word of the message: only the AI can tell, so offline they say it all again.
        draft()
        assertEquals("Bunu dəyişə bilmədim. Mesajı əvvəldən deyin.", spoken(dm.localFix("gələcəm")))
        assertEquals("Leylaya «Axşam gələcəm» yazım?", spoken(handle("axşam gələcəm")))
    }

    @Test
    fun `unclear answers are asked again and do not become the message`() {
        for (unclear in listOf("hmm", "nə?", "bilmirəm", "başa düşmədim", "what?")) {
            draft()
            assertEquals(unclear, "Leylaya «Axşam gəlirəm» yazım? «Hə», «yox» və ya «dəyiş» deyin.", spoken(handle(unclear)))
            assertEquals(unclear, DialogManager.Pending.Confirm(leyla, "Axşam gəlirəm"), dm.pending)
        }
    }

    @Test
    fun `another name sends the same message to someone else`() {
        draft()
        assertEquals("Aysuya «Axşam gəlirəm» yazım?", spoken(handle("Aysuya göndər")))
        assertEquals(DialogManager.Pending.Confirm(aysu, "Axşam gəlirəm"), dm.pending)
        draft()
        assertEquals("Aysuya «Axşam gəlirəm» yazım?", spoken(handle("yox, Aysuya göndər")))
        draft()
        assertEquals(listOf(Action.SendMessage(leyla, "Axşam gəlirəm")), handle("Leylaya göndər"))
    }

    @Test
    fun `the AI can move the message to someone in the phone book`() {
        draft()
        assertEquals("Kamrana «Axşam gəlirəm» yazım?", spoken(dm.applyFix(ai("edit_message", name = "Kamran"), family, "Kamrana göndər")))
        assertEquals(DialogManager.Pending.Confirm(kamran, "Axşam gəlirəm"), dm.pending)
        draft()
        assertEquals("Murada «Sabah gələcəm» yazım?", spoken(dm.applyFix(ai("edit_message", "Sabah gələcəm", listOf("murad")), family, "x")))
    }

    @Test
    fun `an AI answer that is not a correction keeps the message and asks again`() {
        draft()
        val again = dm.applyFix(ServerIntent("chat", emptyList(), "", false, "Yaxşı, göndərmirəm."), family, "nə bilim")
        assertEquals("Leylaya «Axşam gəlirəm» yazım? «Hə», «yox» və ya «dəyiş» deyin.", spoken(again))
        assertEquals(DialogManager.Pending.Confirm(leyla, "Axşam gəlirəm"), dm.pending)
    }

    @Test
    fun `another command from the AI replaces the message`() {
        draft()
        assertEquals(listOf(Action.ReadMessages), dm.applyFix(ai("read_messages"), family, "kim yazıb"))
        assertNull(dm.pending)
    }

    @Test
    fun `typed on the screen`() {
        draft()
        assertEquals("Leylaya «Axşam gələcəm, gözləyin» yazım?", spoken(dm.editDraft("  axşam gələcəm, gözləyin ")))
        assertEquals(DialogManager.Pending.Confirm(leyla, "Axşam gələcəm, gözləyin"), dm.pending)
        assertEquals(emptyList<Action>(), dm.editDraft("   ")) // an empty box changes nothing
        dm.reset()
        assertEquals(emptyList<Action>(), dm.editDraft("salam")) // nothing waiting
    }
}
