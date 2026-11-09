package az.nvaton.komekci.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** "Əzizə səsli mesaj göndər": the chat opens and WhatsApp records; nothing is read back or confirmed first. */
class VoiceMessageTest {
    private val murad = FamilyContact("murad", "Murad", "+994000000001", listOf("nəvəm"))
    private val aysu = FamilyContact("aysu", "Aysu", "+994000000002", listOf("nəvəm"))
    private val leyla = FamilyContact("leyla", "Leyla", "+994000000003", listOf("qızım"))
    private val eziz = FamilyContact("eziz", "Əziz", "+994000000005")
    private val kamran = FamilyContact("phone:994501112233", "Kamran Əliyev", "994501112233")
    private val family = listOf(murad, aysu, leyla, eziz)
    private val dm = DialogManager(phoneBook = { phrase -> if (phrase.contains("Kamran", ignoreCase = true)) listOf(kamran) else emptyList() })

    private fun spoken(actions: List<Action>) = actions.filterIsInstance<Action.Say>().joinToString(" ") { it.text }
    private fun handle(text: String) = dm.handle(text, family, "Əli")

    @Test
    fun `a named person gets the voice message right away`() {
        for (said in listOf(
            "Əli, Əzizə səsli mesaj göndər",
            "sesli mesaj gondermek isteyirem whatsapp uzerinden Ezize", // as the team typed it
            "WhatsApp-dan Əzizə səsli mesaj atmaq istəyirəm",
            "Əzizə voys at",
            "Əzizə səsli mesaj",
        )) {
            dm.reset()
            assertEquals(said, listOf(Action.VoiceMessage(eziz)), handle(said))
        }
    }

    @Test
    fun `without a name it asks who`() {
        assertEquals("Kimə səsli mesaj göndərim?", spoken(handle("səsli mesaj göndərmək istəyirəm")))
        assertEquals(listOf(Action.VoiceMessage(leyla)), handle("Leylaya"))
        assertEquals("Kimə səsli mesaj göndərim: Murada, yoxsa Aysuya?", spoken(handle("nəvəmə səsli mesaj göndər")))
        assertEquals(listOf(Action.VoiceMessage(aysu)), handle("Aysuya"))
    }

    @Test
    fun `people from the phone book, and names only the AI can clean up`() {
        assertEquals(listOf(Action.VoiceMessage(kamran)), handle("Kamrana səsli mesaj at"))
        dm.reset()
        // "Elvinə": not in the phone book either, so the AI gives the bare name and WhatsApp's search finds the chat.
        assertEquals(listOf(Action.AskServer("Elvinə səsli mesaj göndər")), handle("Elvinə səsli mesaj göndər"))
        val fromAi = dm.handleServerIntent(ServerIntent("voice_message", emptyList(), "", false, "", recipientName = "Elvin"), family, "")
        assertEquals(listOf(Action.VoiceMessage(FamilyContact.whatsAppSearch("Elvin"))), fromAi)
    }

    @Test
    fun `a voice call, a message about a voice message and the volume are not voice messages`() {
        assertTrue(handle("Əzizə səsli zəng et").any { it is Action.Call })
        dm.reset()
        handle("Əzizə yaz ki səsli mesajını aldım")
        assertEquals(DialogManager.Pending.Confirm(eziz, "Səsli mesajını aldım"), dm.pending)
        dm.reset()
        assertTrue(handle("səsi artır").none { it is Action.VoiceMessage })
    }

    @Test
    fun `asking for one while a text waits replaces the text`() {
        handle("Leylaya yaz axşam gəlirəm")
        assertEquals(listOf(Action.VoiceMessage(eziz)), handle("yox, Əzizə səsli mesaj göndər"))
        assertEquals(null, dm.pending)
    }

    @Test
    fun `review - yes sends, no cancels, again re-records`() {
        assertEquals("Əzizə səsli mesajı göndərim?", spoken(dm.reviewVoice(eziz)))
        assertEquals(DialogManager.Pending.ConfirmVoice(eziz), dm.pending)
        assertEquals(listOf(Action.SendVoice(eziz)), dm.handle("hə", family, "Əli"))
        assertEquals(null, dm.pending)

        dm.reviewVoice(eziz)
        assertEquals("Yaxşı, göndərmirəm.", spoken(dm.handle("yox", family, "Əli")))
        assertEquals(null, dm.pending)

        dm.reviewVoice(eziz)
        assertEquals(listOf(Action.ReRecordVoice(eziz)), dm.handle("yenidən de", family, "Əli"))
        assertEquals(null, dm.pending)
    }

    @Test
    fun `a new command during review replaces the voice message`() {
        dm.reviewVoice(eziz)
        assertEquals(listOf(Action.ReadMessages), dm.handle("mesajlarımı oxu", family, "Əli"))
        assertEquals(null, dm.pending)
    }

    @Test
    fun `in English the fast database knows voice messages`() {
        val db = QuickCommands.parse(File("src/main/assets/commands/en.tsv").readText())
        val now = LocalDateTime.of(2026, 11, 8, 15, 0)
        for (said in listOf("send a voice message to my son", "record a voice note for Leyla", "send Leyla a voice message", "voice message to my daughter")) {
            assertEquals(said, QuickIntent.VOICE_MESSAGE, db.match(said, now)?.intent)
        }
        assertEquals("my son", db.match("send a voice message to my son", now)?.contact)
        assertEquals(QuickIntent.MESSAGE, db.match("text Leyla that I'll be late", now)?.intent) // texts stay texts
    }
}
