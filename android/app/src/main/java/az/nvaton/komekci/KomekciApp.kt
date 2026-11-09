package az.nvaton.komekci

import android.app.Application
import android.content.Context
import az.nvaton.komekci.core.I18n
import az.nvaton.komekci.core.QuickCommands
import az.nvaton.komekci.data.AppSettings
import az.nvaton.komekci.data.ContactsRepo
import az.nvaton.komekci.net.BackendApi
import az.nvaton.komekci.voice.Speaker

/** App-wide singletons shared by the UI and the background services. */
class KomekciApp : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var contacts: ContactsRepo
        private set
    lateinit var api: BackendApi
        private set
    lateinit var speaker: Speaker
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        I18n.lang = settings.language // before anything builds a sentence or a voice
        contacts = ContactsRepo(this)
        QuickCommands.english = runCatching { QuickCommands.parse(assets.open("commands/en.tsv").bufferedReader().use { it.readText() }) }.getOrNull()
        api = BackendApi(settings)
        speaker = Speaker(this, settings, api)
    }
}

val Context.komekci: KomekciApp
    get() = applicationContext as KomekciApp
