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
                        val members: List<String> = emptyList(), val photoMsg: Boolean = false, val saved: Boolean = true) {
    val group get() = members.size > 1
}

data class Msg(val id: Long, val body: String, val date: Long, val type: Int, val read: Boolean, val subId: Int, val status: Int = -1) {
    val delivered get() = status == Telephony.Sms.STATUS_COMPLETE
    val outgoing get() = type != Telephony.Sms.MESSAGE_TYPE_INBOX
    val failed get() = type == Telephony.Sms.MESSAGE_TYPE_FAILED
    val pending get() = type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || type == Telephony.Sms.MESSAGE_TYPE_QUEUED
}

data class Sim(val subId: Int, val label: String)

/** Names come from Sahl Contacts (Sahl Union), Android contacts, or Sahl Phone's fetched caller names. */
object Names {
    data class Info(val name: String?, val photo: String?, val saved: Boolean)
    private val cache = ConcurrentHashMap<String, Info>()
    fun cachedInfo(number: String): Info = if (number.isBlank()) Info(null, null, false) else cache[number] ?: Info(null, null, false)
    fun cached(number: String): Pair<String?, String?> = cachedInfo(number).let { it.name to it.photo }
    fun seed(list: List<Conversation>) {
        for (c in list) if (!c.group && c.address.isNotBlank() && c.name != null && !cache.containsKey(c.address)) {
            cache[c.address] = Info(c.name, c.photo, c.saved)
        }
    }
    fun info(c: Context, number: String): Info {
        if (number.isBlank()) return Info(null, null, false)
        cache[number]?.let { return it }
        var r = Info(null, null, false)
        try {
            c.contentResolver.query(Uri.parse(Sahl.CONTACTS_UNION + "/lookup/" + Uri.encode(number)), null, null, null, null)?.use {
                if (it.moveToFirst()) r = Info(it.getString(0), it.getString(2), true)
            }
        } catch (e: Exception) { }
        if (r.name == null) try {
            c.contentResolver.query(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)?.use {
                if (it.moveToFirst()) r = Info(it.getString(0), it.getString(1), true)
            }
        } catch (e: Exception) { }
        if (r.name == null) try {
            c.contentResolver.query(Uri.parse(Sahl.PHONE_UNION + "/fetched/" + Uri.encode(number)), null, null, null, null)?.use {
                if (it.moveToFirst()) r = Info(it.getString(0)?.takeIf { s -> s.isNotBlank() }, null, it.getInt(1) == 1)
            }
        } catch (e: Exception) { }
        cache[number] = r
        return r
    }

    fun of(c: Context, number: String): Pair<String?, String?> = info(c, number).let { it.name to it.photo }
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

    fun saveCache(c: Context, l: List<Conversation>) {
        val a = org.json.JSONArray()
        for (cv in l.take(60)) a.put(org.json.JSONObject().put("t", cv.threadId).put("a", cv.address).put("s", cv.snippet).put("d", cv.date)
            .put("u", cv.unread).put("n", cv.name ?: "").put("p", cv.photo ?: "").put("c", cv.category).put("pin", cv.pinned).put("m", cv.muted)
            .put("g", org.json.JSONArray(cv.members)))
        Sahl.prefs(c).edit().putString("list_cache", a.toString()).apply()
    }

    fun readCache(c: Context): List<Conversation> = try {
        val a = org.json.JSONArray(Sahl.prefs(c).getString("list_cache", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("s", "").isNotBlank() }.map { o ->
            val g = o.optJSONArray("g"); val members = if (g == null) emptyList() else (0 until g.length()).map { g.getString(it) }
            Conversation(o.getLong("t"), o.getString("a"), o.getString("s"), o.getLong("d"), o.getInt("u"), false,
                o.getString("n").ifEmpty { null }, o.getString("p").ifEmpty { null }, o.getInt("c"), o.getBoolean("pin"), o.getBoolean("m"), members)
        }
    } catch (e: Exception) { emptyList() }

    /**
     * Fast path: Android keeps one row per conversation (thread) with its snippet and date,
     * so we read those directly instead of scanning every message.
     */
    fun conversations(c: Context, lookupContacts: Boolean = true): List<Conversation> =
        try { fastConversations(c, lookupContacts) } catch (e: Exception) { slowConversations(c, lookupContacts = lookupContacts) }

    private fun fastConversations(c: Context, lookupContacts: Boolean = true): List<Conversation> {
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
        val emptyThreads = ArrayList<Long>()
        cr.query(Uri.parse("content://mms-sms/conversations?simple=true"),
            arrayOf("_id", "date", "recipient_ids", "snippet", "has_attachment", "message_count"), null, null, "date DESC")?.use {
            while (it.moveToNext()) {
                val t = it.getLong(0)
                val count = it.getInt(5)
                val hasAttach = it.getInt(4) == 1
                val rawSnippet = it.getString(3) ?: ""
                if (count <= 0 && rawSnippet.isBlank() && !hasAttach) { emptyThreads.add(t); continue }
                val members = (it.getString(2) ?: "").split(' ').mapNotNull { id -> id.toLongOrNull()?.let { canon[it] } }
                    .filter { a -> a.isNotBlank() && Norm.phoneKey(a) !in mine }
                if (members.isEmpty()) continue
                val snippet = rawSnippet.ifBlank { if (hasAttach) photo else "" }
                if (snippet.isBlank()) continue
                val addr = if (members.size > 1) members.joinToString(",") else members[0]
                val info = if (members.size > 1) null else if (lookupContacts) Names.info(c, members[0]) else Names.cachedInfo(members[0])
                val name = if (members.size > 1) groupName(c, members, lookupContacts) else info?.name
                val ph = info?.photo
                val saved = members.size > 1 || (info?.saved == true)
                out.add(Conversation(t, addr, snippet, it.getLong(1), unread[t] ?: 0, false, name, ph,
                    category = if (members.size > 1) Category.PERSONAL else Category.of(members[0], snippet, saved),
                    pinned = Prefs.pinned(c, t), muted = Prefs.muted(c, t),
                    members = if (members.size > 1) members else emptyList(), photoMsg = snippet == photo, saved = saved))
            }
        }
        // Clean up empty threads left behind by earlier getOrCreateThreadId lookups
        if (emptyThreads.isNotEmpty() && isDefault(c)) {
            for (t in emptyThreads) try { cr.delete(Uri.parse("content://mms-sms/conversations/$t"), null, null) } catch (e: Exception) { }
        }
        if (out.isEmpty()) throw IllegalStateException("empty")   // fall back to the full scan
        return out.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.date })
    }

    /** Slower fallback: scan recent messages (used only if the fast path isn't available). */
    private fun slowConversations(c: Context, scan: Int = 5000, lookupContacts: Boolean = true): List<Conversation> {
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
            val (name, ph) = if (cv.group) groupName(c, cv.members, lookupContacts) to null
                             else if (lookupContacts) Names.of(c, cv.address) else Names.cached(cv.address)
            cv.copy(unread = unread[cv.threadId] ?: 0, name = name, photo = ph,
                category = if (cv.group) Category.PERSONAL else Category.of(cv.address, cv.snippet, name != null),
                pinned = Prefs.pinned(c, cv.threadId), muted = Prefs.muted(c, cv.threadId))
        }.sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.date })
    }

    fun groupName(c: Context, members: List<String>, lookupContacts: Boolean = true) =
        members.take(4).joinToString(", ") { ((if (lookupContacts) Names.of(c, it) else Names.cached(it)).first ?: it).substringBefore(' ') } + if (members.size > 4) " +${members.size - 4}" else ""

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

    /** Finds an existing thread ID for a phone number without creating an empty thread row in Android's database. */
    fun existingThreadFor(c: Context, address: String): Long {
        val key = Norm.phoneKey(address)
        if (key.isEmpty()) return 0L
        try {
            val ids = ArrayList<Long>()
            c.contentResolver.query(Uri.parse("content://mms-sms/canonical-addresses"), null, null, null, null)?.use {
                val i = it.getColumnIndex("_id"); val a = it.getColumnIndex("address")
                while (it.moveToNext()) {
                    val addr = it.getString(a) ?: ""
                    if (addr == address || (key.length >= 7 && Norm.phoneKey(addr) == key)) ids.add(it.getLong(i))
                }
            }
            if (ids.isEmpty()) return 0L
            c.contentResolver.query(Uri.parse("content://mms-sms/conversations?simple=true"),
                arrayOf("_id", "recipient_ids", "message_count"), null, null, "date DESC")?.use {
                while (it.moveToNext()) {
                    if (it.getInt(2) <= 0) continue
                    val rec = (it.getString(1) ?: "").trim()
                    val rId = rec.toLongOrNull()
                    if (rId != null && rId in ids) return it.getLong(0)
                }
            }
        } catch (e: Exception) { }
        return 0L
    }

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
    private const val CH = "messages_tone"    // plays the unique Sahl Messages chime
    private const val CH_SILENT = "messages_silent"

    private fun channel(c: Context) {
        val w = Sahl.wrap(c)
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(Sahl.chimeChannel(c, CH, w.getString(R.string.messages_channel), sound = Sahl.messageChime(c)))
        nm.createNotificationChannel(android.app.NotificationChannel(CH_SILENT, w.getString(R.string.messages_channel), NotificationManager.IMPORTANCE_LOW))
        try { nm.deleteNotificationChannel("messages"); nm.deleteNotificationChannel("messages_chime") } catch (e: Exception) { }
    }

    fun message(c: Context, address: String, body: String, threadId: Long = -1L, groupName: String? = null) {
        val thread = if (threadId > 0) threadId else Sms.threadFor(c, address)
        val id = thread.toInt()
        // Keep the last few lines of this conversation in the notification
        val prev = Sahl.prefs(c).getString("n_$id", "") ?: ""
        val lines = (prev.split('\u0001').filter { it.isNotEmpty() } + body).takeLast(5)
        Sahl.prefs(c).edit().putString("n_$id", lines.joinToString("\u0001"))
            .putString("na_$id", address).putString("ng_$id", groupName ?: "").apply()
        // Update the cached conversation list so opening Sahl Messages shows the new message instantly
        try {
            val cached = Sms.readCache(c).toMutableList()
            if (cached.isNotEmpty()) {
                val idx = cached.indexOfFirst { it.threadId == thread }
                val now = System.currentTimeMillis()
                val updated = if (idx >= 0) {
                    val cur = cached.removeAt(idx)
                    cur.copy(snippet = body, date = now, unread = cur.unread + 1, outgoing = false,
                        category = if (cur.group) Category.PERSONAL else Category.of(address, body, cur.name != null))
                } else {
                    val (name, ph) = Names.of(c, address)
                    Conversation(thread, address, body, now, 1, false, groupName ?: name, ph,
                        category = if (groupName != null) Category.PERSONAL else Category.of(address, body, name != null),
                        pinned = Prefs.pinned(c, thread), muted = Prefs.muted(c, thread))
                }
                val insertAt = if (updated.pinned) 0 else cached.indexOfFirst { !it.pinned }.let { if (it < 0) cached.size else it }
                cached.add(insertAt, updated)
                Sms.saveCache(c, cached)
            }
        } catch (e: Exception) { }
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
        val ch = if (PrayerSilence.inPrayer(c)) CH_SILENT else CH
        val b = Notification.Builder(c, ch).setSmallIcon(R.drawable.ic_chat).setStyle(style)
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

    object PrayerSilence {
        fun inPrayer(c: Context, now: Long = System.currentTimeMillis()): Boolean {
            if (!Sahl.prefs(c).getBoolean("prayer_silence", false)) return false
            val win = 20 * 60_000L
            try {
                c.contentResolver.query(Uri.parse(Sahl.ADHAN_UNION + "/prayers"), null, null, null, null)?.use {
                    val col = it.getColumnIndex("time")
                    while (it.moveToNext()) {
                        if (col >= 0) {
                            val t = it.getLong(col)
                            if (now >= t && now < t + win) return true
                        }
                    }
                }
            } catch (_: Exception) { }
            return false
        }
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
