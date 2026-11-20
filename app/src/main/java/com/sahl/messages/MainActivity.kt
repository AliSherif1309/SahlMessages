package com.sahl.messages

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : SahlActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var all: List<Conversation> = emptyList()
    private var query = ""
    private var unreadOnly = false
    private lateinit var list: ListView
    private lateinit var banner: LinearLayout
    private lateinit var empty: LinearLayout
    private lateinit var chips: LinearLayout
    private var shown: List<Conversation> = emptyList()

    private val perms = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)
    private fun granted() = perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { main.removeCallbacks(reload); main.postDelayed(reload, 300) }
    }
    private val reload = Runnable { load() }
    private var observing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frame = FrameLayout(this).apply { setBackgroundColor(col(R.color.bg)) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        root.addView(header(getString(R.string.app_name), false, listOf(
            R.drawable.ic_tune to { startActivity(Intent(this, SettingsActivity::class.java)) })))
        banner = card().apply { background = round(col(R.color.tonal), 22f); elevation = 0f }
        root.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), px(4), px(16), px(6)) })
        val (search, _) = searchBox(getString(R.string.search)) { query = it; filter() }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), px(2), px(16), px(6)) })
        chips = LinearLayout(this).apply { setPaddingRelative(px(16), px(2), px(16), px(6)) }
        root.addView(chips)
        val body = FrameLayout(this)
        list = ListView(this).apply {
            divider = null; selector = android.graphics.drawable.ColorDrawable(0); clipToPadding = false; setPadding(0, 0, 0, px(96))
        }
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            addView(icon(R.drawable.ic_chat, col(R.color.line), 64))
            addView(text(getString(R.string.no_messages), 15f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0) })
        }
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(fab(R.drawable.ic_add) { startActivity(Intent(this, ComposeActivity::class.java)) },
            FrameLayout.LayoutParams(px(58), px(58), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, px(22), px(26)) })
        setContentView(frame)
        Updater.dailyCheck(this)
        if (!crashOpen && !granted()) askPermissions()
    }

    override fun onResume() {
        super.onResume()
        renderBanner(); renderChips()
        if (granted()) {
            if (!observing) { contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer); observing = true }
            load()
        }
    }

    override fun onDestroy() { super.onDestroy(); if (observing) contentResolver.unregisterContentObserver(observer) }

    private fun askPermissions() {
        val p = perms.toMutableList()
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r); renderBanner(); if (granted()) load()
    }

    @Deprecated("Activity result")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data); renderBanner(); if (!granted()) askPermissions()
    }

    private fun renderBanner() {
        banner.removeAllViews()
        val msg: Int; val btn: Int; val go: () -> Unit
        when {
            !Sms.isDefault(this) -> { msg = R.string.make_default; btn = R.string.set_default; go = {
                try { startActivityForResult(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS), 5) } catch (e: Exception) { }
            } }
            !granted() -> { msg = R.string.need_permission; btn = R.string.allow; go = { askPermissions() } }
            else -> { banner.visibility = View.GONE; return }
        }
        banner.visibility = View.VISIBLE
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(icon(R.drawable.ic_chat, col(R.color.emerald), 22))
        row.addView(text(getString(msg), 14f).apply { setPaddingRelative(px(12), 0, px(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(getString(btn), 14f, 0xFFFFFFFF.toInt(), true).apply {
            background = round(col(R.color.emerald), 20f); setPadding(px(14), px(8), px(14), px(8)); setOnClickListener { go() }
        }.press(20f))
        banner.addView(row)
    }

    private fun renderChips() {
        chips.removeAllViews()
        fun chip(label: Int, on: Boolean, go: () -> Unit) = chips.addView(text(getString(label), 13f,
            if (on) 0xFFFFFFFF.toInt() else col(R.color.text), on).apply {
            background = round(if (on) col(R.color.emerald) else col(R.color.tonal), 18f)
            setPadding(px(16), px(8), px(16), px(8)); setOnClickListener { go() }
        }.press(18f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(8) })
        chip(R.string.all, !unreadOnly) { unreadOnly = false; renderChips(); filter() }
        chip(R.string.unread, unreadOnly) { unreadOnly = true; renderChips(); filter() }
    }

    private fun load() {
        Thread {
            Names.clear()
            val c = try { Sms.conversations(this) } catch (e: Exception) { emptyList() }
            runOnUiThread { all = c; filter() }
        }.start()
    }

    private fun filter() {
        val words = Norm.text(query).split(' ').filter { it.isNotBlank() }
        val dq = Norm.digits(query)
        shown = all.filter { cv ->
            (!unreadOnly || cv.unread > 0) && (words.isEmpty() || words.all { w ->
                Norm.text(cv.name ?: "").contains(w) || Norm.text(cv.snippet).contains(w) } || (dq.length >= 3 && Norm.digits(cv.address).contains(dq)))
        }
        // Search inside every message too
        if (words.isNotEmpty() && query.length >= 2) {
            val extra = deepSearch(query).filter { t -> shown.none { it.threadId == t } }
            if (extra.isNotEmpty()) shown = shown + all.filter { it.threadId in extra }
        }
        list.adapter = Adapter()
        empty.visibility = if (shown.isEmpty() && granted()) View.VISIBLE else View.GONE
    }

    private fun deepSearch(q: String): Set<Long> = try {
        val out = HashSet<Long>()
        contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID), "${Telephony.Sms.BODY} LIKE ?", arrayOf("%$q%"), null)?.use {
            while (it.moveToNext() && out.size < 50) out.add(it.getLong(0))
        }
        out
    } catch (e: Exception) { emptySet() }

    private fun open(cv: Conversation) {
        startActivity(Intent(this, ThreadActivity::class.java).putExtra("thread", cv.threadId).putExtra("address", cv.address))
    }

    private fun actions(cv: Conversation) {
        val items = mutableListOf<Triple<Int, String, () -> Unit>>(
            Triple(R.drawable.ic_check, getString(R.string.mark_read)) { Thread { Sms.markRead(this, cv.threadId) }.start() },
            Triple(R.drawable.ic_call, getString(R.string.call)) { Sahl.callVia(this, cv.address) },
            Triple(R.drawable.ic_delete, getString(R.string.delete_chat)) {
                AlertDialog.Builder(this).setMessage(R.string.delete_chat_q)
                    .setPositiveButton(R.string.delete) { _, _ -> Thread { Sms.deleteThread(this, cv.threadId); runOnUiThread { load() } }.start() }
                    .setNegativeButton(R.string.cancel, null).show()
            })
        sheet(cv.name ?: cv.address, items)
    }

    private fun stamp(t: Long): String {
        val z = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
        val d = z.toLocalDate(); val today = LocalDate.now(); val loc = resources.configuration.locales[0]
        return when {
            d == today -> z.format(DateTimeFormatter.ofPattern("HH:mm", loc))
            d == today.minusDays(1) -> getString(R.string.yesterday)
            d.isAfter(today.minusDays(7)) -> z.format(DateTimeFormatter.ofPattern("EEE", loc))
            else -> z.format(DateTimeFormatter.ofPattern("d MMM", loc))
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = shown[p].threadId
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val c = shown[p]
            val title = c.name ?: c.address
            val row = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(px(18), px(10), px(18), px(10))
                setOnClickListener { open(c) }; setOnLongClickListener { actions(c); true }
            }.press(0f)
            val (f, iv, letter) = avatar(50)
            f.background = oval(avatarColor(title))
            letter.text = if (c.name != null) Norm.section(c.name).let { if (it == "#") "" else it } else ""
            if (letter.text.isEmpty()) f.addView(ImageView(this@MainActivity).apply {
                setImageResource(if (c.address.any { it.isLetter() }) R.drawable.ic_work else R.drawable.ic_people)
                imageTintList = ColorStateList.valueOf(0xCCFFFFFF.toInt())
            }, FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER))
            Thumbs.load(this@MainActivity, c.photo, iv, letter)
            row.addView(f)
            val t = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(px(14), 0, px(8), 0) }
            val top = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            val bold = c.unread > 0
            top.addView(text(title, 16f, col(R.color.text), bold).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(text(stamp(c.date), 12f, if (bold) col(R.color.emerald) else col(R.color.sub), bold))
            t.addView(top)
            val bottom = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            bottom.addView(text((if (c.outgoing) getString(R.string.you) + " " else "") + c.snippet.replace('\n', ' '), 14f,
                if (bold) col(R.color.text) else col(R.color.sub)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, px(2), 0, 0) },
                LinearLayout.LayoutParams(0, -2, 1f))
            if (bold) bottom.addView(text(c.unread.toString(), 11f, 0xFFFFFFFF.toInt(), true).apply {
                gravity = Gravity.CENTER; background = round(col(R.color.emerald), 10f); setPadding(px(7), px(2), px(7), px(2))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8) })
            t.addView(bottom)
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            return SwipeRow(this@MainActivity, row, col(R.color.bg),
                R.drawable.ic_call, col(R.color.ok), { Sahl.callVia(this@MainActivity, c.address) },
                R.drawable.ic_check, col(R.color.emerald), { Thread { Sms.markRead(this@MainActivity, c.threadId) }.start() })
        }
    }
}
