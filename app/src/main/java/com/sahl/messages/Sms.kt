package com.sahl.messages

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person as NPerson
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract.PhoneLookup
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap

data class Conversation(val threadId: Long, val address: String, val snippet: String, val date: Long,
                        val unread: Int, val outgoing: Boolean, val name: String?, val photo: String?)

data class Msg(val id: Long, val body: String, val date: Long, val type: Int, val read: Boolean, val subId: Int) {
    val outgoing get() = type != Telephony.Sms.MESSAGE_TYPE_INBOX
    val failed get() = type == Telephony.Sms.MESSAGE_TYPE_FAILED
    val pending get() = type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || type == Telephony.Sms.MESSAGE_TYPE_QUEUED
}

data class Sim(val subId: Int, val label: String)

/** Names come from Sahl Contacts (Sahl Union) or Android contacts. */
object Names {
    private val cache = ConcurrentHashMap<String, Pair<String?, String?>>()
    fun of(c: Context, number: String): Pair<String?, String?> {
        if (number.isBlank()) return null to null
        cache[number]?.let { return it }
        var r: Pair<String?, String?> = null to null
        try {
            c.contentResolver.query(Uri.parse(Sahl.CONTACTS_UNION + "/lookup/" + Uri.encode(number)), null, null, null, null)?.use {
                if (it.moveToFirst()) r = it.getString(0) to it.getString(2)
            }
        } catch (e: Exception) { }
        if (r.first == null) try {
            c.contentResolver.query(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)?.use {
                if (it.moveToFirst()) r = it.getString(0) to it.getString(1)
            }
        } catch (e: Exception) { }
        cache[number] = r
        return r
    }
    fun clear() = cache.clear()
}

@SuppressLint("MissingPermission")
object Sms {
    fun isDefault(c: Context) = Telephony.Sms.getDefaultSmsPackage(c) == c.packageName

    /** Latest message of every thread (SMS). */
    fun conversations(c: Context, scan: Int = 5000): List<Conversation> {
        val latest = LinkedHashMap<Long, Conversation>()
        val unread = HashMap<Long, Int>()
        c.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
            null, null, Telephony.Sms.DATE + " DESC")?.use {
            var n = 0
            while (it.moveToNext() && n++ < scan) {
                val t = it.getLong(0)
                val type = it.getInt(4)
                if (type == Telephony.Sms.MESSAGE_TYPE_INBOX && it.getInt(5) == 0) unread[t] = (unread[t] ?: 0) + 1
                if (t !in latest) {
                    val addr = it.getString(1) ?: ""
                    latest[t] = Conversation(t, addr, it.getString(2) ?: "", it.getLong(3), 0, type != Telephony.Sms.MESSAGE_TYPE_INBOX, null, null)
                }
            }
        }
        return latest.values.map { cv ->
            val (name, photo) = Names.of(c, cv.address)
            cv.copy(unread = unread[cv.threadId] ?: 0, name = name, photo = photo)
        }
    }

    fun messages(c: Context, threadId: Long, limit: Int = 500): List<Msg> {
        val out = ArrayList<Msg>()
        c.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SUBSCRIPTION_ID),
            "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString()), Telephony.Sms.DATE + " DESC")?.use {
            while (it.moveToNext() && out.size < limit)
                out.add(Msg(it.getLong(0), it.getString(1) ?: "", it.getLong(2), it.getInt(3), it.getInt(4) == 1, it.getInt(5)))
        }
        return out.reversed()
    }

    fun threadFor(c: Context, address: String): Long = try { Telephony.Threads.getOrCreateThreadId(c, address) } catch (e: Exception) { 0L }

    fun markRead(c: Context, threadId: Long) {
        if (!isDefault(c)) return
        try {
            c.contentResolver.update(Telephony.Sms.CONTENT_URI, ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) },
                "${Telephony.Sms.THREAD_ID}=? AND ${Telephony.Sms.READ}=0", arrayOf(threadId.toString()))
        } catch (e: Exception) { }
        c.getSystemService(NotificationManager::class.java)?.cancel(threadId.toInt())
    }

    fun deleteMessage(c: Context, id: Long) = try { c.contentResolver.delete(Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString()), null, null) } catch (e: Exception) { 0 }
    fun deleteThread(c: Context, threadId: Long) = try {
        c.contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString()))
    } catch (e: Exception) { 0 }

    fun sims(c: Context): List<Sim> = try {
        c.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
            ?.map { Sim(it.subscriptionId, (it.displayName ?: it.carrierName ?: "SIM ${it.simSlotIndex + 1}").toString()) } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun defaultSub() = SmsManager.getDefaultSmsSubscriptionId()

    @Suppress("DEPRECATION")
    private fun manager(c: Context, subId: Int): SmsManager {
        if (Build.VERSION.SDK_INT >= 31) {
            val m = c.getSystemService(SmsManager::class.java)
            return if (subId >= 0) m.createForSubscriptionId(subId) else m
        }
        return if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
    }

    /** Sends a text and records it in the Sent box (the default SMS app must do this itself). */
    fun send(c: Context, address: String, body: String, subId: Int = -1): Boolean {
        val text = body.trim()
        if (address.isBlank() || text.isEmpty()) return false
        val sub = if (subId >= 0) subId else defaultSub()
        var uri: Uri? = null
        if (isDefault(c)) try {
            uri = c.contentResolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, text)
                put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                if (sub >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, sub)
            })
        } catch (e: Exception) { }
        return try {
            val m = manager(c, sub)
            val parts = m.divideMessage(text)
            val sent = ArrayList<PendingIntent>()
            for (i in parts.indices) sent.add(PendingIntent.getBroadcast(c, (System.nanoTime() % 100000).toInt() + i,
                Intent(c, SentReceiver::class.java).setData(uri ?: Uri.EMPTY).putExtra("last", i == parts.size - 1),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            m.sendMultipartTextMessage(address, null, parts, sent, null)
            true
        } catch (e: Exception) {
            uri?.let { u -> c.contentResolver.update(u, ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) }, null, null) }
            false
        }
    }

    /** One-time codes: "Your code is 482913" → 482913 */
    fun otp(body: String): String? {
        val t = Norm.text(body)
        if (!Regex("(code|otp|pin|password|verification|رمز|كود|كلمة)").containsMatchIn(t)) return null
        return Regex("(?<!\\d)(\\d{4,8})(?!\\d)").find(t)?.groupValues?.get(1)
    }
}

/** Marks a sent message as sent or failed. */
class SentReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val uri = intent.data ?: return
        if (uri == Uri.EMPTY) return
        val ok = resultCode == android.app.Activity.RESULT_OK
        if (!ok || intent.getBooleanExtra("last", true)) try {
            c.contentResolver.update(uri, ContentValues().apply {
                put(Telephony.Sms.TYPE, if (ok) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED)
            }, null, null)
        } catch (e: Exception) { }
        if (!ok) Toast.makeText(c, Sahl.wrap(c).getString(R.string.send_failed), Toast.LENGTH_LONG).show()
    }
}

/** Incoming SMS (Android only delivers this to the default SMS app). */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val address = parts[0].displayOriginatingAddress ?: parts[0].originatingAddress ?: ""
        val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
        val sub = intent.getIntExtra("subscription", intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", -1))
        val pending = goAsync()
        Thread {
            try {
                c.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.DATE_SENT, parts[0].timestampMillis)
                    put(Telephony.Sms.READ, 0); put(Telephony.Sms.SEEN, 0)
                    if (sub >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, sub)
                })
                if (!Guard.blocked(c, address)) Notify.message(c, address, body)
            } catch (e: Exception) { } finally { pending.finish() }
        }.start()
    }
}

/** MMS arrives here; full picture/group messages come in a later version. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Notify.simple(c, Sahl.wrap(c).getString(R.string.mms_received), Sahl.wrap(c).getString(R.string.mms_later))
    }
}

/** "Respond via message" from the call screen (Sahl Phone quick replies use this). */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
            val to = intent?.data?.schemeSpecificPart?.split(';', ',')?.firstOrNull()
            if (!text.isNullOrBlank() && !to.isNullOrBlank()) Sms.send(this, to, text)
        } catch (e: Exception) { }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** Call Guard (Sahl Tools): don't notify for blocked senders. */
object Guard {
    fun blocked(c: Context, number: String): Boolean = try {
        c.contentResolver.query(Uri.parse("content://com.sahl.tools.union/guard/check/" + Uri.encode(number)), null, null, null, null)?.use {
            it.moveToFirst() && it.getInt(0) == 2 && (it.getString(1) == "cg_r_blocklist")
        } ?: false
    } catch (e: Exception) { false }
}

/** Message notifications with reply, mark-read and copy-code. */
object Notify {
    const val KEY_REPLY = "reply"
    private const val CH = "messages"

    private fun channel(c: Context) {
        val w = Sahl.wrap(c)
        c.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CH, w.getString(R.string.messages_channel), NotificationManager.IMPORTANCE_HIGH))
    }

    fun message(c: Context, address: String, body: String) {
        channel(c)
        val w = Sahl.wrap(c)
        val thread = Sms.threadFor(c, address)
        val id = thread.toInt()
        val name = Names.of(c, address).first ?: address
        val open = PendingIntent.getActivity(c, id, Intent(c, ThreadActivity::class.java).putExtra("thread", thread).putExtra("address", address)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sender = NPerson.Builder().setName(name).build()
        val me = NPerson.Builder().setName(w.getString(R.string.me)).build()
        // Keep the last few lines of this conversation in the notification
        val prev = Sahl.prefs(c).getString("n_$id", "") ?: ""
        val lines = (prev.split('\u0001').filter { it.isNotEmpty() } + body).takeLast(5)
        Sahl.prefs(c).edit().putString("n_$id", lines.joinToString("\u0001")).apply()
        val style = Notification.MessagingStyle(me)
        val now = System.currentTimeMillis()
        lines.forEachIndexed { i, l -> style.addMessage(l, now - (lines.size - i) * 1000L, sender) }

        val reply = Notification.Action.Builder(null, w.getString(R.string.reply),
            PendingIntent.getBroadcast(c, id, Intent(c, ActionReceiver::class.java).setAction("reply").putExtra("address", address).putExtra("thread", thread),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(w.getString(R.string.reply)).build()).build()
        val read = Notification.Action.Builder(null, w.getString(R.string.mark_read),
            PendingIntent.getBroadcast(c, id + 1, Intent(c, ActionReceiver::class.java).setAction("read").putExtra("thread", thread),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        val b = Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_chat).setStyle(style)
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(reply).addAction(read)
        Sms.otp(body)?.let { code ->
            b.addAction(Notification.Action.Builder(null, w.getString(R.string.copy_code, code),
                PendingIntent.getBroadcast(c, id + 2, Intent(c, ActionReceiver::class.java).setAction("copy").putExtra("code", code).putExtra("thread", thread),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        c.getSystemService(NotificationManager::class.java)?.notify(id, b.build())
    }

    fun clear(c: Context, thread: Long) {
        Sahl.prefs(c).edit().remove("n_${thread.toInt()}").apply()
        c.getSystemService(NotificationManager::class.java)?.cancel(thread.toInt())
    }

    fun simple(c: Context, title: String, text: String) {
        channel(c)
        c.getSystemService(NotificationManager::class.java)?.notify(4242, Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).build())
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val thread = intent.getLongExtra("thread", 0L)
        when (intent.action) {
            "reply" -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notify.KEY_REPLY)?.toString() ?: return
                val address = intent.getStringExtra("address") ?: return
                Sms.send(c, address, text)
                Sms.markRead(c, thread); Notify.clear(c, thread)
            }
            "read" -> { Sms.markRead(c, thread); Notify.clear(c, thread) }
            "copy" -> {
                c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("code", intent.getStringExtra("code")))
                Toast.makeText(c, Sahl.wrap(c).getString(R.string.copied), Toast.LENGTH_SHORT).show()
                Sms.markRead(c, thread); Notify.clear(c, thread)
            }
        }
    }
}
