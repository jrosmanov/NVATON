package az.nvaton.komekci.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogManagerTest {
    private val murad = FamilyContact("murad", "Murad", "+994000000001", listOf("nəvəm"))
    private val aysu = FamilyContact("aysu", "Aysu", "+994000000002", listOf("nəvəm"))
    private val leyla = FamilyContact("leyla", "Leyla", "+994000000003", listOf("qızım"))
    private val reshad = FamilyContact("reshad", "Rəşad", "+994000000004", listOf("oğlum"))
    private val family = listOf(murad, aysu, leyla, reshad)
    private val dm = DialogManager()

    private fun spoken(actions: List<Action>) = actions.filterIsInstance<Action.Say>().joinToString(" ") { it.text }
    private fun handle(text: String, contacts: List<FamilyContact> = family) = dm.handle(text, contacts, "Əli")

    @Test
    fun `demo flow - asks which grandchild, confirms, then sends`() {
        assertEquals("Kimə yazım: Murada, yoxsa Aysuya?", spoken(handle("Əli, nəvəmə yaz: salam")))
        assertEquals("Murada «Salam» yazım?", spoken(handle("Murada")))
        assertEquals(listOf(Action.SendMessage(murad, "Salam")), handle("hə"))
        assertNull(dm.pending)
    }

    @Test
    fun `tapping a name button works as an answer`() {
        handle("nəvəmə yaz salam")
        assertEquals("Aysuya «Salam» yazım?", spoken(handle("Aysu")))
    }

    @Test
    fun `ordinal answer picks a candidate`() {
        handle("nəvəmə yaz salam")
        assertEquals("Aysuya «Salam» yazım?", spoken(handle("ikincisinə")))
    }

    @Test
    fun `de ki form keeps only the message`() {
        handle("qızıma de ki axşam gəlsin")
        assertEquals(DialogManager.Pending.Confirm(leyla, "Axşam gəlsin"), dm.pending)
    }

    @Test
    fun `message before the verb`() {
        handle("Leylaya salam yaz")
        assertEquals(DialogManager.Pending.Confirm(leyla, "Salam"), dm.pending)
    }

    @Test
    fun `yaz və göndər is a command, not part of the message`() {
        handle("Əli, Leylaya WhatsApp-dan salam yaz və göndər")
        assertEquals(DialogManager.Pending.Confirm(leyla, "Salam"), dm.pending)
        dm.reset()
        handle("qızıma yaz ki axşam gəlsin və göndər")
        assertEquals(DialogManager.Pending.Confirm(leyla, "Axşam gəlsin"), dm.pending)
        dm.reset()
        handle("qızıma de ki pulu göndər") // the message itself asks to send something
        assertEquals(DialogManager.Pending.Confirm(leyla, "Pulu göndər"), dm.pending)
    }

    @Test
    fun `command words the AI model left in the message are removed`() {
        dm.handleServerIntent(ServerIntent("send_message", listOf("leyla"), "Salam və göndər", false, ""), family, "")
        assertEquals(DialogManager.Pending.Confirm(leyla, "Salam"), dm.pending)
    }

    @Test
    fun `ekranı oxu reads the screen, not the camera`() {
        assertEquals(listOf(Action.ReadScreen("ekranı oxu")), handle("Əli, ekranı oxu"))
        assertEquals(listOf(Action.ReadScreen("ekranda nə yazılıb")), handle("ekranda nə yazılıb"))
        assertEquals(listOf(Action.ReadScreen("")), dm.handleServerIntent(ServerIntent("read_screen", emptyList(), "", false, ""), family, ""))
        val nlu = LocalNlu()
        assertTrue(nlu.isBareScreenRequest("Əli, ekranı oxu"))
        assertTrue(nlu.isBareScreenRequest("ekranda nə yazılıb"))
        assertTrue(!nlu.isBareScreenRequest("ekranda kim yazıb mənə"))
    }

    @Test
    fun `verb first, then recipient`() {
        handle("yaz Leylaya sabah gəlirəm")
        assertEquals(DialogManager.Pending.Confirm(leyla, "Sabah gəlirəm"), dm.pending)
    }

    @Test
    fun `missing text is asked for`() {
        val contacts = listOf(murad, leyla)
        assertEquals("Murada nə yazım?", spoken(handle("nəvəmə mesaj yaz", contacts)))
        assertEquals("Murada «Salam necəsən» yazım?", spoken(handle("salam necəsən", contacts)))
    }

    @Test
    fun `missing recipient is asked for`() {
        assertEquals("Kimə yazım?", spoken(handle("mesaj yaz")))
        assertEquals("Leylaya nə yazım?", spoken(handle("Leylaya")))
    }

    @Test
    fun `no cancels the confirmation`() {
        handle("qızıma yaz salam")
        val actions = handle("yox")
        assertEquals("Yaxşı, göndərmirəm.", spoken(actions))
        assertTrue(actions.none { it is Action.SendMessage })
        assertNull(dm.pending)
    }

    @Test
    fun `göndərmə means do not send`() {
        handle("qızıma yaz salam")
        assertTrue(handle("göndərmə").none { it is Action.SendMessage })
    }

    @Test
    fun `a new command while confirming replaces the old one`() {
        handle("qızıma yaz salam")
        handle("oğluma yaz sabah gəl")
        assertEquals(DialogManager.Pending.Confirm(reshad, "Sabah gəl"), dm.pending)
    }

    @Test
    fun `unclear answers are re-asked, then cancelled`() {
        handle("qızıma yaz salam")
        handle("bilmirəm")
        handle("hmm")
        val actions = handle("nə?")
        assertEquals("Yaxşı, ləğv etdim. Yenidən deyə bilərsiniz.", spoken(actions))
        assertNull(dm.pending)
    }

    @Test
    fun `diacritics are optional`() {
        handle("neveme yaz salam", listOf(murad))
        assertEquals(DialogManager.Pending.Confirm(murad, "Salam"), dm.pending)
    }

    @Test
    fun `cloud recogniser punctuation is ignored`() {
        handle("Nəvəmə yaz, salam.", listOf(murad))
        assertEquals(DialogManager.Pending.Confirm(murad, "Salam"), dm.pending)
    }

    @Test
    fun `a message about medicine stays a message`() {
        handle("nəvəmə yaz ki dərmanımı içdim", listOf(murad))
        assertEquals(DialogManager.Pending.Confirm(murad, "Dərmanımı içdim"), dm.pending)
    }

    @Test
    fun `tell someone to call stays a message`() {
        handle("Murada de ki zəng etsin")
        assertEquals(DialogManager.Pending.Confirm(murad, "Zəng etsin"), dm.pending)
    }

    @Test
    fun `call a contact`() {
        val actions = handle("oğluma zəng et")
        assertEquals("Rəşada zəng edirəm.", spoken(actions))
        assertTrue(actions.contains(Action.Call(reshad, video = false)))
    }

    @Test
    fun `call with comitative ending`() {
        assertTrue(handle("qızımla danışmaq istəyirəm").contains(Action.Call(leyla, video = false)))
    }

    @Test
    fun `camera request`() {
        assertEquals(listOf(Action.OpenCamera("bu dərmanın üstündə nə yazılıb")), handle("bu dərmanın üstündə nə yazılıb"))
    }

    @Test
    fun `question about the view keeps the question for the photo`() {
        assertEquals(listOf(Action.OpenCamera("bu nədir?")), handle("Əli, bu nədir?"))
        assertEquals(listOf(Action.OpenCamera("buna bax")), handle("buna bax"))
    }

    @Test
    fun `just opening the camera asks nothing yet`() {
        assertEquals(listOf(Action.OpenCamera(null)), handle("kameranı aç"))
        assertEquals(listOf(Action.OpenCamera(null)), handle("zəhmət olmasa kameranı aç"))
    }

    @Test
    fun `server camera intent keeps the spoken question`() {
        val intent = ServerIntent("read_camera", emptyList(), "", false, "")
        assertEquals(listOf(Action.OpenCamera("əlimdəkini de")), dm.handleServerIntent(intent, family, "əlimdəkini de"))
    }

    @Test
    fun `unknown request goes to the server AI`() {
        assertEquals(listOf(Action.AskServer("bu gün hava necədir")), handle("bu gün hava necədir"))
    }

    @Test
    fun `misrecognised name goes to the server AI instead of guessing`() {
        assertEquals(listOf(Action.AskServer("Murata yaz salam")), handle("Murata yaz salam"))
    }

    @Test
    fun `the particle də is not a send verb`() {
        assertTrue(handle("mənə də kömək et ürəyim ağrıyır").single() is Action.AskServer)
    }

    @Test
    fun `assistant name alone prompts for a command`() {
        assertEquals(listOf(Action.Say("Buyurun, sizi dinləyirəm.", listen = true)), handle("Əli"))
    }

    @Test
    fun `contact named like the assistant is not stripped`() {
        val eli = FamilyContact("eli", "Əli", "+994000000009")
        assertTrue(dm.handle("Əliyə zəng et", listOf(eli), "Əli").contains(Action.Call(eli, video = false)))
    }

    @Test
    fun `server intent with two contacts asks which one`() {
        val actions = dm.handleServerIntent(
            ServerIntent("send_message", listOf("murad", "aysu"), "axşam gəl", videoCall = false, reply = ""),
            family,
            "uşaqlara yaz axşam gəlsinlər",
        )
        assertEquals("Kimə yazım: Murada, yoxsa Aysuya?", spoken(actions))
        handle("Aysuya")
        assertEquals(DialogManager.Pending.Confirm(aysu, "Axşam gəl"), dm.pending)
    }

    @Test
    fun `server chat reply is spoken`() {
        val actions = dm.handleServerIntent(ServerIntent("chat", emptyList(), "", false, "Bu gün şənbədir."), family, "bu gün nə gündür")
        assertEquals(listOf(Action.Say("Bu gün şənbədir.")), actions)
    }
}
