package az.nvaton.komekci.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** The language the user chose in Settings: what they speak to the assistant and what it answers in. */
enum class Lang(val code: String, val speechTag: String, val locale: Locale) {
    AZ("az", "az-AZ", Locale.forLanguageTag("az-AZ")),
    EN("en", "en-US", Locale.US),
    ;

    companion object {
        fun of(code: String?): Lang = entries.firstOrNull { it.code == code } ?: AZ
    }
}

object I18n {
    /** Compose state, so every screen redraws when the language changes. Set from [az.nvaton.komekci.data.AppSettings]. */
    var lang by mutableStateOf(Lang.AZ)

    val english: Boolean get() = lang == Lang.EN
}

/** The text in the chosen language: `tr("Salam", "Hello")`. */
fun tr(az: String, en: String): String = if (I18n.lang == Lang.EN) en else az
