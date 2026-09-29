package com.knightdx.glassestunes

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Notification access does two jobs:
 *  - Android only lets apps control other apps' media sessions (Samsung
 *    Music's play/pause/skip) if they hold it.
 *  - It lets us read incoming *message* notifications aloud and reply to them
 *    through their own reply button, the same way Android Auto and
 *    smartwatches do. That works for WhatsApp, Messenger, Telegram, Signal,
 *    Samsung Messages and any app with a reply button.
 * Messages are only kept in memory and never leave the phone.
 */
class MediaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        GlassesService.instance?.refresh()
        val current: Array<out StatusBarNotification> = try {
            activeNotifications.orEmpty()
        } catch (e: RuntimeException) {
            Log.w("MessageInbox", "couldn't read current notifications", e)
            emptyArray()
        }
        for (sbn in current) {
            try {
                MessageInbox.add(this, sbn)
            } catch (e: Throwable) {
                Log.w("MessageInbox", "skipped a notification from ${sbn.packageName}", e)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        // Other apps' notifications can hold data we can't unpack; never let that crash us.
        val entry = try {
            MessageInbox.add(this, sbn)
        } catch (e: Throwable) {
            Log.w("MessageInbox", "skipped a notification from ${sbn.packageName}", e)
            null
        } ?: return
        try {
            GlassesService.instance?.onNewMessage(entry)
        } catch (e: RuntimeException) {
            Log.w("MessageInbox", "couldn't announce message", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        MessageInbox.remove(sbn.key)
    }
}

data class InboxEntry(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val sender: String,
    val text: String,
    val time: Long,
    val reply: Notification.Action?,
) {
    val canReply get() = reply != null
}

/** Recent incoming messages, newest last. */
object MessageInbox {
    private const val MAX = 30
    private val entries = ArrayList<InboxEntry>()
    private val alreadyRead = HashSet<String>()

    @Synchronized
    fun all(): List<InboxEntry> = entries.toList()

    @Synchronized
    fun unread(): List<InboxEntry> = entries.filter { readKey(it) !in alreadyRead }

    @Synchronized
    fun markRead(e: InboxEntry) {
        alreadyRead += readKey(e)
    }

    private fun readKey(e: InboxEntry) = "${e.key}|${e.text}"

    @Synchronized
    fun remove(key: String) {
        entries.removeAll { it.key == key }
    }

    /** Latest message from someone whose name matches [spoken], or the latest overall. */
    @Synchronized
    fun latest(spoken: String? = null, needsReply: Boolean = false): InboxEntry? {
        val pool = entries.filter { !needsReply || it.canReply }
        if (spoken == null) return pool.lastOrNull()
        return pool.filter { LibraryMatcher.score(it.sender, spoken) >= 70 }.lastOrNull()
    }

    @Synchronized
    fun senders(): List<String> = entries.map { it.sender }.distinct()

    @Synchronized
    fun put(entry: InboxEntry) {
        entries.removeAll { it.key == entry.key }
        entries += entry
        while (entries.size > MAX) entries.removeAt(0)
    }

    fun add(context: Context, sbn: StatusBarNotification): InboxEntry? {
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return null
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val isMessage = n.category == Notification.CATEGORY_MESSAGE || reply != null
        if (!isMessage) return null

        val extras = n.extras
        val (sender, text) = lastMessage(extras) ?: return null
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val from = if (conversation != null && conversation != sender) "$sender in $conversation" else sender
        val label = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        val entry = InboxEntry(sbn.key, sbn.packageName, label, from, text, sbn.postTime, reply)
        put(entry)
        return entry
    }

    /** Sender and text of the newest message in a (possibly MessagingStyle) notification. */
    private fun lastMessage(extras: Bundle): Pair<String, String>? {
        @Suppress("DEPRECATION")
        val messages: Array<Parcelable>? = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = messages?.lastOrNull() as? Bundle
        if (last != null) {
            val text = last.getCharSequence("text")?.toString()
            val sender = if (Build.VERSION.SDK_INT >= 28) {
                @Suppress("DEPRECATION")
                (last.getParcelable<Person>("sender_person"))?.name?.toString()
            } else {
                null
            } ?: last.getCharSequence("sender")?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            if (!text.isNullOrBlank() && !sender.isNullOrBlank()) return sender to text
        }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString() ?: return null
        return title to text
    }

    /** Replies through the notification's own reply button. */
    fun reply(context: Context, entry: InboxEntry, message: String): Boolean {
        val action = entry.reply ?: return false
        val inputs = action.remoteInputs ?: return false
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, message) } }
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            action.actionIntent.send(context, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w("MessageInbox", "reply button expired", e)
            false
        }
    }
}
