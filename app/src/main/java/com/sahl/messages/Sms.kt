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
                        val unread: Int, val outgoing: Boolean, val name: String?, val photo: String?,
                        val category: Int = Category.PERSONAL, val pinned: Boolean = false, val muted: Boolean = false,
                        val members: List<String> = emptyList(), val photoMsg: Boolean = false) {
    val group get() = members.size > 1
}

data class Msg(val id: Long, val body: String, val date: Long, val type: Int, val read: Boolean, val subId: Int, val status: Int = -1) {
    val delivered get() = status == Telephony.Sms.STATUS_COMPLETE
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
    /** Uses the role check first — on some phones (Honor) the old check returns the wrong app. */
    fun isDefault(c: Context): Boolean {
        try { if (c.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) == true) return true } catch (e: Exception) { }
        return try { Telephony.Sms.getDefaultSmsPackage(c) == c.packageName } catch (e: Exception) { false }
    }

    /** Your own number(s), so group chats don't list you. Set in Settings. */
    fun myNumbers(c: Context): Set<String> =
        (Sahl.prefs(c).getString("my_numbers", "") ?: "").split(',', ' ', '\n').map { Norm.phoneKey(it) }.filter { it.length >= 7 }.toSet()

    /**
     * Fast path: Android keeps one row per conversation (thread) with its snippet and date,
     * so we read those directly instead of scanning every message.
     */
    fun conversations(c: Context): List<Conversation> = try { fastConversations(c) } catch (e: Exception) { slowConversations(c) }

    private fun fastConversations(c: Context): List<Conversation> {
        val cr = c.contentResolver
        // recipient id -> phone number
        val canon = HashMap<Long, String>()
        cr.query(Uri.parse("content://mms-sms/canonical-addresses"), null, null, null, null)?.use {
            val i = it.getColumnIndex("_id"); val a = it.getColumnIndex("address")
            while (it.moveToNext()) canon[it.getLong(i)] = it.getString(a) ?: ""
        }
        // unread counts (only unread rows are read, so this is quick)
        val unread = HashMap<Long, Int>()
        cr.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID), "${Telephony.Sms.READ}=0 AND ${Telephony.Sms.TYPE}=1", null, null)?.use {
            while (it.moveToNext()) { val t = it.getLong(0); unread[t] = (unread[t] ?: 0) + 1 }
        }
        try {
            cr.query(Telephony.Mms.CONTENT_URI, arrayOf("thread_id"), "read=0 AND msg_box=1", null, null)?.use {
                while (it.moveToNext()) { val t = it.getLong(0); unread[t] = (unread[t] ?: 0) + 1 }
            }
        } catch (e: Exception) { }
        val mine = myNumbers(c)
        val photo = Sahl.wrap(c).getString(R.string.photo)
        val out = ArrayList<Conversation>()
        cr.query(Uri.parse("content://mms-sms/conversations?simple=true"),
            arrayOf("_id", "date", "recipient_ids", "snippet", "has_attachment"), null, null, "date DESC")?.use {
            while (it.moveToNext()) {
                val t = it.getLong(0)
                val members = (it.getString(2) ?: "").split(' ').mapNotNull { id -> id.toLongOrNull()?.let { canon[it] } }
                    .filter { a -> a.isNotBlank() && Norm.phoneKey(a) !in mine }
                if (members.isEmpty()) continue
                val snippet = (it.getString(3) ?: "").ifBlank { if (it.getInt(4) == 1) photo else "" }
                val addr = if (members.size > 1) members.joinToString(",") else members[0]
                val (name, ph) = if (members.size > 1) groupName(c, members) to null else Names.of(c, members[0])
                out.add(Conversation(t, addr, snippet, it.getLong(1), unread[t] ?: 0, false, name, ph,
                    category = if (members.size > 1) Category.PERSONAL else Category.of(members[0], snippet, name != null),
                    pinned = Prefs.pinned(c, t), muted = Prefs.muted(c, t),
                    members = if (members.size > 1) members else emptyList(), photoMsg = snippet == photo))
            }
        }
        if (out.isEmpty()) throw IllegalStateException("empty")   // fall back to the full scan
        return out.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.date })
    }

    /** Slower fallback: scan recent messages (used only if the fast path isn't available). */
    private fun slowConversations(c: Context, scan: Int = 5000): List<Conversation> {
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
        // Pictures and group messages
        val mms = try { Mms.latest(c) } catch (e: Exception) { emptyMap() }
        val photo = c.getString(R.string.photo)
        for ((t, m) in mms) {
            val cur = latest[t]
            val mine = myNumbers(c)
            val members = m.addresses.filter { Norm.phoneKey(it) !in mine }
            val mmsAddr = m.from ?: members.firstOrNull() ?: ""
            if (cur == null || m.date > cur.date)
                latest[t] = Conversation(t, if (members.size > 1) members.joinToString(",") else (cur?.address ?: mmsAddr),
                    m.text.ifBlank { photo }, m.date, 0, m.outgoing, null, null, members = members, photoMsg = m.images.isNotEmpty())
            else latest[t] = cur.copy(members = if (members.size > 1) members else cur.members)
            unread[t] = (unread[t] ?: 0) + (Mms.unreadCache[t] ?: 0)
        }
        return latest.values.map { cv ->
            val (name, ph) = if (cv.group) groupName(c, cv.members) to null else Names.of(c, cv.address)
            cv.copy(unread = unread[cv.threadId] ?: 0, name = name, photo = ph,
                category = if (cv.group) Category.PERSONAL else Category.of(cv.address, cv.snippet, name != null),
                pinned = Prefs.pinned(c, cv.threadId), muted = Prefs.muted(c, cv.threadId))
        }.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.date })
    }

    fun groupName(c: Context, members: List<String>) =
        members.take(4).joinToString(", ") { (Names.of(c, it).first ?: it).substringBefore(' ') } + if (members.size > 4) " +${members.size - 4}" else ""

    fun messages(c: Context, threadId: Long, limit: Int = 500): List<Msg> {
        val out = ArrayList<Msg>()
        c.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SUBSCRIPTION_ID, Telephony.Sms.STATUS),
            "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString()), Telephony.Sms.DATE + " DESC")?.use {
            while (it.moveToNext() && out.size < limit)
                out.add(Msg(it.getLong(0), it.getString(1) ?: "", it.getLong(2), it.getInt(3), it.getInt(4) == 1, it.getInt(5), it.getInt(6)))
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
        Mms.markRead(c, threadId)
        c.getSystemService(NotificationManager::class.java)?.cancel(threadId.toInt())
    }

    fun deleteMessage(c: Context, id: Long) = try { c.contentResolver.delete(Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString()), null, null) } catch (e: Exception) { 0 }
    fun deleteThread(c: Context, threadId: Long) = try {
        c.contentResolver.delete(Uri.parse("content://mms-sms/conversations/$threadId"), null, null)
    } catch (e: Exception) {
        try { c.contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms.THREAD_ID}=?", arrayOf(threadId.toString())) } catch (x: Exception) { 0 }
    }

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
            val delivered = if (Prefs.deliveryReports(c) && uri != null) ArrayList(parts.indices.map { i ->
                PendingIntent.getBroadcast(c, (System.nanoTime() % 100000).toInt() + 500 + i, Intent(c, DeliveredReceiver::class.java).setData(uri),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }) else null
            if (uri != null && delivered != null) try {
                c.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING) }, null, null)
            } catch (e: Exception) { }
            m.sendMultipartTextMessage(address, null, parts, sent, delivered)
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
                if (!Guard.blocked(c, address) && !Prefs.muted(c, Sms.threadFor(c, address))) Notify.message(c, address, body)
            } catch (e: Exception) { } finally { pending.finish() }
        }.start()
    }
}

/** MMS arrives here as a short notice; Sahl Messages then downloads the full picture/group message. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val pdu = intent.getByteArrayExtra("data") ?: return
        val sub = intent.getIntExtra("subscription", -1)
        val pending = goAsync()
        Thread { try { Mms.onPush(c, pdu, sub) } catch (e: Exception) { } finally { pending.finish() } }.start()
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
    private const val CH = "messages_chime"   // plays the Sahl chime

    private fun channel(c: Context) {
        val w = Sahl.wrap(c)
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(Sahl.chimeChannel(c, CH, w.getString(R.string.messages_channel)))
        try { nm.deleteNotificationChannel("messages") } catch (e: Exception) { }
    }

    fun message(c: Context, address: String, body: String, threadId: Long = -1L, groupName: String? = null) {
        val thread = if (threadId > 0) threadId else Sms.threadFor(c, address)
        val id = thread.toInt()
        // Keep the last few lines of this conversation in the notification
        val prev = Sahl.prefs(c).getString("n_$id", "") ?: ""
        val lines = (prev.split('\u0001').filter { it.isNotEmpty() } + body).takeLast(5)
        Sahl.prefs(c).edit().putString("n_$id", lines.joinToString("\u0001"))
            .putString("na_$id", address).putString("ng_$id", groupName ?: "").apply()
        post(c, thread, false)
    }

    /** "Show" was tapped on a hidden notification: show the text, quietly. */
    fun reveal(c: Context, thread: Long) = post(c, thread, true)

    private fun post(c: Context, thread: Long, reveal: Boolean) {
        channel(c)
        val w = Sahl.wrap(c)
        val id = thread.toInt()
        val p = Sahl.prefs(c)
        val address = p.getString("na_$id", "") ?: ""
        val groupName = (p.getString("ng_$id", "") ?: "").ifEmpty { null }
        val lines = (p.getString("n_$id", "") ?: "").split('\u0001').filter { it.isNotEmpty() }
        if (lines.isEmpty() || address.isEmpty()) return
        val body = lines.last()
        val hidden = !reveal && Prefs.hidden(c, thread)
        val name = Names.of(c, address).first ?: address
        val open = PendingIntent.getActivity(c, id, Intent(c, ThreadActivity::class.java).putExtra("thread", thread).putExtra("address", address)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sender = NPerson.Builder().setName(groupName ?: name).build()
        val me = NPerson.Builder().setName(w.getString(R.string.me)).build()
        val style = Notification.MessagingStyle(me)
        val now = System.currentTimeMillis()
        if (hidden) style.addMessage(w.getString(R.string.hidden_preview, lines.size), now, sender)
        else lines.forEachIndexed { i, l -> style.addMessage(l, now - (lines.size - i) * 1000L, sender) }

        fun action(label: String, code: Int, act: String, extra: Intent.() -> Unit = {}) = Notification.Action.Builder(null, label,
            PendingIntent.getBroadcast(c, id + code, Intent(c, ActionReceiver::class.java).setAction(act).putExtra("thread", thread).apply(extra),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        val reply = Notification.Action.Builder(null, w.getString(R.string.reply),
            PendingIntent.getBroadcast(c, id, Intent(c, ActionReceiver::class.java).setAction("reply").putExtra("address", address).putExtra("thread", thread),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(w.getString(R.string.reply)).build()).build()
        val warn = if (groupName == null && !hidden) Assist.warning(c, address, body, Names.of(c, address).first != null) else null
        val b = Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_chat).setStyle(style)
            .apply { if (warn != null) setSubText("⚠ " + warn) }
            .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(reveal)
            .setVisibility(if (hidden) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PRIVATE)
        // Hidden: "Show" comes first, then the usual quick buttons
        if (hidden) b.addAction(action(w.getString(R.string.show), 3, "show"))
        b.addAction(reply)
        val code = Sms.otp(body)
        if (code != null) b.addAction(action(if (hidden) w.getString(R.string.copy_code_hidden) else w.getString(R.string.copy_code, code), 2, "copy") { putExtra("code", code) })
        else b.addAction(action(w.getString(R.string.mark_read), 1, "read"))
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
            "show" -> Notify.reveal(c, thread)
            "copy" -> {
                c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("code", intent.getStringExtra("code")))
                Toast.makeText(c, Sahl.wrap(c).getString(R.string.copied), Toast.LENGTH_SHORT).show()
                Sms.markRead(c, thread); Notify.clear(c, thread)
            }
        }
    }
}
