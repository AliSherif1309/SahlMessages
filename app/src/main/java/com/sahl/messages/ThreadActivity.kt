package com.sahl.messages

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One conversation: bubbles, date separators, and a compose bar with SIM choice. */
class ThreadActivity : SahlActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var thread = 0L
    private lateinit var address: String
    private lateinit var list: ListView
    private lateinit var input: EditText
    private lateinit var simChip: TextView
    private lateinit var counter: TextView
    private var msgs: List<Msg> = emptyList()
    private var sims: List<Sim> = emptyList()
    private var subId = -1

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { main.removeCallbacks(reload); main.postDelayed(reload, 250) }
    }
    private val reload = Runnable { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra("address") ?: ""
        thread = intent.getLongExtra("thread", 0L)
        if (thread == 0L && address.isNotBlank()) thread = Sms.threadFor(this, address)
        val name = Names.of(this, address).first
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(name ?: address, true, listOf(
            R.drawable.ic_call to { Sahl.callVia(this, address) },
            R.drawable.ic_more to { menu() }
        ), large = false))
        if (name != null || address.any { it.isLetter() }) Unit
        else root.addView(text(listOfNotNull(getString(R.string.not_in_contacts), Countries.of(address, Sahl.countryCode(this), rtl)).joinToString("  ·  "),
            12f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, px(4)) })

        list = ListView(this).apply {
            divider = null; selector = android.graphics.drawable.ColorDrawable(0); stackFromBottom = true
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL; clipToPadding = false; setPadding(0, px(8), 0, px(8))
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        // Compose bar
        val bar = LinearLayout(this).apply { gravity = Gravity.BOTTOM; setPadding(px(10), px(6), px(10), px(10)) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = round(col(R.color.tonal), 24f); setPaddingRelative(px(16), px(4), px(10), px(4))
        }
        input = EditText(this).apply {
            hint = getString(R.string.type_message); background = null; typeface = font; textSize = 16f; maxLines = 6
            setTextColor(col(R.color.text)); setHintTextColor(col(R.color.sub)); setPadding(0, px(10), 0, px(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { updateCounter() }
            })
        }
        box.addView(input)
        val meta = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        simChip = text("", 11f, col(R.color.emerald), true).apply {
            background = round(col(R.color.card), 10f); setPadding(px(8), px(2), px(8), px(2)); visibility = View.GONE
            setOnClickListener { nextSim() }
        }
        counter = text("", 11f, col(R.color.sub))
        meta.addView(simChip); meta.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)); meta.addView(counter)
        box.addView(meta)
        bar.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(iconButton(R.drawable.ic_send, col(R.color.emerald), 0xFFFFFFFF.toInt(), 50, 22) { send() },
            LinearLayout.LayoutParams(px(50), px(50)).apply { marginStart = px(8); bottomMargin = px(2) })
        root.addView(bar)
        setContentView(root)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        intent.getStringExtra("body")?.let { input.setText(it) }
        sims = Sms.sims(this)
        subId = Sms.defaultSub()
        if (sims.size > 1) { simChip.visibility = View.VISIBLE; if (sims.none { it.subId == subId }) subId = sims[0].subId; showSim() }
    }

    override fun onResume() {
        super.onResume()
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        load()
    }

    override fun onPause() { super.onPause(); contentResolver.unregisterContentObserver(observer) }

    private fun showSim() { simChip.text = sims.firstOrNull { it.subId == subId }?.label ?: "SIM" }
    private fun nextSim() {
        if (sims.size < 2) return
        val i = sims.indexOfFirst { it.subId == subId }
        subId = sims[(i + 1) % sims.size].subId; showSim()
    }

    private fun updateCounter() {
        val t = input.text.toString()
        if (t.length < 100) { counter.text = ""; return }
        val parts = try { android.telephony.SmsMessage.calculateLength(t, false) } catch (e: Exception) { null }
        counter.text = if (parts != null) "${parts[2]} / ${parts[0]}" else ""
    }

    private fun load() {
        if (thread == 0L) return
        Thread {
            val m = try { Sms.messages(this, thread) } catch (e: Exception) { emptyList() }
            Sms.markRead(this, thread); Notify.clear(this, thread)
            runOnUiThread { msgs = m; list.adapter = Bubbles() }
        }.start()
    }

    private fun send() {
        val t = input.text.toString()
        if (t.isBlank()) return
        if (!Sms.isDefault(this)) { toast(R.string.make_default); return }
        input.setText("")
        val sub = subId
        Thread {
            Sms.send(this, address, t, sub)
            if (thread == 0L) thread = Sms.threadFor(this, address)
            runOnUiThread { load() }
        }.start()
    }

    private fun menu() {
        sheet(Names.of(this, address).first ?: address, listOf(
            Triple(R.drawable.ic_call, getString(R.string.call)) { Sahl.callVia(this, address) },
            Triple(R.drawable.ic_person_add, getString(R.string.add_contact)) {
                val i = Intent(Intent.ACTION_INSERT).setType(android.provider.ContactsContract.Contacts.CONTENT_TYPE)
                    .putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, address)
                try { packageManager.getPackageInfo("com.sahl.contacts", 0); i.setPackage("com.sahl.contacts") } catch (e: Exception) { }
                try { startActivity(i) } catch (e: Exception) { }
            },
            Triple(R.drawable.ic_copy, getString(R.string.copy_number)) { copy(address) },
            Triple(R.drawable.ic_delete, getString(R.string.delete_chat)) {
                AlertDialog.Builder(this).setMessage(R.string.delete_chat_q)
                    .setPositiveButton(R.string.delete) { _, _ -> Thread { Sms.deleteThread(this, thread); runOnUiThread { finish() } }.start() }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        ))
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", s)); toast(R.string.copied)
    }

    private fun msgActions(m: Msg) {
        val items = mutableListOf(
            Triple(R.drawable.ic_copy, getString(R.string.copy)) { copy(m.body) },
            Triple(R.drawable.ic_share, getString(R.string.share)) {
                try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, m.body), getString(R.string.share))) } catch (e: Exception) { }
            })
        Sms.otp(m.body)?.let { code -> items.add(0, Triple(R.drawable.ic_lock, getString(R.string.copy_code, code)) { copy(code) }) }
        if (m.failed) items.add(0, Triple(R.drawable.ic_send, getString(R.string.retry)) {
            Thread { Sms.deleteMessage(this, m.id); Sms.send(this, address, m.body, subId); runOnUiThread { load() } }.start()
        })
        items.add(Triple(R.drawable.ic_delete, getString(R.string.delete)) { Thread { Sms.deleteMessage(this, m.id); runOnUiThread { load() } }.start() })
        sheet(null, items)
    }

    private fun loc() = resources.configuration.locales[0]
    private fun day(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate()
    private fun dayLabel(d: LocalDate): String {
        val today = LocalDate.now()
        return when (d) {
            today -> getString(R.string.today)
            today.minusDays(1) -> getString(R.string.yesterday)
            else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEEE, d MMMM" else "d MMMM yyyy", loc()))
        }
    }
    private fun time(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", loc()))

    private inner class Bubbles : BaseAdapter() {
        override fun getCount() = msgs.size
        override fun getItem(p: Int) = msgs[p]
        override fun getItemId(p: Int) = msgs[p].id
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val m = msgs[p]
            val outer = LinearLayout(this@ThreadActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(2), px(12), px(2)) }
            if (p == 0 || day(msgs[p - 1].date) != day(m.date)) outer.addView(text(dayLabel(day(m.date)), 12f, col(R.color.sub), true).apply {
                gravity = Gravity.CENTER; setPadding(0, px(12), 0, px(8))
            }, LinearLayout.LayoutParams(-1, -2))
            val mine = m.outgoing
            val r = 20f
            val bubble = LinearLayout(this@ThreadActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (m.failed) col(R.color.danger) else if (mine) col(R.color.emerald) else col(R.color.card))
                    val small = 6f * dp; val big = r * dp
                    // Tail corner points toward the sender
                    val ltr = !rtl
                    cornerRadii = if (mine == ltr) floatArrayOf(big, big, big, big, small, small, big, big)
                                  else floatArrayOf(big, big, big, big, big, big, small, small)
                }
                setPadding(px(14), px(9), px(14), px(7))
                setOnLongClickListener { msgActions(m); true }
            }
            val fg = if (mine || m.failed) 0xFFFFFFFF.toInt() else col(R.color.text)
            bubble.addView(text(m.body, 16f, fg).apply {
                autoLinkMask = Linkify.WEB_URLS or Linkify.PHONE_NUMBERS or Linkify.EMAIL_ADDRESSES
                setLinkTextColor(if (mine) 0xFFFFFFFF.toInt() else col(R.color.emerald))
                setTextIsSelectable(false)
            })
            val status = when { m.failed -> getString(R.string.failed_tap); m.pending -> getString(R.string.sending); else -> "" }
            bubble.addView(text(listOf(time(m.date), status).filter { it.isNotEmpty() }.joinToString(" · "), 11f,
                if (mine || m.failed) 0xCCFFFFFF.toInt() else col(R.color.sub)).apply { gravity = Gravity.END; setPadding(0, px(3), 0, 0) },
                LinearLayout.LayoutParams(-1, -2))
            if (m.failed) bubble.setOnClickListener { msgActions(m) }
            val holder = LinearLayout(this@ThreadActivity).apply { gravity = if (mine) Gravity.END else Gravity.START }
            holder.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply {
                if (mine) marginStart = px(56) else marginEnd = px(56)
            })
            outer.addView(holder, LinearLayout.LayoutParams(-1, -2))
            // Code detected: one-tap copy right under the message
            if (!mine) Sms.otp(m.body)?.let { code ->
                outer.addView(text(getString(R.string.copy_code, code), 13f, col(R.color.emerald), true).apply {
                    background = round(col(R.color.tonal), 14f); setPadding(px(12), px(6), px(12), px(6))
                    setOnClickListener { copy(code) }
                }.press(14f), LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(4) })
            }
            return outer
        }
    }
}
