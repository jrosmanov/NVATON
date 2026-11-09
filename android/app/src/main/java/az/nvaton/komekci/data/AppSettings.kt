package az.nvaton.komekci.data

import android.content.Context
import androidx.core.content.edit
import az.nvaton.komekci.core.Lang

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var assistantName: String
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
        set(value) = prefs.edit { putString(KEY_NAME, value.trim()) }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
        set(value) = prefs.edit { putString(KEY_SERVER, value.trim().trimEnd('/')) }

    /** Must match APP_TOKEN in backend/.env when the backend listens on Wi-Fi. */
    var appToken: String
        get() = prefs.getString(KEY_TOKEN, null) ?: ""
        set(value) = prefs.edit { putString(KEY_TOKEN, value.trim()) }

    var useServerVoice: Boolean
        get() = prefs.getBoolean(KEY_VOICE, true)
        set(value) = prefs.edit { putBoolean(KEY_VOICE, value) }


    /**
     * Prefer the server's speech recognition (Gemini or Azure) whenever the backend offers it.
     * On by default: Google's on-device recognizer has no Azerbaijani model.
     */
    var useServerSpeech: Boolean
        get() = prefs.getBoolean(KEY_SERVER_SPEECH, true)
        set(value) = prefs.edit { putBoolean(KEY_SERVER_SPEECH, value) }

    /** Start listening when the user says the assistant's name, like "Hey Siri" (offline, on the phone). */
    var wakeWord: Boolean
        get() = prefs.getBoolean(KEY_WAKE, true)
        set(value) = prefs.edit { putBoolean(KEY_WAKE, value) }

    /** Read incoming messages, missed calls and reminders aloud (needs notification access). */
    var announceNotifications: Boolean
        get() = prefs.getBoolean(KEY_ANNOUNCE, true)
        set(value) = prefs.edit { putBoolean(KEY_ANNOUNCE, value) }

    /**
     * The phone's address book may be used: adding people from it, messaging anyone in it, and saving new numbers
     * there too. Asked for in Settings or on the import screen; on by default only if contacts access was given.
     */
    var contactsIntegration: Boolean
        get() = if (prefs.contains(KEY_CONTACTS)) prefs.getBoolean(KEY_CONTACTS, false) else contactsGranted()
        set(value) = prefs.edit { putBoolean(KEY_CONTACTS, value) }

    private val appContext = context.applicationContext

    private fun contactsGranted() = androidx.core.content.ContextCompat.checkSelfPermission(
        appContext,
        android.Manifest.permission.READ_CONTACTS,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** How fast the voice speaks; "speak slower" / "speak faster" change it. 0.9 is a little slower than normal. */
    var speechRate: Float
        get() = prefs.getFloat(KEY_RATE, 0.9f)
        set(value) = prefs.edit { putFloat(KEY_RATE, value) }

    /** "az" (default) or "en": the language of the screens, the voice, the commands and the AI's answers. */
    var language: Lang
        get() = Lang.of(prefs.getString(KEY_LANGUAGE, null))
        set(value) = prefs.edit { putString(KEY_LANGUAGE, value.code) }

    companion object {
        const val DEFAULT_NAME = "Əli"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_ANNOUNCE = "announce_notifications"
        private const val KEY_CONTACTS = "contacts_integration"
        private const val KEY_RATE = "speech_rate"
        /** Works on a USB-connected phone or the emulator after `adb reverse tcp:8787 tcp:8787`. */
        const val DEFAULT_SERVER = "http://127.0.0.1:8787"
        private const val KEY_NAME = "assistant_name"
        private const val KEY_SERVER = "server_url"
        private const val KEY_TOKEN = "app_token"
        private const val KEY_VOICE = "server_voice"
        private const val KEY_SERVER_SPEECH = "server_speech"
        private const val KEY_WAKE = "wake_word"
    }
}
