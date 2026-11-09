package az.nvaton.komekci.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AzTest {
    @Test
    fun `dative follows vowel harmony`() {
        assertEquals("Murada", Az.dative("Murad"))
        assertEquals("Aysuya", Az.dative("Aysu"))
        assertEquals("Əliyə", Az.dative("Əli"))
        assertEquals("Rəşada", Az.dative("Rəşad"))
        assertEquals("Leylaya", Az.dative("Leyla"))
        assertEquals("Elvinə", Az.dative("Elvin"))
        assertEquals("Ülviyyəyə", Az.dative("Ülviyyə"))
    }

    @Test
    fun `fold removes diacritics, case and punctuation`() {
        assertEquals("neveme", Az.fold("Nəvəmə,"))
        assertEquals("oglum", Az.fold("OĞLUM"))
        assertEquals("indi", Az.fold("İndi"))
    }

    @Test
    fun `capitalizeFirst uses Azerbaijani rules`() {
        assertEquals("İçdim", Az.capitalizeFirst("içdim"))
    }

    @Test
    fun `orList joins with yoxsa`() {
        assertEquals("Murada, yoxsa Aysuya", Az.orList(listOf("Murada", "Aysuya")))
        assertEquals("A, B, yoxsa C", Az.orList(listOf("A", "B", "C")))
    }

    @Test
    fun `yes and no detection`() {
        val nlu = LocalNlu()
        assertTrue(nlu.isYes("hə"))
        assertTrue(nlu.isYes("Bəli, göndər"))
        assertTrue(nlu.isYes("yaz"))
        assertFalse(nlu.isYes("yox"))
        assertFalse(nlu.isYes("göndərmə"))
        assertFalse(nlu.isYes("hə, yox, dayan"))
        assertTrue(nlu.isNo("lazım deyil"))
    }
}
