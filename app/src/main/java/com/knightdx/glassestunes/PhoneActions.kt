package com.knightdx.glassestunes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log

/**
 * Calls, texts and WhatsApp through the phone's normal Android APIs. Calls and
 * texts work with the screen off and locked; WhatsApp needs the phone unlocked
 * because it has no API for sending, so the accessibility service presses Send.
 */
class PhoneActions(private val context: Context) {

    private fun has(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    val canReadContacts get() = has(Manifest.permission.READ_CONTACTS)
    val canCall get() = has(Manifest.permission.CALL_PHONE)
    val canText get() = has(Manifest.permission.SEND_SMS)
    val canAnswer get() = has(Manifest.permission.ANSWER_PHONE_CALLS)

    fun contacts(): List<Contact> {
        if (!canReadContacts) return emptyList()
        val phones = HashMap<Long, MutableList<Phone>>()
        val names = HashMap<Long, String>()
        context.contentResolver.query(
            CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                CommonDataKinds.Phone.CONTACT_ID,
                CommonDataKinds.Phone.DISPLAY_NAME,
                CommonDataKinds.Phone.NUMBER,
                CommonDataKinds.Phone.TYPE,
                CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                names[id] = c.getString(1) ?: continue
                val number = c.getString(2) ?: continue
                val kind = when (c.getInt(3)) {
                    CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
                    CommonDataKinds.Phone.TYPE_WORK, CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "work"
                    CommonDataKinds.Phone.TYPE_HOME -> "home"
                    else -> "other"
                }
                val list = phones.getOrPut(id) { mutableListOf() }
                // Contacts synced from several accounts often repeat numbers.
                if (list.none { it.number.filter(Char::isDigit) == number.filter(Char::isDigit) }) {
                    list += Phone(number, kind, primary = c.getInt(4) != 0)
                }
            }
        }
        val whatsapp = HashMap<Long, String>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(WHATSAPP_PROFILE_MIME),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                c.getString(1)?.takeIf { it.endsWith("@s.whatsapp.net") }?.let { whatsapp.putIfAbsent(id, it) }
            }
        }
        return names.map { (id, name) -> Contact(id, name, phones[id].orEmpty(), whatsapp[id]) }
    }

    fun call(number: String): Boolean {
        if (!canCall) return false
        return try {
            context.getSystemService(TelecomManager::class.java)
                .placeCall(Uri.fromParts("tel", number, null), Bundle())
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "call refused", e)
            false
        }
    }

    fun answer(): Boolean {
        if (!canAnswer) return false
        return try {
            context.getSystemService(TelecomManager::class.java).acceptRingingCall()
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun decline(): Boolean {
        if (!canAnswer || Build.VERSION.SDK_INT < 28) return false
        return try {
            @Suppress("DEPRECATION")
            context.getSystemService(TelecomManager::class.java).endCall()
        } catch (e: SecurityException) {
            false
        }
    }

    /** Sends an SMS from your normal number. It shows up in Samsung Messages like any other text. */
    fun sendSms(number: String, message: String): Boolean {
        if (!canText) return false
        return try {
            val sms = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = sms.divideMessage(message)
            if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
            else sms.sendTextMessage(number, null, message, null, null)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "SMS failed", e)
            false
        }
    }

    fun hasWhatsApp(): Boolean = context.packageManager.getLaunchIntentForPackage(WHATSAPP) != null

    /** Opens the chat with the message typed in. Pressing Send is up to the accessibility service. */
    fun openWhatsAppChat(jid: String, message: String, launcher: AppLauncher): Boolean {
        val digits = jid.substringBefore('@')
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=${Uri.encode(message)}"))
            .setPackage(WHATSAPP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launcher.startIntent(intent, "WhatsApp")
    }

    companion object {
        private const val TAG = "PhoneActions"
        const val WHATSAPP = "com.whatsapp"
        private const val WHATSAPP_PROFILE_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
    }
}
