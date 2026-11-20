package com.sahl.messages

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

/** Pinned and muted conversations. */
object Prefs {
    private fun set(c: Context, k: String) = Sahl.prefs(c).getStringSet(k, null)?.toSet() ?: emptySet()
    private fun toggle(c: Context, k: String, id: Long) {
        val s = set(c, k).toMutableSet(); val v = id.toString()
        if (v in s) s.remove(v) else s.add(v)
        Sahl.prefs(c).edit().putStringSet(k, s).apply()
    }
    fun pinned(c: Context, thread: Long) = thread.toString() in set(c, "pinned")
    fun muted(c: Context, thread: Long) = thread.toString() in set(c, "muted")
    fun togglePin(c: Context, thread: Long) = toggle(c, "pinned", thread)
    fun toggleMute(c: Context, thread: Long) = toggle(c, "muted", thread)
    fun deliveryReports(c: Context) = Sahl.prefs(c).getBoolean("delivery", true)
    fun undoSend(c: Context) = Sahl.prefs(c).getBoolean("undo_send", true)
    fun cleanCodes(c: Context) = Sahl.prefs(c).getBoolean("clean_codes", false)
}

/** Smart inbox: sorts conversations into Personal, Codes, Banks and Offers. */
object Category {
    const val PERSONAL = 0; const val CODES = 1; const val BANKS = 2; const val OFFERS = 3
    private val bank = Regex("(bank|qnb|cbq|qiib|qib|dukhan|doha ?bank|ahli|masraf|rayan|hsbc|debit|credit|card|account|a/c|transaction|purchase|withdraw|balance|qar|salary|transfer|بنك|مصرف|بطاقة|حساب|مبلغ|رصيد|خصم|إيداع|ايداع|تحويل|شراء|ريال)")
    private val promo = Regex("(offer|discount|% ?off|sale|deal|promo|win|free|bonus|subscribe|unsubscribe|stop|عرض|خصم|تخفيض|مجان|اربح|اشترك|باقة|هدية)")

    fun of(address: String, body: String, inContacts: Boolean): Int {
        val t = Norm.text(body)
        if (Sms.otp(body) != null) return CODES
        if (inContacts) return PERSONAL
        val alpha = address.any { it.isLetter() }
        if (bank.containsMatchIn(t) || bank.containsMatchIn(Norm.text(address))) return BANKS
        if (alpha || promo.containsMatchIn(t)) return OFFERS
        return PERSONAL
    }
}

/** Send later. */
object Scheduled {
    data class Item(val id: Long, val address: String, val body: String, val at: Long, val sub: Int)

    fun all(c: Context): List<Item> {
        val a = try { JSONArray(Sahl.prefs(c).getString("scheduled", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Item(it.getLong("id"), it.getString("a"), it.getString("b"), it.getLong("t"), it.optInt("s", -1)) }
    }
    private fun save(c: Context, l: List<Item>) = Sahl.prefs(c).edit().putString("scheduled",
        JSONArray(l.map { JSONObject().put("id", it.id).put("a", it.address).put("b", it.body).put("t", it.at).put("s", it.sub) }).toString()).apply()

    fun forThread(c: Context, address: String) = all(c).filter { Norm.phoneKey(it.address) == Norm.phoneKey(address) }

    fun add(c: Context, address: String, body: String, at: Long, sub: Int) {
        val item = Item(System.currentTimeMillis(), address, body, at, sub)
        save(c, all(c) + item)
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, item.id))
    }

    fun cancel(c: Context, id: Long) {
        save(c, all(c).filter { it.id != id })
        c.getSystemService(AlarmManager::class.java)?.cancel(pi(c, id))
    }

    private fun pi(c: Context, id: Long) = PendingIntent.getBroadcast(c, (id % 1_000_000).toInt(),
        Intent(c, ScheduledReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun sendDue(c: Context) {
        val now = System.currentTimeMillis()
        val (due, later) = all(c).partition { it.at <= now + 30_000 }
        if (due.isEmpty()) return
        save(c, later)
        for (i in due) Sms.send(c, i.address, i.body, i.sub)
    }
}

class ScheduledReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val pending = goAsync()
        Thread { try { Scheduled.sendDue(c) } finally { pending.finish() } }.start()
    }
}

/** "Delivered" ticks. */
class DeliveredReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val uri = intent.data ?: return
        try { c.contentResolver.update(uri, ContentValues().apply { put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE) }, null, null) } catch (e: Exception) { }
    }
}

/** Optional: remove one-time codes older than a day, to keep the inbox clean. */
object Cleanup {
    fun run(c: Context) {
        if (!Prefs.cleanCodes(c) || !Sms.isDefault(c)) return
        val p = Sahl.prefs(c)
        if (System.currentTimeMillis() - p.getLong("last_clean", 0) < 6 * 3600_000L) return
        p.edit().putLong("last_clean", System.currentTimeMillis()).apply()
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        val ids = ArrayList<Long>()
        try {
            c.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY),
                "${Telephony.Sms.DATE}<?", arrayOf(cutoff.toString()), null)?.use {
                while (it.moveToNext()) if (Sms.otp(it.getString(1) ?: "") != null) ids.add(it.getLong(0))
            }
            if (ids.isNotEmpty()) c.contentResolver.delete(Telephony.Sms.CONTENT_URI, "${Telephony.Sms._ID} IN (${ids.joinToString(",")})", null)
        } catch (e: Exception) { }
    }
}
