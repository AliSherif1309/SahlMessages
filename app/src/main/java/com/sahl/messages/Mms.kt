package com.sahl.messages

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Telephony
import android.telephony.SmsManager
import java.io.ByteArrayOutputStream
import java.io.File

/** One picture/group message. */
data class MmsMsg(val id: Long, val threadId: Long, val date: Long, val box: Int, val read: Boolean,
                  val text: String, val images: List<Long>, val from: String?, val addresses: List<String>, val subId: Int = -1) {
    val outgoing get() = box != Telephony.Mms.MESSAGE_BOX_INBOX
}

/** Minimal MMS (WAP/WSP PDU) reader and writer — enough for text + pictures, 1-to-1 and groups. */
object Pdu {
    class Reader(val b: ByteArray, var i: Int = 0) {
        fun more() = i < b.size
        fun u8() = b[i++].toInt() and 0xFF
        fun uintvar(): Int { var v = 0; while (true) { val x = u8(); v = (v shl 7) or (x and 0x7F); if (x and 0x80 == 0) return v } }
        fun cstr(): String { val s = i; while (i < b.size && b[i] != 0.toByte()) i++; val r = String(b, s, i - s, Charsets.UTF_8); if (i < b.size) i++; return r }
        /** Reads one header value in its raw form (generic WSP rule). */
        fun value(): ByteArray {
            val f = b[i].toInt() and 0xFF
            return when {
                f <= 30 -> { i++; val v = b.copyOfRange(i, i + f); i += f; v }
                f == 31 -> { i++; val n = uintvar(); val v = b.copyOfRange(i, i + n); i += n; v }
                f in 32..127 -> { val s = i; cstr(); b.copyOfRange(s, i) }
                else -> { i++; byteArrayOf(f.toByte()) }
            }
        }
    }

    /** Text from a value that may be "encoded-string" (optionally with a charset prefix). */
    fun text(v: ByteArray): String {
        if (v.isEmpty()) return ""
        var start = 0
        val first = v[0].toInt() and 0xFF
        if (first <= 31 && v.size > 2) start = 2                   // value-length + charset, then the text
        else if (first >= 0x80 && v.size > 1) start = 1            // charset as short-integer before the text
        if (first == 0x7F) start = 1                              // quote
        var end = v.size; while (end > start && v[end - 1] == 0.toByte()) end--
        return String(v, start, end - start, Charsets.UTF_8)
    }

    fun cleanAddress(a: String) = a.substringBefore("/TYPE").trim()

    data class Headers(val type: Int, val location: String?, val txId: String?, val from: String?, val to: List<String>,
                       val subject: String?, val bodyStart: Int, val contentType: ByteArray?)

    fun headers(b: ByteArray): Headers {
        val r = Reader(b)
        var type = 0; var loc: String? = null; var tx: String? = null; var from: String? = null; var subj: String? = null
        val to = ArrayList<String>(); var ct: ByteArray? = null
        while (r.more()) {
            val h = r.u8()
            if (h < 0x80) { r.i--; r.cstr(); r.value(); continue }   // application header: name + value
            val code = h and 0x7F
            if (code == 0x04) { ct = r.value(); break }                // Content-Type: body follows
            val v = r.value()
            when (code) {
                0x0C -> type = v[0].toInt() and 0xFF
                0x03 -> loc = text(v)
                0x18 -> tx = text(v)
                0x16 -> subj = text(v)
                0x17, 0x02 -> to.add(cleanAddress(text(v)))
                0x09 -> if (v.isNotEmpty() && (v[0].toInt() and 0xFF) == 0x80) from = cleanAddress(text(v.copyOfRange(1, v.size)))
            }
        }
        return Headers(type, loc, tx, from, to, subj, r.i, ct)
    }

    private val known = mapOf(0x03 to "text/plain", 0x1D to "image/gif", 0x1E to "image/jpeg", 0x20 to "image/png",
        0x21 to "image/vnd.wap.wbmp", 0x33 to "application/vnd.wap.multipart.related", 0x23 to "application/vnd.wap.multipart.mixed")

    fun mime(ct: ByteArray): String {
        if (ct.isEmpty()) return "application/octet-stream"
        val f = ct[0].toInt() and 0xFF
        if (f >= 0x80) return known[f and 0x7F] ?: "application/octet-stream"
        return String(ct, 0, ct.indexOf(0.toByte()).let { if (it < 0) ct.size else it }, Charsets.US_ASCII).lowercase()
    }

    /** Content-type value → mime (handles the value-length form with parameters). */
    fun partMime(r: Reader): String {
        val f = r.b[r.i].toInt() and 0xFF
        return if (f <= 31) mime(r.value()) else if (f >= 0x80) { r.i++; known[f and 0x7F] ?: "application/octet-stream" }
        else r.cstr().lowercase()
    }

    data class Part(val mime: String, val data: ByteArray)

    fun parts(b: ByteArray, start: Int): List<Part> {
        val out = ArrayList<Part>()
        val r = Reader(b, start)
        if (!r.more()) return out
        val n = r.uintvar()
        repeat(n) {
            val hl = r.uintvar(); val dl = r.uintvar()
            val hs = r.i
            val m = partMime(r)
            r.i = hs + hl
            if (r.i + dl > b.size) return out
            out.add(Part(m, b.copyOfRange(r.i, r.i + dl)))
            r.i += dl
        }
        return out
    }

    // ---------- Writer (send-req) ----------
    private fun uintvar(v: Int): ByteArray {
        val bytes = ArrayList<Byte>(); var x = v
        bytes.add((x and 0x7F).toByte()); x = x ushr 7
        while (x > 0) { bytes.add(0, ((x and 0x7F) or 0x80).toByte()); x = x ushr 7 }
        return bytes.toByteArray()
    }
    private fun cstr(s: String) = s.toByteArray(Charsets.UTF_8) + 0.toByte()

    fun sendReq(to: List<String>, text: String?, image: ByteArray?): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0x8C); o.write(0x80)                                   // message type: send-req
        o.write(0x98); o.write(cstr("T" + System.currentTimeMillis())) // transaction id
        o.write(0x8D); o.write(0x92)                                   // MMS version 1.2
        o.write(0x89); o.write(1); o.write(0x81)                       // From: insert-address-token
        for (a in to) { o.write(0x97); o.write(cstr(a.filter { it.isDigit() || it == '+' } + "/TYPE=PLMN")) }
        o.write(0x84); o.write(0xA3)                                   // Content-Type: multipart/mixed
        val parts = ArrayList<Pair<ByteArray, ByteArray>>()
        // text part header: value-length 3 · text/plain · charset=utf-8
        if (!text.isNullOrBlank()) parts.add(byteArrayOf(0x03, 0x83.toByte(), 0x81.toByte(), 0xEA.toByte()) to text.toByteArray(Charsets.UTF_8))
        if (image != null) parts.add(cstr("image/jpeg") to image)
        o.write(uintvar(parts.size))
        for ((h, d) in parts) { o.write(uintvar(h.size)); o.write(uintvar(d.size)); o.write(h); o.write(d) }
        return o.toByteArray()
    }
}

/** Gives the system's MMS service a file to read from / write into. */
class MmsFileProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = File(context!!.cacheDir, uri.lastPathSegment ?: return null)
        val m = if (mode.contains("w")) ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                else ParcelFileDescriptor.MODE_READ_ONLY
        return ParcelFileDescriptor.open(f, m)
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        fun uri(c: Context, name: String): Uri = Uri.parse("content://${c.packageName}.mmsfiles/$name")
    }
}

@SuppressLint("MissingPermission")
object Mms {
    private fun manager(c: Context, sub: Int): SmsManager {
        @Suppress("DEPRECATION")
        val m: SmsManager = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        return if (sub >= 0) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 31) m.createForSubscriptionId(sub) else SmsManager.getSmsManagerForSubscriptionId(sub)
        } else m
    }

    private fun grant(c: Context, uri: Uri) {
        for (p in listOf("com.android.phone", "com.android.mms.service")) try {
            c.grantUriPermission(p, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { }
    }

    /** A notification-ind arrived: download the real message. */
    fun onPush(c: Context, pdu: ByteArray, sub: Int) {
        val h = Pdu.headers(pdu)
        if (h.type != 0x82 || h.location.isNullOrBlank()) return
        val name = "dl_" + System.currentTimeMillis() + ".pdu"
        val uri = MmsFileProvider.uri(c, name)
        val done = PendingIntent.getBroadcast(c, name.hashCode(), Intent(c, MmsDownloadedReceiver::class.java)
            .putExtra("file", name).putExtra("sub", sub).putExtra("from", h.from ?: ""),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        grant(c, uri)
        try { manager(c, sub).downloadMultimediaMessage(c, h.location, uri, null, done) } catch (e: Exception) { }
    }

    /** Store a downloaded retrieve-conf PDU in Android's message database and notify. */
    fun store(c: Context, bytes: ByteArray, sub: Int) {
        val h = Pdu.headers(bytes)
        val from = h.from ?: ""
        val mine = Sms.myNumbers(c) + Norm.phoneKey(myNumber(c))
        val others = h.to.filter { Norm.phoneKey(it) !in mine }
        val members = (listOf(from) + others).filter { it.isNotBlank() }.distinctBy { Norm.phoneKey(it) }
        val thread = try { Telephony.Threads.getOrCreateThreadId(c, members.toSet()) } catch (e: Exception) { 0L }
        val cv = ContentValues().apply {
            put(Telephony.Mms.THREAD_ID, thread); put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX); put(Telephony.Mms.READ, 0); put(Telephony.Mms.SEEN, 0)
            put(Telephony.Mms.MESSAGE_TYPE, 132); put(Telephony.Mms.MMS_VERSION, 18)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            h.subject?.let { put(Telephony.Mms.SUBJECT, it) }
            if (sub >= 0) put(Telephony.Mms.SUBSCRIPTION_ID, sub)
        }
        val msgUri = c.contentResolver.insert(Telephony.Mms.Inbox.CONTENT_URI, cv) ?: return
        val id = msgUri.lastPathSegment ?: return
        fun addr(a: String, type: Int) = c.contentResolver.insert(Uri.parse("content://mms/$id/addr"), ContentValues().apply {
            put(Telephony.Mms.Addr.ADDRESS, a); put(Telephony.Mms.Addr.TYPE, type); put(Telephony.Mms.Addr.CHARSET, 106)
        })
        if (from.isNotBlank()) addr(from, 137)
        for (t in h.to) addr(t, 151)
        var text = ""
        for (p in Pdu.parts(bytes, h.bodyStart)) {
            if (p.mime == "application/smil") continue
            val pv = ContentValues().apply { put(Telephony.Mms.Part.CONTENT_TYPE, p.mime); put(Telephony.Mms.Part.MSG_ID, id.toLong()) }
            if (p.mime.startsWith("text/")) {
                val t = String(p.data, Charsets.UTF_8); text += t
                pv.put(Telephony.Mms.Part.CHARSET, 106); pv.put(Telephony.Mms.Part.TEXT, t)
                c.contentResolver.insert(Uri.parse("content://mms/$id/part"), pv)
            } else {
                val pu = c.contentResolver.insert(Uri.parse("content://mms/$id/part"), pv) ?: continue
                try { c.contentResolver.openOutputStream(pu)?.use { it.write(p.data) } } catch (e: Exception) { }
            }
        }
        val who = if (members.size > 1) Names.of(c, from).first ?: from else from
        Archive.unarchive(c, thread)
        if (!Spam.isSpam(c, thread, from, text, checkGuard = true) && !Prefs.muted(c, thread))
            Notify.message(c, from, if (text.isNotBlank()) text else Sahl.wrap(c).getString(R.string.photo), thread, if (members.size > 1) who else null, subId = sub)
    }

    @SuppressLint("HardwareIds")
    private fun myNumber(c: Context): String = try {
        c.getSystemService(android.telephony.TelephonyManager::class.java)?.line1Number ?: ""
    } catch (e: Exception) { "" }

    /** Send a picture and/or text to one or more people. */
    fun send(c: Context, to: List<String>, text: String?, imageUri: Uri?, sub: Int): Boolean {
        val img = imageUri?.let { compress(c, it) }
        val pdu = Pdu.sendReq(to, text, img)
        val name = "tx_" + System.currentTimeMillis() + ".pdu"
        File(c.cacheDir, name).writeBytes(pdu)
        val thread = try { Telephony.Threads.getOrCreateThreadId(c, to.toSet()) } catch (e: Exception) { 0L }
        // Record in the Sent box (the default SMS app must do this itself)
        var msgUri: Uri? = null
        try {
            msgUri = c.contentResolver.insert(Telephony.Mms.Sent.CONTENT_URI, ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, thread); put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX); put(Telephony.Mms.READ, 1); put(Telephony.Mms.SEEN, 1)
                put(Telephony.Mms.MESSAGE_TYPE, 128); put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.mixed")
                if (sub >= 0) put(Telephony.Mms.SUBSCRIPTION_ID, sub)
            })
            val id = msgUri?.lastPathSegment
            if (id != null) {
                for (a in to) c.contentResolver.insert(Uri.parse("content://mms/$id/addr"), ContentValues().apply {
                    put(Telephony.Mms.Addr.ADDRESS, a); put(Telephony.Mms.Addr.TYPE, 151); put(Telephony.Mms.Addr.CHARSET, 106)
                })
                if (!text.isNullOrBlank()) c.contentResolver.insert(Uri.parse("content://mms/$id/part"), ContentValues().apply {
                    put(Telephony.Mms.Part.CONTENT_TYPE, "text/plain"); put(Telephony.Mms.Part.CHARSET, 106); put(Telephony.Mms.Part.TEXT, text)
                })
                if (img != null) c.contentResolver.insert(Uri.parse("content://mms/$id/part"), ContentValues().apply {
                    put(Telephony.Mms.Part.CONTENT_TYPE, "image/jpeg")
                })?.let { pu -> c.contentResolver.openOutputStream(pu)?.use { it.write(img) } }
            }
        } catch (e: Exception) { }
        return try {
            val sent = PendingIntent.getBroadcast(c, name.hashCode(), Intent(c, MmsSentReceiver::class.java)
                .setData(msgUri ?: Uri.EMPTY).putExtra("file", name), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            grant(c, MmsFileProvider.uri(c, name))
            manager(c, sub).sendMultimediaMessage(c, MmsFileProvider.uri(c, name), null, null, sent)
            true
        } catch (e: Exception) { false }
    }

    /** Shrinks a picture so it fits carrier MMS limits (~300 KB). */
    fun compress(c: Context, uri: Uri): ByteArray? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 1600) s *= 2
        val bmp = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
        if (bmp == null) null else {
            var q = 85; var out: ByteArray
            var b: Bitmap = bmp
            while (true) {
                val bo = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, q, bo); out = bo.toByteArray()
                if (out.size <= 290_000) break
                if (q > 50) q -= 15 else { b = Bitmap.createScaledBitmap(b, b.width * 3 / 4, b.height * 3 / 4, true) }
            }
            out
        }
    } catch (e: Exception) { null }

    // ---------- Reading for the UI ----------
    fun text(c: Context, id: Long): Pair<String, List<Long>> {
        var t = ""; val imgs = ArrayList<Long>()
        try {
            c.contentResolver.query(Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text"), "mid=?", arrayOf(id.toString()), null)?.use {
                while (it.moveToNext()) {
                    val ct = it.getString(1) ?: ""
                    if (ct.startsWith("text/plain")) t += it.getString(2) ?: ""
                    else if (ct.startsWith("image/")) imgs.add(it.getLong(0))
                }
            }
        } catch (e: Exception) { }
        return t to imgs
    }

    fun addresses(c: Context, id: Long): Pair<String?, List<String>> {
        var from: String? = null; val all = ArrayList<String>()
        try {
            c.contentResolver.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null)?.use {
                while (it.moveToNext()) {
                    val a = it.getString(0) ?: continue
                    if (a == "insert-address-token") continue
                    if (it.getInt(1) == 137) from = a
                    all.add(a)
                }
            }
        } catch (e: Exception) { }
        return from to all.distinctBy { Norm.phoneKey(it) }
    }

    fun inThread(c: Context, thread: Long, limit: Int = 200): List<MmsMsg> {
        val out = ArrayList<MmsMsg>()
        try {
            c.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id", "date", "msg_box", "read", "sub_id"),
                "thread_id=?", arrayOf(thread.toString()), "date DESC")?.use {
                while (it.moveToNext() && out.size < limit) {
                    val id = it.getLong(0)
                    val (t, imgs) = text(c, id)
                    val (from, all0) = addresses(c, id)
                    val mine = Sms.myNumbers(c); val all = all0.filter { Norm.phoneKey(it) !in mine }
                    out.add(MmsMsg(id, thread, it.getLong(1) * 1000, it.getInt(2), it.getInt(3) == 1, t, imgs, from, all, it.getInt(4)))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    /** Latest MMS per thread (for the conversation list). */
    fun latest(c: Context, scan: Int = 600): Map<Long, MmsMsg> {
        val out = LinkedHashMap<Long, MmsMsg>()
        val unread = HashMap<Long, Int>()
        try {
            c.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id", "thread_id", "date", "msg_box", "read", "sub_id"), null, null, "date DESC")?.use {
                var n = 0
                while (it.moveToNext() && n++ < scan) {
                    val t = it.getLong(1)
                    if (it.getInt(3) == 1 && it.getInt(4) == 0) unread[t] = (unread[t] ?: 0) + 1
                    if (t in out) continue
                    val id = it.getLong(0)
                    val (txt, imgs) = text(c, id)
                    val (from, all) = addresses(c, id)
                    out[t] = MmsMsg(id, t, it.getLong(2) * 1000, it.getInt(3), it.getInt(4) == 1, txt, imgs, from, all, it.getInt(5))
                }
            }
        } catch (e: Exception) { }
        unreadCache = unread
        return out
    }
    var unreadCache: Map<Long, Int> = emptyMap()

    fun markRead(c: Context, thread: Long) = try {
        c.contentResolver.update(Telephony.Mms.CONTENT_URI, ContentValues().apply { put("read", 1); put("seen", 1) },
            "thread_id=? AND read=0", arrayOf(thread.toString()))
    } catch (e: Exception) { 0 }

    fun markAllRead(c: Context) = try {
        c.contentResolver.update(Telephony.Mms.CONTENT_URI, ContentValues().apply { put("read", 1); put("seen", 1) },
            "read=0", null)
    } catch (e: Exception) { 0 }

    fun markUnread(c: Context, thread: Long) {
        try {
            c.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id"),
                "thread_id=? AND msg_box=1", arrayOf(thread.toString()), "date DESC")?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(0)
                    c.contentResolver.update(Telephony.Mms.CONTENT_URI, ContentValues().apply { put("read", 0) },
                        "_id=?", arrayOf(id.toString()))
                }
            }
        } catch (e: Exception) { }
    }

    fun delete(c: Context, id: Long) = try { c.contentResolver.delete(Uri.parse("content://mms/$id"), null, null) } catch (e: Exception) { 0 }
}

class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val name = intent.getStringExtra("file") ?: return
        val sub = intent.getIntExtra("sub", -1)
        val pending = goAsync()
        Thread {
            try {
                val f = File(c.cacheDir, name)
                if (resultCode == android.app.Activity.RESULT_OK && f.exists() && f.length() > 0) Mms.store(c, f.readBytes(), sub)
                else Notify.simple(c, Sahl.wrap(c).getString(R.string.mms_failed), intent.getStringExtra("from") ?: "")
                f.delete()
            } catch (e: Exception) { } finally { pending.finish() }
        }.start()
    }
}

class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val ok = resultCode == android.app.Activity.RESULT_OK
        intent.data?.takeIf { it != Uri.EMPTY }?.let { u ->
            try {
                c.contentResolver.update(u, ContentValues().apply {
                    put(Telephony.Mms.MESSAGE_BOX, if (ok) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED)
                }, null, null)
            } catch (e: Exception) { }
        }
        intent.getStringExtra("file")?.let { File(c.cacheDir, it).delete() }
        if (!ok) android.widget.Toast.makeText(c, Sahl.wrap(c).getString(R.string.send_failed), android.widget.Toast.LENGTH_LONG).show()
    }
}

/** Loads a picture scaled down to about [max] pixels. */
object Images {
    fun load(c: Context, u: Uri, max: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > max * 2) s *= 2
        c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
    } catch (e: Exception) { null }
}
