package az.nvaton.komekci.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** The English fast-command database (assets/commands/en.tsv) and how its commands are carried out. */
class QuickCommandsTest {
    private val db = QuickCommands.parse(File("src/main/assets/commands/en.tsv").readText())
    private val afternoon = LocalDateTime.of(2026, 11, 8, 15, 0)
    private val rashad = FamilyContact("reshad", "Rəşad", "+994000000004", listOf("oğlum"))
    private val leyla = FamilyContact("leyla", "Leyla", "+994000000003", listOf("qızım"))

    @Before
    fun english() {
        I18n.lang = Lang.EN
        QuickCommands.english = db
    }

    @After
    fun back() {
        I18n.lang = Lang.AZ
        QuickCommands.english = null
    }

    private fun m(text: String) = db.match(text, afternoon)

    @Test
    fun `the database has at least a hundred commands`() {
        assertTrue("only ${db.entries.size}", db.entries.size >= 100)
    }

    @Test
    fun `everyday requests are recognised with their details`() {
        assertEquals(QuickIntent.TIME, m("Ali, what time is it?")?.intent)
        assertEquals(QuickIntent.TIME, m("what's the time please")?.intent)
        assertEquals(QuickIntent.CALL, m("call my son")?.intent)
        assertEquals("my son", m("call my son")?.contact)
        assertEquals("ambulance", m("call an ambulance")?.service)
        assertEquals(QuickIntent.HELP_ME, m("help")?.intent)
        m("text my daughter that I'll be late")!!.let {
            assertEquals(QuickIntent.MESSAGE, it.intent)
            assertEquals("my daughter", it.contact)
            assertEquals("i will be late", it.text)
        }
        assertEquals("hello", m("send a message to Leyla saying hello")?.text)
        assertEquals("call me", m("tell my grandson to call me")?.text)
        assertEquals(QuickIntent.MESSAGE_ASK, m("message Murad")?.intent)
        assertEquals(QuickIntent.FLASHLIGHT_ON, m("turn on the flashlight")?.intent)
        assertEquals(QuickIntent.VOLUME_UP, m("I can't hear you")?.intent)
        assertEquals("whatsapp", m("open WhatsApp")?.app)
        assertEquals(QuickIntent.OPEN_CAMERA, m("open the camera")?.intent)
        assertEquals(QuickIntent.CAMERA, m("what is this")?.intent)
        assertEquals(QuickIntent.MEDICINE_TAKEN, m("I took my pills")?.intent)
        assertEquals(QuickIntent.BATTERY, m("how much battery do I have")?.intent)
        assertEquals(QuickIntent.SLOWER, m("speak slower")?.intent)
        assertEquals(QuickIntent.THANKS, m("thank you")?.intent)
    }

    @Test
    fun `anything else is left to the AI`() {
        assertNull(m("what's the weather like in Baku"))
        assertNull(m("call me"))
        assertNull(m("tell me a joke"))
    }

    @Test
    fun `alarms and reminders get their times`() {
        assertEquals(AlarmSpec(hour = 7, minute = 0), m("set an alarm for 7 am")?.alarm)
        assertEquals(AlarmSpec(hour = 19, minute = 0), m("set an alarm for 7")?.alarm) // said at 3 pm: the next 7 is the evening
        assertEquals(AlarmSpec(hour = 7, minute = 0), m("wake me up at 7")?.alarm) // waking up is the morning
        assertEquals(AlarmSpec(hour = 6, minute = 30, dayOffset = 1), m("wake me up tomorrow at 6:30")?.alarm)
        assertEquals(AlarmSpec(hour = 21, minute = 0, repeatDaily = true, label = "Take your medicine"), m("remind me to take my medicine every day at 9 pm")?.alarm)
        assertEquals(AlarmSpec(inMinutes = 20, label = "Call aysu"), m("remind me to call Aysu in 20 minutes")?.alarm)
        assertEquals(AlarmSpec(inMinutes = 30, label = "Timer"), m("set a timer for half an hour")?.alarm)
        assertEquals(AlarmSpec(hour = 19, minute = 30), m("alarm at half past seven in the evening")?.alarm)
        assertEquals(90, QuickCommands.minutes("1 hour 30 minutes"))
        assertEquals(45, QuickCommands.number(listOf("forty", "five")))
    }

    @Test
    fun `the dialogue carries them out on the phone`() {
        val dm = DialogManager()
        val family = listOf(rashad, leyla)
        val call = dm.handle("Ali, call my son", family, "Əli")
        assertEquals(listOf(Action.Say("Calling Rəşad."), Action.Call(rashad, false)), call)

        assertEquals("Ambulance, 103. Shall I call?", (dm.handle("call the ambulance", family, "Əli").single() as Action.Say).text)
        val yes = dm.handle("yes", family, "Əli")
        assertEquals(Action.Call(FamilyContact("sos:103", "Ambulance", "103"), false), yes.last())

        assertTrue(dm.handle("what time is it", family, "Əli").single() is Action.Device)
        assertEquals(listOf(Action.Repeat), dm.handle("say that again", family, "Əli"))
        assertEquals("Send «I will be late» to Leyla?", (dm.handle("text my daughter that I'll be late", family, "Əli").single() as Action.Say).text)
    }
}
