package az.nvaton.komekci.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import az.nvaton.komekci.core.AlarmTime
import az.nvaton.komekci.core.QuickIntent
import az.nvaton.komekci.core.QuickMatch
import az.nvaton.komekci.core.tr
import az.nvaton.komekci.komekci
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Fast commands that the phone answers by itself, in a moment and without the internet: the time, the battery,
 * volume, the torch, opening apps, medicine notes. Each returns the sentence to say.
 */
object PhoneDevice {

    fun handle(context: Context, m: QuickMatch): String = when (m.intent) {
        QuickIntent.TIME -> tr("Saat ${AlarmTime.clock(ZonedDateTime.now())}.", "It's ${AlarmTime.clock(ZonedDateTime.now())}.")
        QuickIntent.DATE -> date()
        QuickIntent.BATTERY -> battery(context)
        QuickIntent.INTERNET -> internet(context)
        QuickIntent.VOLUME_UP -> volume(context, AudioManager.ADJUST_RAISE, steps = 2, tr("Səsi artırdım.", "Louder now."))
        QuickIntent.VOLUME_DOWN -> volume(context, AudioManager.ADJUST_LOWER, steps = 2, tr("Səsi azaltdım.", "Quieter now."))
        QuickIntent.VOLUME_MAX -> {
            val audio = context.getSystemService(AudioManager::class.java)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI)
            tr("Səs ən yüksəkdədir.", "The volume is at maximum.")
        }
        QuickIntent.MUTE -> ringer(context, AudioManager.RINGER_MODE_VIBRATE, tr("Telefon indi yalnız titrəyəcək.", "The phone will only vibrate now."))
        QuickIntent.SOUND_ON -> ringer(context, AudioManager.RINGER_MODE_NORMAL, tr("Səs açıqdır.", "The sound is on."))
        QuickIntent.FLASHLIGHT_ON -> torch(context, true)
        QuickIntent.FLASHLIGHT_OFF -> torch(context, false)
        QuickIntent.BRIGHTNESS -> open(context, Intent(Settings.ACTION_DISPLAY_SETTINGS), tr("Ekran parametrlərini açdım. Parlaqlığı zolaqla dəyişin.", "I opened the display settings. Slide the bar to change the brightness."))
        QuickIntent.WIFI -> open(
            context,
            Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS),
            tr("Wi-Fi parametrləri açıqdır.", "Here are the Wi-Fi settings."),
        )
        QuickIntent.BLUETOOTH -> open(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS), tr("Bluetooth parametrləri açıqdır.", "Here are the Bluetooth settings."))
        QuickIntent.OPEN_APP -> openApp(context, m.app.orEmpty())
        QuickIntent.MEDICINE_TAKEN -> {
            val now = ZonedDateTime.now()
            HealthLog.medicineTaken(context, now.toInstant().toEpochMilli())
            tr("Qeyd etdim: dərmanı saat ${AlarmTime.clock(now)}-da içdiniz.", "Noted: you took your medicine at ${AlarmTime.clock(now)}.")
        }
        QuickIntent.MEDICINE_CHECK -> {
            val today = HealthLog.medicineToday(context)
            if (today == null) {
                tr("Bu gün dərman içdiyinizə dair qeyd yoxdur.", "I have no note that you took your medicine today.")
            } else {
                val at = AlarmTime.clock(Instant.ofEpochMilli(today).atZone(ZoneId.systemDefault()))
                tr("Bəli, bu gün saat $at-da içmisiniz.", "Yes, you took it today at $at.")
            }
        }
        QuickIntent.SLOWER -> rate(context, -0.15f, tr("Daha yavaş danışacağam.", "I'll speak more slowly."))
        QuickIntent.FASTER -> rate(context, +0.15f, tr("Daha tez danışacağam.", "I'll speak a little faster."))
        else -> tr("Bağışlayın, bunu bacarmadım.", "Sorry, I couldn't do that.")
    }

    private fun date(): String {
        val today = LocalDate.now()
        val en = today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", java.util.Locale.US))
        val az = today.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", java.util.Locale.forLanguageTag("az")))
        return tr("Bu gün $az.", "Today is $en.")
    }

    private fun battery(context: Context): String {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return when {
            charging -> tr("Batareya $level faizdir, şarj olunur.", "The battery is at $level percent and charging.")
            level <= 15 -> tr("Batareya $level faizdir. Telefonu şarja qoyun.", "The battery is at $level percent. Please charge the phone.")
            else -> tr("Batareya $level faizdir.", "The battery is at $level percent.")
        }
    }

    private fun internet(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return when {
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                tr("İnternet bağlantısı yoxdur.", "There is no internet connection.")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> tr("İnternet var, Wi-Fi ilə.", "You're online through Wi-Fi.")
            else -> tr("İnternet var, mobil şəbəkə ilə.", "You're online through mobile data.")
        }
    }

    private fun volume(context: Context, direction: Int, steps: Int, done: String): String {
        val audio = context.getSystemService(AudioManager::class.java)
        repeat(steps) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, if (it == steps - 1) AudioManager.FLAG_SHOW_UI else 0) }
        return done
    }

    private fun ringer(context: Context, mode: Int, done: String): String = try {
        context.getSystemService(AudioManager::class.java).ringerMode = mode
        done
    } catch (e: SecurityException) { // "Do not disturb" access is needed for this on some phones
        open(context, Intent(Settings.ACTION_SOUND_SETTINGS), tr("Səs parametrlərini açdım.", "I opened the sound settings."))
    }

    private fun torch(context: Context, on: Boolean): String = try {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return tr("Bu telefonda fənər yoxdur.", "This phone has no flashlight.")
        cm.setTorchMode(id, on)
        if (on) tr("Fənər yanır.", "The flashlight is on.") else tr("Fənəri söndürdüm.", "The flashlight is off.")
    } catch (e: Exception) {
        tr("Fənəri idarə etmək alınmadı. Kamera açıqdırsa, bağlayın.", "I couldn't switch the flashlight. Close the camera if it's open.")
    }

    private fun rate(context: Context, delta: Float, done: String): String {
        val settings = context.komekci.settings
        settings.speechRate = (settings.speechRate + delta).coerceIn(0.6f, 1.3f)
        context.komekci.speaker.applyRate()
        return done
    }

    private fun openApp(context: Context, app: String): String {
        val name = NAMES[app] ?: app
        val intent = when (app) {
            "whatsapp" -> launcher(context, "com.whatsapp") ?: launcher(context, "com.whatsapp.w4b")
            "youtube" -> launcher(context, "com.google.android.youtube")
            "telegram" -> launcher(context, "org.telegram.messenger")
            "facebook" -> launcher(context, "com.facebook.katana")
            "instagram" -> launcher(context, "com.instagram.android")
            "store" -> launcher(context, "com.android.vending")
            "maps" -> launcher(context, "com.google.android.apps.maps") ?: category(Intent.CATEGORY_APP_MAPS)
            "gallery" -> category(Intent.CATEGORY_APP_GALLERY)
            "calendar" -> category(Intent.CATEGORY_APP_CALENDAR)
            "messages" -> category(Intent.CATEGORY_APP_MESSAGING)
            "music" -> category(Intent.CATEGORY_APP_MUSIC)
            "contacts" -> category(Intent.CATEGORY_APP_CONTACTS)
            "browser" -> category(Intent.CATEGORY_APP_BROWSER)
            "email" -> category(Intent.CATEGORY_APP_EMAIL)
            "calculator" -> category(Intent.CATEGORY_APP_CALCULATOR)
            "clock" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "phone" -> Intent(Intent.ACTION_DIAL)
            else -> null
        } ?: return tr("$name bu telefonda tapılmadı.", "I couldn't find $name on this phone.")
        return open(context, intent, tr("$name açılır.", "Opening $name."))
    }

    private fun launcher(context: Context, pkg: String): Intent? = context.packageManager.getLaunchIntentForPackage(pkg)

    private fun category(category: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)

    private fun open(context: Context, intent: Intent, done: String): String = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        done
    } catch (e: ActivityNotFoundException) {
        tr("Bunu bu telefonda aça bilmədim.", "I couldn't open that on this phone.")
    }

    private val NAMES = mapOf(
        "whatsapp" to "WhatsApp", "youtube" to "YouTube", "telegram" to "Telegram", "facebook" to "Facebook",
        "instagram" to "Instagram", "store" to "Play Store", "maps" to "Maps", "gallery" to "Gallery", "calendar" to "Calendar",
        "messages" to "Messages", "music" to "Music", "contacts" to "Contacts", "browser" to "Browser", "email" to "Email",
        "calculator" to "Calculator", "clock" to "Clock", "settings" to "Settings", "phone" to "Phone",
    )
}

/** When medicine was taken, said by voice ("I took my medicine"). Kept on the phone, the last 60 notes. */
object HealthLog {
    private const val PREFS = "health"
    private const val KEY = "medicine"

    fun medicineTaken(context: Context, at: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = (prefs.getString(KEY, null).orEmpty().split(',').mapNotNull { it.toLongOrNull() } + at).takeLast(60)
        prefs.edit().putString(KEY, list.joinToString(",")).apply()
    }

    /** The last time today, or null. */
    fun medicineToday(context: Context): Long? {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null).orEmpty()
            .split(',').mapNotNull { it.toLongOrNull() }.lastOrNull { it >= start }
    }
}
