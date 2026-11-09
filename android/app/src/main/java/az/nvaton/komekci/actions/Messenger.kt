package az.nvaton.komekci.actions

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import az.nvaton.komekci.a11y.PendingSend
import az.nvaton.komekci.a11y.WhatsAppDriver
import az.nvaton.komekci.core.FamilyContact
import az.nvaton.komekci.core.PhoneNumbers

object Messenger {
    val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    enum class Result { WHATSAPP, SMS, FAILED }

    /**
     * Opens the WhatsApp chat with the confirmed text already typed. If our accessibility service
     * is on, it presses "Send"; otherwise the user taps it. Falls back to a prefilled SMS.
     */
    fun sendMessage(context: Context, contact: FamilyContact, text: String): Result {
        if (contact.isWhatsAppSearch) { // a group or someone without a number: find the chat by name
            return if (WhatsAppDriver.start(context, contact.name, text)) Result.WHATSAPP else Result.FAILED
        }
        val number = PhoneNumbers.international(contact.phone) // wa.me wants 994501234567, also for "050 123 45 67"
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${Uri.encode(text)}")
        for (pkg in WHATSAPP_PACKAGES) {
            val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                PendingSend.arm(contact.name, text)
                context.startActivity(intent)
                return Result.WHATSAPP
            } catch (e: ActivityNotFoundException) {
                PendingSend.clear()
            }
        }
        return try {
            val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${contact.phone}"))
                .putExtra("sms_body", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(sms)
            Result.SMS
        } catch (e: ActivityNotFoundException) {
            Result.FAILED
        }
    }

    /** Opens the WhatsApp chat with [contact] without typing anything, so the user can read it. */
    fun openChat(context: Context, contact: FamilyContact): Boolean {
        if (contact.isWhatsAppSearch) return WhatsAppDriver.start(context, contact.name, null)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${PhoneNumbers.international(contact.phone)}")
        return WHATSAPP_PACKAGES.any { pkg ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
        }
    }

    /**
     * Shares a recorded voice message (.m4a) into WhatsApp. With a known number it opens that chat ready to send;
     * for a name-only contact WhatsApp shows its own picker. Either way the user taps WhatsApp's own send button.
     */
    fun sendVoice(context: Context, contact: FamilyContact, file: File): Result {
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            return Result.FAILED
        }
        for (pkg in WHATSAPP_PACKAGES) {
            if (context.packageManager.getLaunchIntentForPackage(pkg) == null) continue
            val intent = Intent(Intent.ACTION_SEND).apply {
                setPackage(pkg)
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                // Straight to the chat when the number is known; otherwise WhatsApp asks whom to send to.
                if (!contact.isWhatsAppSearch && contact.phone.isNotBlank()) {
                    putExtra("jid", PhoneNumbers.international(contact.phone) + "@s.whatsapp.net")
                }
            }
            try {
                context.startActivity(intent)
                return Result.WHATSAPP
            } catch (e: ActivityNotFoundException) {
                // try the next WhatsApp package
            }
        }
        return Result.FAILED
    }

    /** Calls directly when CALL_PHONE is granted, otherwise opens the dialer with the number. */
    fun call(context: Context, contact: FamilyContact): Boolean {
        val uri = Uri.parse("tel:" + contact.phone.filter { it.isDigit() || it == '+' })
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
