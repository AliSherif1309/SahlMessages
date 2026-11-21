package com.sahl.messages

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Smart helpers that read a message and offer the right one-tap action. */
object Assist {

    // ---------- Smart reminders: "tomorrow at 10:30" → Add to calendar ----------
    private val months = mapOf("jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8,
        "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12, "يناير" to 1, "فبراير" to 2, "مارس" to 3, "ابريل" to 4, "مايو" to 5,
        "يونيو" to 6, "يوليو" to 7, "اغسطس" to 8, "سبتمبر" to 9, "اكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12)

    /** Finds a date and/or time in the message. Returns null if nothing is found. */
    fun when_(body: String, sent: Long): LocalDateTime? {
        val t = Norm.text(body)
        val base = java.time.Instant.ofEpochMilli(sent).atZone(ZoneId.systemDefault()).toLocalDate()
        var date: LocalDate? = null
        when {
            Regex("\\b(tomorrow|tmrw)\\b|غدا|بكره|بكرة").containsMatchIn(t) -> date = base.plusDays(1)
            Regex("\\btoday\\b|tonight|اليوم|الليله").containsMatchIn(t) -> date = base
        }
        if (date == null) Regex("(?<!\\d)(\\d{1,2})[/\\-.](\\d{1,2})(?:[/\\-.](\\d{2,4}))?(?!\\d)").find(t)?.let { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) it + 2000 else it } ?: base.year
            if (d in 1..31 && mo in 1..12) date = try { LocalDate.of(y, mo, d) } catch (e: Exception) { null }
        }
        if (date == null) for ((name, mo) in months) {
            val m = Regex("(?<!\\d)(\\d{1,2})\\s*$name|$name\\w*\\s*(\\d{1,2})(?!\\d)").find(t) ?: continue
            val d = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() ?: continue
            date = try { LocalDate.of(base.year, mo, d).let { if (it.isBefore(base)) it.plusYears(1) else it } } catch (e: Exception) { null }
            if (date != null) break
        }
        var time: LocalTime? = null
        Regex("(?<!\\d)(\\d{1,2})[:.](\\d{2})\\s*(am|pm|ص|م|صباحا|مساء)?").find(t)?.let { m ->
            var h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
            val ap = m.groupValues[3]
            if ((ap == "pm" || ap.startsWith("م")) && h < 12) h += 12
            if ((ap == "am" || ap.startsWith("ص")) && h == 12) h = 0
            if (h in 0..23 && mi in 0..59) time = LocalTime.of(h, mi)
        }
        if (time == null) Regex("(?:at|الساعه)\\s*(\\d{1,2})\\s*(am|pm|ص|م)?").find(t)?.let { m ->
            var h = m.groupValues[1].toInt(); val ap = m.groupValues[2]
            if ((ap == "pm" || ap == "م") && h < 12) h += 12
            if (h in 0..23) time = LocalTime.of(h, 0)
        }
        if (date == null && time == null) return null
        // Ignore plain clock times in codes/banks (they're timestamps, not appointments)
        if (date == null && (Sms.otp(body) != null || Regex("(debit|credit|card|بطاقه|خصم|مبلغ)").containsMatchIn(t))) return null
        return LocalDateTime.of(date ?: base, time ?: LocalTime.of(9, 0))
    }

    fun calendarIntent(title: String, body: String, at: LocalDateTime): Intent {
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ms + 3600_000L)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.DESCRIPTION, body)
    }

    // ---------- Delivery tracking ----------
    data class Track(val carrier: String, val number: String, val url: String)

    fun tracking(body: String): Track? {
        val t = Norm.text(body)
        val carriers = listOf(
            "aramex" to "https://www.aramex.com/track/results?ShipmentNumber=%s",
            "dhl" to "https://www.dhl.com/qa-en/home/tracking/tracking-express.html?submit=1&tracking-id=%s",
            "fedex" to "https://www.fedex.com/fedextrack/?trknbr=%s",
            "ups" to "https://www.ups.com/track?tracknum=%s",
            "qatar post" to "https://www.google.com/search?q=Qatar+Post+track+%s",
            "qpost" to "https://www.google.com/search?q=Qatar+Post+track+%s",
            "بريد قطر" to "https://www.google.com/search?q=Qatar+Post+track+%s",
            "ارامكس" to "https://www.aramex.com/track/results?ShipmentNumber=%s")
        val c = carriers.firstOrNull { t.contains(it.first) } ?: return null
        val n = Regex("(?<![a-z0-9])([a-z]{2}\\d{9}[a-z]{2}|\\d{9,14}|[a-z0-9]{10,20})(?![a-z0-9])").findAll(t)
            .map { it.groupValues[1] }.firstOrNull { it.any { ch -> ch.isDigit() } && Sms.otp(it) == null } ?: return null
        return Track(c.first.replaceFirstChar { it.uppercase() }, n.uppercase(), c.second.format(n.uppercase()))
    }

    // ---------- Unknown sender check ----------
    private val scamWords = Regex("(verify|suspend|blocked|locked|urgent|prize|winner|won|claim|reward|update your|confirm your|click|refund|parcel.*fee|" +
        "حسابك|موقوف|ايقاف|تحديث بيانات|جائزه|ربحت|فزت|اضغط|رابط|استرداد|رسوم)")

    /** Warning text for a message from someone not in your contacts, or null. */
    fun warning(c: Context, address: String, body: String, known: Boolean): String? {
        if (known || address.isBlank()) return null
        val w = Sahl.wrap(c)
        try {
            c.contentResolver.query(Uri.parse("content://com.sahl.tools.union/guard/check/" + Uri.encode(address)), null, null, null, null)?.use {
                if (it.moveToFirst() && it.getInt(0) != 0) return w.getString(R.string.warn_guard, it.getString(2) ?: "")
            }
        } catch (e: Exception) { }
        val t = Norm.text(body)
        val link = Regex("https?://|www\\.|bit\\.ly|\\.link|\\.xyz|\\.top").containsMatchIn(t)
        if (link && scamWords.containsMatchIn(t)) return w.getString(R.string.warn_scam)
        if (address.startsWith("+") && Countries.of(address, Sahl.countryCode(c), false) != null && link) return w.getString(R.string.warn_foreign_link)
        return null
    }

    // ---------- Quick reply suggestions ----------
    fun suggestions(c: Context, lastIncoming: String?): List<String> {
        if (lastIncoming.isNullOrBlank()) return emptyList()
        if (Sms.otp(lastIncoming) != null) return emptyList()
        val t = Norm.text(lastIncoming)
        val ar = lastIncoming.any { it in '؀'..'ۿ' }
        fun pick(en: List<String>, arl: List<String>) = if (ar) arl else en
        return when {
            Regex("(السلام عليكم|salam|assalam)").containsMatchIn(t) -> pick(listOf("Wa alaikum assalam", "Hi! How are you?"), listOf("وعليكم السلام", "أهلًا، كيف حالك؟"))
            Regex("(thank|thanks|thx|شكرا|مشكور|يعطيك العافيه)").containsMatchIn(t) -> pick(listOf("You're welcome 🌷", "Anytime"), listOf("العفو 🌷", "تسلم"))
            Regex("(where are you|وينك|انت فين|اين انت)").containsMatchIn(t) -> pick(listOf("On my way 🚗", "Arriving in 10 min", "I'll call you"), listOf("في الطريق 🚗", "أوصل خلال ١٠ دقائق", "بتصل عليك"))
            Regex("(call me|كلمني|اتصل)").containsMatchIn(t) -> pick(listOf("Calling you now", "Will call in a bit"), listOf("أتصل الحين", "بتصل بعد شوي"))
            t.contains("?") || t.contains("؟") -> pick(listOf("Yes 👍", "No", "Let me check"), listOf("نعم 👍", "لا", "خلني أشوف"))
            else -> pick(listOf("OK 👍", "Thanks", "I'll call you"), listOf("تمام 👍", "شكرًا", "بتصل عليك"))
        }
    }
}

/** Backup and restore all text messages to a file (save it in a folder Sahl Sync syncs to OneDrive). */
object Backup {
    fun export(c: Context, out: java.io.OutputStream): Int {
        var n = 0
        val arr = JSONArray()
        c.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.READ),
            null, null, Telephony.Sms.DATE)?.use {
            while (it.moveToNext()) {
                arr.put(JSONObject().put("a", it.getString(0) ?: "").put("b", it.getString(1) ?: "").put("d", it.getLong(2))
                    .put("ds", it.getLong(3)).put("t", it.getInt(4)).put("r", it.getInt(5)))
                n++
            }
        }
        out.write(JSONObject().put("app", "Sahl Messages").put("version", 1).put("sms", arr).toString().toByteArray(Charsets.UTF_8))
        return n
    }

    /** Adds messages that aren't already on the phone. Returns how many were added. */
    fun import(c: Context, input: java.io.InputStream): Int {
        val j = JSONObject(input.readBytes().toString(Charsets.UTF_8))
        val arr = j.optJSONArray("sms") ?: return 0
        val existing = HashSet<String>()
        c.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE), null, null, null)?.use {
            while (it.moveToNext()) existing.add(Norm.phoneKey(it.getString(0) ?: "") + "@" + it.getLong(1))
        }
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val key = Norm.phoneKey(o.optString("a")) + "@" + o.optLong("d")
            if (key in existing) continue
            try {
                c.contentResolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, o.optString("a")); put(Telephony.Sms.BODY, o.optString("b"))
                    put(Telephony.Sms.DATE, o.optLong("d")); put(Telephony.Sms.DATE_SENT, o.optLong("ds"))
                    put(Telephony.Sms.TYPE, o.optInt("t", 1)); put(Telephony.Sms.READ, o.optInt("r", 1)); put(Telephony.Sms.SEEN, 1)
                })
                n++
            } catch (e: Exception) { }
        }
        return n
    }
}

/** Addresses and map links → "Open in Maps". Understands Qatar's Zone / Street / Building numbers. */
object Places {
    fun mapsUri(body: String): Uri? {
        Regex("https?://(maps\\.app\\.goo\\.gl|goo\\.gl/maps|(www\\.)?google\\.[a-z.]+/maps|maps\\.google\\.[a-z.]+)\\S*", RegexOption.IGNORE_CASE)
            .find(body)?.let { return Uri.parse(it.value) }
        Regex("(-?\\d{1,2}\\.\\d{3,}),\\s*(-?\\d{1,3}\\.\\d{3,})").find(body)?.let {
            return Uri.parse("geo:${it.groupValues[1]},${it.groupValues[2]}?q=${it.groupValues[1]},${it.groupValues[2]}")
        }
        val t = Norm.text(body)
        val zone = Regex("(zone|منطقه)\\s*(?:no\\.?|رقم)?\\s*(\\d{1,3})").find(t)?.groupValues?.get(2)
        val street = Regex("(street|st\\.?|شارع)\\s*(?:no\\.?|رقم)?\\s*(\\d{1,4})").find(t)?.groupValues?.get(2)
        val building = Regex("(building|bldg\\.?|مبني|مبنى|بنايه)\\s*(?:no\\.?|رقم)?\\s*(\\d{1,4})").find(t)?.groupValues?.get(2)
        if (zone != null && (street != null || building != null)) {
            val q = listOfNotNull(building?.let { "Building $it" }, street?.let { "Street $it" }, "Zone $zone", "Qatar").joinToString(", ")
            return Uri.parse("geo:0,0?q=" + Uri.encode(q))
        }
        return null
    }
}

/** Starred messages: kept with a copy of the text so the list opens instantly. */
object Stars {
    data class Star(val key: String, val thread: Long, val address: String, val body: String, val date: Long)
    private fun all(c: Context): JSONArray = try { JSONArray(Sahl.prefs(c).getString("stars", "[]")) } catch (e: Exception) { JSONArray() }

    fun list(c: Context): List<Star> { val a = all(c); return (0 until a.length()).map { a.getJSONObject(it) }
        .map { Star(it.getString("k"), it.getLong("t"), it.getString("a"), it.getString("b"), it.getLong("d")) }.sortedByDescending { it.date } }
    fun keys(c: Context) = list(c).map { it.key }.toSet()
    fun isStarred(c: Context, key: String) = key in keys(c)

    fun toggle(c: Context, key: String, thread: Long, address: String, body: String, date: Long): Boolean {
        val l = list(c).toMutableList()
        val on = l.none { it.key == key }
        if (on) l.add(Star(key, thread, address, body, date)) else l.removeAll { it.key == key }
        Sahl.prefs(c).edit().putString("stars", JSONArray(l.map { JSONObject().put("k", it.key).put("t", it.thread).put("a", it.address)
            .put("b", it.body).put("d", it.date) }).toString()).apply()
        return on
    }
}
