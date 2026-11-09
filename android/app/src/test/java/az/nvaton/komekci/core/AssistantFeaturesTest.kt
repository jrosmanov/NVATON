package az.nvaton.komekci.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Language by voice, alarms, reading messages and notifications. */
class AssistantFeaturesTest {
    private val leyla = FamilyContact("leyla", "Leyla", "+994000000003", listOf("qızım"))
    private val dm = DialogManager()
    private val nlu = LocalNlu()
    private val baku = ZoneId.of("Asia/Baku")
    private val now = ZonedDateTime.of(2026, 11, 8, 17, 10, 0, 0, baku) // Sunday 17:10

    @After
    fun back() {
        I18n.lang = Lang.AZ
    }

    private fun handle(text: String) = dm.handle(text, listOf(leyla), "Əli")
    private fun server(intent: String, alarm: AlarmSpec = AlarmSpec(), ids: List<String> = emptyList(), language: String = "") =
        dm.handleServerIntent(ServerIntent(intent, emptyList(), "", false, "", language, alarm, ids), listOf(leyla), "")

    @Test
    fun `language switches by voice in both languages`() {
        assertEquals(listOf(Action.SetLanguage(Lang.EN)), handle("Əli, dili ingilis dilinə dəyiş"))
        assertEquals(listOf(Action.SetLanguage(Lang.EN)), handle("ingiliscə danış"))
        assertEquals(listOf(Action.SetLanguage(Lang.AZ)), handle("Azərbaycan dilinə keç"))
        assertEquals(listOf(Action.SetLanguage(Lang.EN)), server("set_language", language = "en"))
        I18n.lang = Lang.EN
        assertEquals(listOf(Action.SetLanguage(Lang.AZ)), handle("Ali, switch to Azerbaijani"))
        assertEquals(listOf(Action.SetLanguage(Lang.AZ)), handle("speak Azerbaijani please"))
    }

    @Test
    fun `a message about English lessons is still a message`() {
        handle("qızıma yaz ki ingilis dili kursuna getsin")
        assertEquals(DialogManager.Pending.Confirm(leyla, "İngilis dili kursuna getsin"), dm.pending)
    }

    @Test
    fun `read my messages, not the camera`() {
        assertEquals(listOf(Action.ReadMessages), handle("Əli, mesajlarımı oxu"))
        assertEquals(listOf(Action.ReadMessages), handle("yeni mesaj var?"))
        assertEquals(listOf(Action.ReadMessages), handle("kim yazıb?"))
        assertEquals(listOf(Action.ReadMessages), server("read_messages"))
        I18n.lang = Lang.EN
        assertEquals(listOf(Action.ReadMessages), handle("read my messages"))
        assertEquals(listOf(Action.ReadMessages), handle("who texted me"))
        assertEquals(listOf(Action.AskServer("send a new message to Leyla")), handle("send a new message to Leyla"))
    }

    @Test
    fun `alarms - list and delete all locally, setting one goes to the AI`() {
        assertEquals(listOf(Action.ListAlarms), handle("zəngli saatlarım hansılardır"))
        assertEquals(listOf(Action.ListAlarms), handle("hansı zəngli saatlar var"))
        assertEquals(listOf(Action.CancelAlarms(emptyList(), all = true)), handle("bütün zəngli saatları sil"))
        assertEquals(listOf(Action.AskServer("sabah səhər 7-də zəngli saat qur")), handle("sabah səhər 7-də zəngli saat qur"))
        assertEquals(listOf(Action.AskServer("7-dəki zəngli saatı sil")), handle("7-dəki zəngli saatı sil"))
        I18n.lang = Lang.EN
        assertEquals(listOf(Action.ListAlarms), handle("what alarms do I have"))
        assertEquals(listOf(Action.CancelAlarms(emptyList(), all = true)), handle("delete all alarms"))
        assertEquals(listOf(Action.AskServer("set an alarm for 6:45")), handle("set an alarm for 6:45"))
    }

    @Test
    fun `server alarm intents become actions`() {
        val spec = AlarmSpec(hour = 7, minute = 30, dayOffset = 1)
        assertEquals(listOf(Action.SetAlarm(spec)), server("set_alarm", spec))
        assertEquals("Saat neçəyə qurum?", (server("set_alarm", AlarmSpec()).single() as Action.Say).text)
        assertEquals(listOf(Action.CancelAlarms(listOf("a2"), all = false)), server("cancel_alarms", ids = listOf("a2")))
        assertEquals(listOf(Action.CancelAlarms(emptyList(), all = true)), server("cancel_alarms"))
        assertEquals(listOf(Action.ListAlarms), server("list_alarms"))
    }

    @Test
    fun `alarm times`() {
        val later = AlarmTime.firstTrigger(AlarmSpec(hour = 21, minute = 0), now)!!
        assertEquals(ZonedDateTime.of(2026, 11, 8, 21, 0, 0, 0, baku), later)
        val passed = AlarmTime.firstTrigger(AlarmSpec(hour = 7, minute = 30), now)!!
        assertEquals(ZonedDateTime.of(2026, 11, 9, 7, 30, 0, 0, baku), passed)
        val dayAfter = AlarmTime.firstTrigger(AlarmSpec(hour = 7, minute = 0, dayOffset = 2), now)!!
        assertEquals(ZonedDateTime.of(2026, 11, 10, 7, 0, 0, 0, baku), dayAfter)
        assertEquals(now.plusMinutes(10), AlarmTime.firstTrigger(AlarmSpec(inMinutes = 10), now))
        assertNull(AlarmTime.firstTrigger(AlarmSpec(hour = 25), now))
        assertEquals(ZonedDateTime.of(2026, 11, 9, 21, 0, 0, 0, baku), AlarmTime.nextDaily(later, later))
    }

    @Test
    fun `alarm phrases in both languages`() {
        val tomorrow = ZonedDateTime.of(2026, 11, 9, 7, 30, 0, 0, baku)
        assertEquals("sabah, saat 07:30", AlarmTime.describe(tomorrow, false, "", now))
        assertEquals("hər gün, saat 21:00, «Dərman»", AlarmTime.describe(now.withHour(21).withMinute(0), true, "Dərman", now))
        I18n.lang = Lang.EN
        assertEquals("tomorrow at 7:30 AM", AlarmTime.describe(tomorrow, false, "", now))
        assertEquals("in 10 minutes (at 5:20 PM)", AlarmTime.inMinutesPhrase(10, now.plusMinutes(10)))
    }

    @Test
    fun `notifications are announced in the chosen language`() {
        val msg = Notice("WhatsApp", "Murad", "Salam baba, axşam gələcəyəm", postedAt = 0)
        assertEquals("Yeni mesaj, WhatsApp. Murad: Salam baba, axşam gələcəyəm", Notices.announcement(msg))
        val group = msg.copy(conversation = "Ailə")
        assertEquals("Yeni mesaj, WhatsApp, «Ailə» qrupu. Murad: Salam baba, axşam gələcəyəm", Notices.announcement(group))
        assertEquals("Buraxılmış zəng: Murad.", Notices.announcement(msg.copy(kind = Notice.Kind.MISSED_CALL)))
        assertTrue(Notices.summary(listOf(msg.copy(postedAt = 0)), 5 * 60_000).startsWith("Bir yeni mesaj. Murad, WhatsApp, 5 dəqiqə əvvəl: Salam baba"))
        assertEquals("Yeni mesaj yoxdur.", Notices.summary(emptyList(), 0))
        I18n.lang = Lang.EN
        assertEquals("New WhatsApp message in «Ailə» from Murad: Salam baba, axşam gələcəyəm", Notices.announcement(group))
        assertEquals("Missed call from Murad.", Notices.announcement(msg.copy(kind = Notice.Kind.MISSED_CALL)))
    }
}
