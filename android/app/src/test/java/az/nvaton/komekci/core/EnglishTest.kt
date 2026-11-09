package az.nvaton.komekci.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The same dialogue with the language switched to English in Settings. */
class EnglishTest {
    private val murad = FamilyContact("murad", "Murad", "+994000000001", listOf("nəvəm"))
    private val aysu = FamilyContact("aysu", "Aysu", "+994000000002", listOf("nəvəm"))
    private val leyla = FamilyContact("leyla", "Leyla", "+994000000003", listOf("qızım"))
    private val family = listOf(murad, aysu, leyla)
    private val dm = DialogManager()

    @Before
    fun english() {
        I18n.lang = Lang.EN
    }

    @After
    fun back() {
        I18n.lang = Lang.AZ
    }

    private fun spoken(actions: List<Action>) = actions.filterIsInstance<Action.Say>().joinToString(" ") { it.text }
    private fun handle(text: String) = dm.handle(text, family, "Əli")
    private fun server(ids: List<String>, message: String) =
        dm.handleServerIntent(ServerIntent("send_message", ids, message, false, ""), family, "")

    @Test
    fun `messages go to the AI, then confirm and send in English`() {
        assertEquals(listOf(Action.AskServer("text my daughter hello")), handle("Ali, text my daughter hello"))
        assertEquals("Send «Hello» to Leyla?", spoken(server(listOf("leyla"), "hello and send it")))
        assertEquals(listOf(Action.SendMessage(leyla, "Hello")), handle("yes"))
        assertNull(dm.pending)
    }

    @Test
    fun `asks which grandchild by name and understands ordinals`() {
        assertEquals("Who should I write to: Murad or Aysu?", spoken(server(listOf("murad", "aysu"), "Hello")))
        assertEquals("Send «Hello» to Aysu?", spoken(handle("the second one")))
        assertEquals("Okay, I won't send it.", spoken(handle("no")))
    }

    @Test
    fun `relation words find contacts saved with Azerbaijani aliases`() {
        assertEquals("I couldn't find that person in your contacts. Who should I write to?", spoken(server(emptyList(), "Hello")))
        assertEquals("Send «Hello» to Leyla?", spoken(handle("my daughter")))
    }

    @Test
    fun `a dictated message may contain no-words`() {
        server(listOf("leyla"), "")
        assertEquals("Send «Don't forget the milk» to Leyla?", spoken(handle("don't forget the milk")))
    }

    @Test
    fun `camera, screen, help and yes-no work offline`() {
        assertEquals(listOf(Action.OpenCamera("what is this medicine?")), handle("Ali, what is this medicine?"))
        assertEquals(listOf(Action.OpenCamera(null)), handle("open the camera"))
        assertEquals(listOf(Action.ReadScreen("read the screen")), handle("read the screen"))
        assertEquals(DialogManager.HELP, spoken(handle("what can you do")))
        val nlu = LocalNlu()
        assertEquals(true, nlu.isBareScreenRequest("Ali, read the screen please"))
        assertEquals(false, nlu.isBareScreenRequest("who wrote to me on the screen"))
        assertEquals(true, nlu.isCloseCamera("close"))
        assertEquals("Hello", nlu.cleanMessage("hello on WhatsApp"))
    }

    @Test
    fun `Azerbaijani relation words are not English-only matches in Azerbaijani mode`() {
        I18n.lang = Lang.AZ
        assertEquals(listOf(listOf("leyla"), listOf("qizim")), leyla.keys)
    }
}
