package az.nvaton.komekci.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeAndCameraTest {
    private val nlu = LocalNlu()

    // Transcripts below are what the offline Turkish model actually wrote for these phrases.

    @Test
    fun `the name alone wakes the assistant`() {
        assertEquals(0, WakePhrase.match("ali", "Əli"))
        assertEquals(0, WakePhrase.match("eli", "Əli"))
        assertEquals(0, WakePhrase.match("Əli", "Əli"))
    }

    @Test
    fun `a greeting before the name is fine`() {
        assertEquals(0, WakePhrase.match("selam eli", "Əli"))
        assertEquals(0, WakePhrase.match("hey ali", "Əli"))
    }

    @Test
    fun `a stray word from a noise before the name is tolerated in a short transcript`() {
        assertEquals(0, WakePhrase.match("başkan ali", "Əli"))
        assertNull(WakePhrase.match("onun eli ağrıyor", "Əli"))
    }

    @Test
    fun `a command right after the name counts its words`() {
        assertEquals(3, WakePhrase.match("ali ne ve beyaz", "Əli"))
        assertEquals(5, WakePhrase.match("eli ne ve mesela m yaz", "Əli"))
    }

    @Test
    fun `the name in the middle of a sentence or a similar word does not wake it`() {
        assertNull(WakePhrase.match("onu milyar hayır", "Əli"))
        assertNull(WakePhrase.match("onun eli ağrıyor", "Əli"))
        assertNull(WakePhrase.match("allah razı olsun", "Əli"))
        assertNull(WakePhrase.match("elimde kitaplar", "Əli"))
        assertNull(WakePhrase.match("alınan correct", "Əli"))
        assertNull(WakePhrase.match("aile geldi", "Əli"))
        assertNull(WakePhrase.match("alo susun", "Əli"))
        assertNull(WakePhrase.match("", "Əli"))
    }

    @Test
    fun `other assistant names work with Turkish spelling`() {
        assertEquals(0, WakePhrase.match("hanım", "Xanım"))
        assertEquals(0, WakePhrase.match("ayla", "Ayla"))
        assertNull(WakePhrase.match("ali", "Ayla"))
    }

    @Test
    fun `dotted capital i from the recognizer is folded`() {
        assertEquals(0, WakePhrase.match("Ali̇", "Əli"))
    }

    @Test
    fun `just opening the camera`() {
        assertTrue(nlu.isJustOpenCamera("kameranı aç"))
        assertTrue(nlu.isJustOpenCamera("zəhmət olmasa kameranı açın"))
        assertTrue(nlu.isJustOpenCamera("lupa"))
        assertFalse(nlu.isJustOpenCamera("bu dərmanı oxu"))
        assertFalse(nlu.isJustOpenCamera("kamera ilə bax gör bu nədir"))
    }

    @Test
    fun `closing the camera by voice`() {
        assertTrue(nlu.isCloseCamera("kameranı bağla"))
        assertTrue(nlu.isCloseCamera("bağla"))
        assertTrue(nlu.isCloseCamera("bəsdir, geri"))
        assertFalse(nlu.isCloseCamera("bunun qapağını necə bağlayım"))
        assertFalse(nlu.isCloseCamera("geri qaytarmaq olar?"))
        assertFalse(nlu.isCloseCamera("son istifadə tarixi nədir"))
    }
}
