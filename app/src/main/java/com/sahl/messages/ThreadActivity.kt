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
    private var msgs: List<Any> = emptyList()          // Msg (SMS) or MmsMsg (picture/group)
    private var members: List<String> = emptyList()
    private val group get() = members.size > 1
    private var image: Uri? = null
    private lateinit var attachBox: LinearLayout
    private lateinit var undoBar: LinearLayout
    private lateinit var schedBox: LinearLayout
    private var pendingSend: Runnable? = null
    private lateinit var suggestBox: LinearLayout
    private lateinit var warnBox: TextView
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
        members = address.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (thread == 0L && address.isNotBlank()) thread = if (group) try { Telephony.Threads.getOrCreateThreadId(this, members.toSet()) } catch (e: Exception) { 0L } else Sms.existingThreadFor(this, address)
        val info = if (group) null else Names.info(this, address)
        val name = if (group) Sms.groupName(this, members) else info?.name
        val saved = group || (info?.saved == true)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(name ?: address, true, listOf(
            R.drawable.ic_call to { Sahl.callVia(this, address) },
            R.drawable.ic_more to { menu() }
        ), large = false))
        if (group) root.addView(text(getString(R.string.group_of, members.size), 12f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, px(4)) })
        else if (saved || address.any { it.isLetter() }) Unit
        else root.addView(text(listOfNotNull(if (name != null) address else null, getString(R.string.not_in_contacts),
            Countries.of(address, Sahl.countryCode(this), rtl)).joinToString("  ·  "),
            12f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, px(4)) })

        warnBox = text("", 13f, 0xFFFFFFFF.toInt()).apply {
            background = round(0xFFB3261E.toInt(), 14f); setPadding(px(14), px(10), px(14), px(10)); visibility = View.GONE
        }
        root.addView(warnBox, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(12), px(4), px(12), px(4)) })
        list = ListView(this).apply {
            divider = null; selector = android.graphics.drawable.ColorDrawable(0); isStackFromBottom = true
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL; clipToPadding = false; setPadding(0, px(8), 0, px(8))
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))

        // Scheduled messages for this chat
        schedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), 0, px(12), 0) }
        root.addView(schedBox)
        // "Sending… Undo" bar
        undoBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = round(col(R.color.tonal), 18f); visibility = View.GONE
            setPadding(px(16), px(8), px(8), px(8))
            addView(text(getString(R.string.sending_soon), 14f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(getString(R.string.undo), 14f, col(R.color.emerald), true).apply {
                setPadding(px(14), px(6), px(14), px(6)); setOnClickListener { undo() }
            }.press(14f))
        }
        root.addView(undoBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(12), px(4), px(12), 0) })
        // Quick reply suggestions
        suggestBox = LinearLayout(this).apply { setPaddingRelative(px(12), px(4), px(12), 0) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(suggestBox) })
        // Attached photo preview
        attachBox = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(px(16), px(6), px(16), 0) }
        root.addView(attachBox)

        // Compose bar
        val bar = LinearLayout(this).apply { gravity = Gravity.BOTTOM; setPadding(px(10), px(6), px(10), px(10)) }
        bar.addView(iconButton(R.drawable.ic_add, 0, col(R.color.emerald), 46, 24) { pickImage() },
            LinearLayout.LayoutParams(px(46), px(46)).apply { marginEnd = px(4); bottomMargin = px(4) })
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
        bar.addView(iconButton(R.drawable.ic_send, col(R.color.emerald), 0xFFFFFFFF.toInt(), 50, 22) { send() }.apply {
            setOnLongClickListener { sendLater(); true }
        }, LinearLayout.LayoutParams(px(50), px(50)).apply { marginStart = px(8); bottomMargin = px(2) })
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
            Sms.markRead(this, thread); Notify.clear(this, thread)
            val sms = try { Sms.messages(this, thread) } catch (e: Exception) { emptyList() }
            val mms = try { Mms.inThread(this, thread) } catch (e: Exception) { emptyList() }
            val all: List<Any> = (sms + mms).sortedBy { if (it is Msg) it.date else (it as MmsMsg).date }
            val lastIn = sms.lastOrNull { !it.outgoing }
            val known = group || Names.of(this, address).first != null
            val warn = if (lastIn != null && !group) Assist.warning(this, address, lastIn.body, known) else null
            val lastIsIncoming = all.lastOrNull().let { it is Msg && !it.outgoing }
            val sugg = if (lastIsIncoming) Assist.suggestions(this, lastIn?.body) else emptyList()
            runOnUiThread {
                msgs = all; list.adapter = Bubbles(); renderScheduled()
                warnBox.text = warn ?: ""; warnBox.visibility = if (warn != null) View.VISIBLE else View.GONE
                suggestBox.removeAllViews()
                if (input.text.isEmpty()) for (q in sugg) suggestBox.addView(text(q, 13f, col(R.color.emerald), true).apply {
                    background = round(col(R.color.tonal), 16f); setPadding(px(14), px(7), px(14), px(7))
                    setOnClickListener { input.setText(q); input.setSelection(q.length); suggestBox.removeAllViews() }
                }.press(16f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6) })
            }
        }.start()
    }

    private fun send() {
        val t = input.text.toString()
        val img = image
        if (t.isBlank() && img == null) return
        if (!Sms.isDefault(this)) { toast(R.string.make_default); return }
        input.setText(""); clearImage()
        val sub = subId
        val job = Runnable {
            undoBar.visibility = View.GONE; pendingSend = null
            Thread {
                if (group || img != null) Mms.send(this, members, t.ifBlank { null }, img, sub)
                else Sms.send(this, address, t, sub)
                if (thread == 0L && address.isNotBlank()) thread = if (group) try { Telephony.Threads.getOrCreateThreadId(this, members.toSet()) } catch (e: Exception) { 0L } else Sms.threadFor(this, address)
                runOnUiThread { load() }
            }.start()
        }
        if (Prefs.undoSend(this)) {
            pendingSend = job; undoText = t; undoImage = img
            undoBar.visibility = View.VISIBLE
            main.postDelayed(job, 3000)
        } else job.run()
    }

    private var undoText = ""
    private var undoImage: Uri? = null
    private fun undo() {
        pendingSend?.let { main.removeCallbacks(it) }
        pendingSend = null; undoBar.visibility = View.GONE
        input.setText(undoText); input.setSelection(undoText.length)
        undoImage?.let { setImage(it) }
    }

    /** Long-press send: pick a time to send later. */
    private fun sendLater() {
        val t = input.text.toString()
        if (t.isBlank() || group) { toast(R.string.later_text_only); return }
        val now = java.util.Calendar.getInstance()
        android.app.DatePickerDialog(this, { _, y, m, d ->
            android.app.TimePickerDialog(this, { _, h, min ->
                val cal = java.util.Calendar.getInstance().apply { set(y, m, d, h, min, 0) }
                if (cal.timeInMillis <= System.currentTimeMillis()) { toast(R.string.pick_future); return@TimePickerDialog }
                Scheduled.add(this, address, t, cal.timeInMillis, subId)
                input.setText(""); renderScheduled(); toast(R.string.scheduled_ok)
            }, now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), true).show()
        }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }

    private fun renderScheduled() {
        schedBox.removeAllViews()
        if (group) return
        for (it in Scheduled.forThread(this, address)) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; background = round(col(R.color.tonal), 16f); setPadding(px(14), px(8), px(6), px(8))
            }
            row.addView(icon(R.drawable.ic_history, col(R.color.emerald), 18))
            row.addView(text(getString(R.string.scheduled_for, stampFull(it.at)) + "\n" + it.body, 13f).apply {
                maxLines = 2; setPaddingRelative(px(10), 0, px(6), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            val id = it.id
            row.addView(iconButton(R.drawable.ic_close, 0, col(R.color.sub), 36, 18) { Scheduled.cancel(this, id); renderScheduled() })
            schedBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) })
        }
    }

    private fun stampFull(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", loc()))

    @Suppress("DEPRECATION")
    private fun pickImage() {
        try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 40) } catch (e: Exception) { }
    }

    @Deprecated("Activity result")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 40 && res == RESULT_OK) data?.data?.let { setImage(it) }
    }

    private fun setImage(u: Uri) {
        image = u
        attachBox.removeAllViews(); attachBox.visibility = View.VISIBLE
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = round(col(R.color.card), 12f) }
        attachBox.addView(iv, LinearLayout.LayoutParams(px(64), px(64)))
        attachBox.addView(text(getString(R.string.photo_attached), 13f, col(R.color.sub)).apply { setPaddingRelative(px(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        attachBox.addView(iconButton(R.drawable.ic_close, 0, col(R.color.sub), 36, 18) { clearImage() })
        Thread { val b = Images.load(this, u, 200); runOnUiThread { iv.setImageBitmap(b) } }.start()
    }

    private fun clearImage() { image = null; attachBox.removeAllViews(); attachBox.visibility = View.GONE }

    private fun menu() {
        val info = if (group) null else Names.info(this, address)
        sheet(if (group) Sms.groupName(this, members) else info?.name ?: address, listOf(
            Triple(R.drawable.ic_call, getString(R.string.call)) { Sahl.callVia(this, address) },
            Triple(R.drawable.ic_person_add, getString(R.string.add_contact)) {
                val i = Intent(Intent.ACTION_INSERT).setType(android.provider.ContactsContract.Contacts.CONTENT_TYPE)
                    .putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, address)
                if (info?.name != null && !info.saved) i.putExtra(android.provider.ContactsContract.Intents.Insert.NAME, info.name)
                try { packageManager.getPackageInfo("com.sahl.contacts", 0); i.setPackage("com.sahl.contacts") } catch (e: Exception) { }
                try { startActivity(i) } catch (e: Exception) { }
            },
            Triple(R.drawable.ic_copy, getString(R.string.copy_number)) { copy(address) },
            Triple(R.drawable.ic_star, getString(if (Prefs.pinned(this, thread)) R.string.unpin else R.string.pin)) { Prefs.togglePin(this, thread) },
            Triple(R.drawable.ic_bell, getString(if (Prefs.muted(this, thread)) R.string.unmute else R.string.mute)) { Prefs.toggleMute(this, thread) },
            Triple(R.drawable.ic_visibility_off, getString(if (Prefs.hiddenChat(this, thread)) R.string.show_previews else R.string.hide_previews)) {
                Prefs.toggleHiddenChat(this, thread); toast(if (Prefs.hiddenChat(this, thread)) R.string.previews_hidden else R.string.previews_shown)
            },
            Triple(R.drawable.ic_delete, getString(R.string.delete_chat)) {
                AlertDialog.Builder(this).setMessage(R.string.delete_chat_q)
                    .setPositiveButton(R.string.delete) { _, _ -> Thread { Sms.deleteThread(this, thread); runOnUiThread { finish() } }.start() }
                    .setNegativeButton(R.string.cancel, null).show()
            }
        ))
    }

    /** Shows the message with selectable text — pick any part and copy it. */
    private fun selectText(body: String) {
        val tv = TextView(this).apply {
            text = body; textSize = 17f; typeface = font; setTextColor(col(R.color.text)); setTextIsSelectable(true)
            setPadding(px(22), px(16), px(22), px(8)); setLineSpacing(0f, 1.15f)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        val d = AlertDialog.Builder(this).setTitle(R.string.select_text).setView(sv)
            .setPositiveButton(R.string.copy_selected) { _, _ ->
                val a = tv.selectionStart; val b = tv.selectionEnd
                copy(if (a in 0 until b) body.substring(a, b) else body)
            }
            .setNegativeButton(R.string.cancel, null).show()
        // Long-press a word to start selecting; drag the handles to adjust
        tv.post { tv.requestFocus() }
        d.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", s)); toast(R.string.copied)
    }

    private fun msgActions(m: Msg) {
        val key = "s:" + m.id
        val starred = Stars.isStarred(this, key)
        val items = mutableListOf<Triple<Int, String, () -> Unit>>(
            Triple(if (starred) R.drawable.ic_star_border else R.drawable.ic_star, getString(if (starred) R.string.unstar else R.string.star)) {
                Stars.toggle(this, key, thread, address, m.body, m.date); list.invalidateViews()
            },
            Triple(R.drawable.ic_copy, getString(R.string.copy)) { copy(m.body) },
            Triple(R.drawable.ic_edit, getString(R.string.select_text)) { selectText(m.body) },
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

    private fun dateOf(o: Any) = if (o is Msg) o.date else (o as MmsMsg).date

    /** Picture / group message bubble. */
    private fun mmsBubble(p: Int, m: MmsMsg): View {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(2), px(12), px(2)) }
        if (p == 0 || day(dateOf(msgs[p - 1])) != day(m.date)) outer.addView(text(dayLabel(day(m.date)), 12f, col(R.color.sub), true).apply {
            gravity = Gravity.CENTER; setPadding(0, px(12), 0, px(8)) }, LinearLayout.LayoutParams(-1, -2))
        val mine = m.outgoing
        if (group && !mine && m.from != null) outer.addView(text(Names.of(this, m.from).first ?: m.from, 12f, col(R.color.emerald), true).apply {
            setPaddingRelative(px(8), px(4), 0, px(2)) })
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = round(if (mine) col(R.color.emerald) else col(R.color.card), 18f)
            setPadding(px(6), px(6), px(6), px(6))
            setOnLongClickListener {
                val items = mutableListOf<Triple<Int, String, () -> Unit>>()
                if (m.text.isNotBlank()) {
                    items.add(Triple(R.drawable.ic_copy, getString(R.string.copy)) { copy(m.text) })
                    items.add(Triple(R.drawable.ic_edit, getString(R.string.select_text)) { selectText(m.text) })
                }
                items.add(Triple(R.drawable.ic_delete, getString(R.string.delete)) { Thread { Mms.delete(this@ThreadActivity, m.id); runOnUiThread { load() } }.start() })
                sheet(null, items)
                true
            }
        }
        for (img in m.images) {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = round(0x22000000, 14f); adjustViewBounds = true }
            bubble.addView(iv, LinearLayout.LayoutParams(px(230), px(230)).apply { bottomMargin = px(4) })
            val u = Uri.parse("content://mms/part/$img")
            iv.setOnClickListener { try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (e: Exception) { } }
            Thread { val b = Images.load(this, u, 700); runOnUiThread { iv.setImageBitmap(b) } }.start()
        }
        val fg = if (mine) 0xFFFFFFFF.toInt() else col(R.color.text)
        if (m.text.isNotBlank()) bubble.addView(text(m.text, 16f, fg).apply {
            autoLinkMask = Linkify.WEB_URLS or Linkify.PHONE_NUMBERS; setPadding(px(8), px(4), px(8), 0) })
        val status = when (m.box) { Telephony.Mms.MESSAGE_BOX_FAILED -> getString(R.string.failed_tap); Telephony.Mms.MESSAGE_BOX_OUTBOX -> getString(R.string.sending); else -> "" }
        bubble.addView(text(listOf(time(m.date), status).filter { it.isNotEmpty() }.joinToString(" · "), 11f,
            if (mine) 0xCCFFFFFF.toInt() else col(R.color.sub)).apply { gravity = Gravity.END; setPadding(px(8), px(3), px(8), px(2)) },
            LinearLayout.LayoutParams(-1, -2))
        val holder = LinearLayout(this).apply { gravity = if (mine) Gravity.END else Gravity.START }
        holder.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply { if (mine) marginStart = px(56) else marginEnd = px(56) })
        outer.addView(holder, LinearLayout.LayoutParams(-1, -2))
        return outer
    }

    private inner class Bubbles : BaseAdapter() {
        override fun getCount() = msgs.size
        override fun getItem(p: Int) = msgs[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val item = msgs[p]
            if (item is MmsMsg) return mmsBubble(p, item)
            val m = item as Msg
            val outer = LinearLayout(this@ThreadActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), px(2), px(12), px(2)) }
            if (p == 0 || day(dateOf(msgs[p - 1])) != day(m.date)) outer.addView(text(dayLabel(day(m.date)), 12f, col(R.color.sub), true).apply {
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
            val status = when { m.failed -> getString(R.string.failed_tap); m.pending -> getString(R.string.sending)
                mine && m.delivered -> "✓✓"; mine -> "✓"; else -> "" }
            val star = if (Stars.isStarred(this@ThreadActivity, "s:" + m.id)) "★ " else ""
            bubble.addView(text(star + listOf(time(m.date), status).filter { it.isNotEmpty() }.joinToString(" · "), 11f,
                if (mine || m.failed) 0xCCFFFFFF.toInt() else col(R.color.sub)).apply { gravity = Gravity.END; setPadding(0, px(3), 0, 0) },
                LinearLayout.LayoutParams(-1, -2))
            if (m.failed) bubble.setOnClickListener { msgActions(m) }
            val holder = LinearLayout(this@ThreadActivity).apply { gravity = if (mine) Gravity.END else Gravity.START }
            holder.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply {
                if (mine) marginStart = px(56) else marginEnd = px(56)
            })
            outer.addView(holder, LinearLayout.LayoutParams(-1, -2))
            // Smart actions right under the message: copy code, add to calendar, track parcel
            if (!mine) {
                val acts = LinearLayout(this@ThreadActivity)
                fun chip(icon: Int, label: String, go: () -> Unit) = acts.addView(LinearLayout(this@ThreadActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL; background = round(col(R.color.tonal), 14f); setPadding(px(10), px(6), px(12), px(6))
                    addView(icon(icon, col(R.color.emerald), 16))
                    addView(text(label, 13f, col(R.color.emerald), true).apply { setPaddingRelative(px(6), 0, 0, 0) })
                    setOnClickListener { go() }
                }.press(14f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6) })
                Sms.otp(m.body)?.let { code -> chip(R.drawable.ic_copy, getString(R.string.copy_code, code)) { copy(code) } }
                Assist.when_(m.body, m.date)?.let { at ->
                    chip(R.drawable.ic_bell, getString(R.string.add_calendar)) {
                        val title = (Names.of(this@ThreadActivity, address).first ?: address) + " — " + m.body.lineSequence().first().take(40)
                        try { startActivity(Assist.calendarIntent(title, m.body, at)) } catch (e: Exception) { }
                    }
                }
                Places.mapsUri(m.body)?.let { u ->
                    chip(R.drawable.ic_send, getString(R.string.open_maps)) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) { }
                    }
                }
                Assist.tracking(m.body)?.let { tr ->
                    chip(R.drawable.ic_send, getString(R.string.track_x, tr.carrier)) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tr.url))) } catch (e: Exception) { }
                    }
                }
                if (acts.childCount > 0) outer.addView(HorizontalScrollView(this@ThreadActivity).apply {
                    isHorizontalScrollBarEnabled = false; addView(acts) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(4) })
            }
            return outer
        }
    }
}
