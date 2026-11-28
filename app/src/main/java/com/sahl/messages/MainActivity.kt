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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
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
    private var cat = -1                       // -1 = all categories
    private lateinit var dates: LinearLayout
    private var dateFilter = 0                 // 0 any · 1 today · 2 yesterday · 3 last 7 days · 4 last 30 days · 5 picked day
    private var pickedDay: java.time.LocalDate? = null
    private var dateThreads: Set<Long>? = null // threads with messages in the chosen period
    private lateinit var list: ListView
    private lateinit var banner: LinearLayout
    private lateinit var empty: LinearLayout
    private lateinit var chips: LinearLayout
    private var shown: List<Conversation> = emptyList()
    private val adapter = Adapter()
    private var simLabels: Map<Int, String> = emptyMap()
    private val selected = LinkedHashSet<Long>()
    private val selecting get() = selected.isNotEmpty()
    private lateinit var normalHeader: View
    private lateinit var selectBar: LinearLayout
    private lateinit var selectTitle: TextView
    private lateinit var folderBar: LinearLayout
    private lateinit var folderTitle: TextView
    private lateinit var folderAction: TextView
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var datesScroll: HorizontalScrollView
    private lateinit var bankCard: LinearLayout
    private lateinit var fabView: View
    private var folder = 0                       // 0 inbox · 1 archived · 2 spam · 3 locked
    private var bankExpanded = false
    private var lastBankReport: BankReport? = null
    private var hiddenLookups: Set<String> = emptySet()
    private var searchMatches: Map<Long, Pair<Long, String>> = emptyMap()
    private var pendingSearch: Runnable? = null
    private var lastDeepQuery = ""
    private var lastBankKey = ""

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
        normalHeader = header(getString(R.string.app_name), false, listOf(
            R.drawable.ic_star to { startActivity(Intent(this, StarredActivity::class.java)) },
            R.drawable.ic_tune to { startActivity(Intent(this, SettingsActivity::class.java)) },
            R.drawable.ic_more to { overflowMenu() }))
        root.addView(normalHeader)
        selectBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(col(R.color.tonal))
            setPadding(px(8), px(10), px(8), px(10))
            visibility = View.GONE
            addView(iconButton(R.drawable.ic_close, 0, col(R.color.text), 44, 22) { clearSelection() })
            selectTitle = text("", 18f, col(R.color.text), true).apply { setPaddingRelative(px(8), 0, 0, 0) }
            addView(selectTitle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconButton(R.drawable.ic_check, 0, col(R.color.emerald), 44, 22) { markSelectedRead() })
            addView(iconButton(R.drawable.ic_star, 0, col(R.color.text), 44, 22) { togglePinSelected() })
            addView(iconButton(R.drawable.ic_bell, 0, col(R.color.text), 44, 22) { toggleMuteSelected() })
            addView(iconButton(R.drawable.ic_delete, 0, col(R.color.danger), 44, 22) { deleteSelected() })
            addView(iconButton(R.drawable.ic_more, 0, col(R.color.text), 44, 22) { selectionMenu() })
        }
        root.addView(selectBar)
        folderBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(col(R.color.card))
            setPadding(px(8), px(8), px(12), px(8))
            visibility = View.GONE
            addView(iconButton(R.drawable.ic_back, 0, col(R.color.text), 42, 22) { setFolder(0) })
            folderTitle = text("", 18f, col(R.color.text), true).apply { setPaddingRelative(px(6), 0, 0, 0) }
            addView(folderTitle, LinearLayout.LayoutParams(0, -2, 1f))
            folderAction = text("", 13f, col(R.color.emerald), true).apply {
                background = round(col(R.color.tonal), 14f)
                setPadding(px(12), px(6), px(12), px(6))
                visibility = View.GONE
            }.press(14f)
            addView(folderAction)
        }
        root.addView(folderBar)
        banner = card().apply { background = round(col(R.color.tonal), 22f); elevation = 0f }
        root.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), px(4), px(16), px(6)) })
        val (search, _) = searchBox(getString(R.string.search)) { query = it; filter() }
        root.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), px(2), px(16), px(6)) })
        chips = LinearLayout(this).apply { setPaddingRelative(px(16), px(2), px(16), px(6)) }
        chipsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        root.addView(chipsScroll)
        dates = LinearLayout(this).apply { setPaddingRelative(px(16), 0, px(16), px(6)) }
        datesScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(dates) }
        root.addView(datesScroll)
        bankCard = card().apply {
            background = round(col(R.color.card), 20f, col(R.color.line))
            visibility = View.GONE
        }
        root.addView(bankCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), px(2), px(16), px(6)) })
        val body = FrameLayout(this)
        list = ListView(this).apply {
            divider = null; selector = android.graphics.drawable.ColorDrawable(0); clipToPadding = false; setPadding(0, 0, 0, px(96))
            adapter = this@MainActivity.adapter
        }
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE
            addView(icon(R.drawable.ic_chat, col(R.color.line), 64))
            addView(text(getString(R.string.no_messages), 15f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0) })
        }
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        fabView = fab(R.drawable.ic_add) { startActivity(Intent(this, ComposeActivity::class.java)) }
        frame.addView(fabView,
            FrameLayout.LayoutParams(px(58), px(58), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, px(22), px(26)) })
        setContentView(frame)
        Updater.dailyCheck(this)
        Updater.checkOnStart(this)
        if (!crashOpen && !granted()) askPermissions()
    }

    override fun onResume() {
        super.onResume()
        renderBanner(); renderChips(); renderDates()
        if (granted()) {
            val s = Sms.sims(this)
            simLabels = if (s.size > 1) s.associate { it.subId to it.label } else emptyMap()
            if (!observing) { contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer); observing = true }
            load()
        }
    }

    override fun onDestroy() { super.onDestroy(); if (observing) contentResolver.unregisterContentObserver(observer) }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (selecting) clearSelection()
        else if (folder != 0) setFolder(0)
        else super.onBackPressed()
    }

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
        super.onActivityResult(req, res, data); renderBanner()
        if (req == 70 && res == RESULT_OK) {
            val uri = data?.data ?: return
            val report = lastBankReport ?: return
            Thread {
                val ok = try {
                    contentResolver.openOutputStream(uri)?.use { it.write(BankSummary.toCsv(report).toByteArray(Charsets.UTF_8)) }
                    true
                } catch (_: Exception) { false }
                runOnUiThread { if (ok) toast(R.string.csv_exported) else toast(R.string.backup_failed) }
            }.start()
            return
        }
        // Android blocks SMS for apps installed from a file until "restricted settings" are allowed
        if (req == 5 && !Sms.isDefault(this)) restrictedHelp() else if (!granted()) askPermissions()
    }

    private fun restrictedHelp() {
        AlertDialog.Builder(this).setTitle(R.string.restricted_title).setMessage(R.string.restricted_steps)
            .setPositiveButton(R.string.open_app_info) { _, _ ->
                try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } catch (e: Exception) { }
            }
            .setNegativeButton(R.string.cancel, null).show()
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
        fun chip(label: String, on: Boolean, go: () -> Unit) = chips.addView(text(label, 13f,
            if (on) 0xFFFFFFFF.toInt() else col(R.color.text), on).apply {
            background = round(if (on) col(R.color.emerald) else col(R.color.tonal), 18f)
            setPadding(px(16), px(8), px(16), px(8)); setOnClickListener { go() }
        }.press(18f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(8) })
        fun count(k: Int) = all.count { it.category == k && it.unread > 0 }.let { if (it > 0) " $it" else "" }
        chip(getString(R.string.all), !unreadOnly && cat == -1) { unreadOnly = false; cat = -1; renderChips(); filter() }
        chip(getString(R.string.unread), unreadOnly) { unreadOnly = true; cat = -1; renderChips(); filter() }
        chip(getString(R.string.cat_personal) + count(Category.PERSONAL), cat == Category.PERSONAL) { cat = Category.PERSONAL; unreadOnly = false; renderChips(); filter() }
        chip(getString(R.string.cat_codes), cat == Category.CODES) { cat = Category.CODES; unreadOnly = false; renderChips(); filter() }
        chip(getString(R.string.cat_banks) + count(Category.BANKS), cat == Category.BANKS) { cat = Category.BANKS; unreadOnly = false; renderChips(); filter() }
        chip(getString(R.string.cat_offers) + count(Category.OFFERS), cat == Category.OFFERS) { cat = Category.OFFERS; unreadOnly = false; renderChips(); filter() }
    }

    /** The last list is saved, so the app shows it instantly next time while it refreshes. */
    private fun saveCache(l: List<Conversation>) = Sms.saveCache(this, l)

    private fun readCache(): List<Conversation> = Sms.readCache(this)

    private fun renderDates() {
        dates.removeAllViews()
        fun dchip(label: String, on: Boolean, go: () -> Unit) = dates.addView(text(label, 12f,
            if (on) col(R.color.emerald) else col(R.color.sub), on).apply {
            background = if (on) round(col(R.color.tonal), 14f) else round(0, 14f, col(R.color.line))
            setPadding(px(12), px(6), px(12), px(6)); setOnClickListener { go() }
        }.press(14f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6) })
        dchip(getString(R.string.any_time), dateFilter == 0) { setDate(0) }
        dchip(getString(R.string.today), dateFilter == 1) { setDate(1) }
        dchip(getString(R.string.yesterday), dateFilter == 2) { setDate(2) }
        dchip(getString(R.string.last_7), dateFilter == 3) { setDate(3) }
        dchip(getString(R.string.last_30), dateFilter == 4) { setDate(4) }
        dchip(if (dateFilter == 5 && pickedDay != null) pickedDay!!.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", resources.configuration.locales[0]))
              else getString(R.string.pick_date), dateFilter == 5) {
            val d = pickedDay ?: java.time.LocalDate.now()
            android.app.DatePickerDialog(this, { _, y, m, day -> pickedDay = java.time.LocalDate.of(y, m + 1, day); setDate(5) },
                d.year, d.monthValue - 1, d.dayOfMonth).show()
        }
    }

    /** Finds conversations that have any message in the chosen period (not just the latest one). */
    private fun setDate(i: Int) {
        dateFilter = i; renderDates()
        if (i == 0) { dateThreads = null; filter(); return }
        val zone = java.time.ZoneId.systemDefault(); val today = java.time.LocalDate.now()
        val (from, to) = when (i) {
            1 -> today to today.plusDays(1)
            2 -> today.minusDays(1) to today
            3 -> today.minusDays(6) to today.plusDays(1)
            4 -> today.minusDays(29) to today.plusDays(1)
            else -> (pickedDay ?: today).let { it to it.plusDays(1) }
        }
        val a = from.atStartOfDay(zone).toInstant().toEpochMilli(); val b = to.atStartOfDay(zone).toInstant().toEpochMilli()
        Thread {
            val set = HashSet<Long>()
            try {
                contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID), "${Telephony.Sms.DATE}>=? AND ${Telephony.Sms.DATE}<?",
                    arrayOf(a.toString(), b.toString()), null)?.use { while (it.moveToNext()) set.add(it.getLong(0)) }
                contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("thread_id"), "date>=? AND date<?",
                    arrayOf((a / 1000).toString(), (b / 1000).toString()), null)?.use { while (it.moveToNext()) set.add(it.getLong(0)) }
            } catch (e: Exception) { }
            runOnUiThread { dateThreads = set; filter() }
        }.start()
    }

    private fun load() {
        val cached = readCache()
        if (all.isEmpty() && cached.isNotEmpty()) {
            all = cached
            Names.seed(cached)
            renderChips(); filter()
        }
        Thread {
            hiddenLookups = ChatVault.contactsVaultLookups(this)
            // 1. Fast pass: load latest conversations using already-cached contact names so new messages show immediately
            val fast = try { Sms.conversations(this, lookupContacts = false) } catch (e: Exception) { emptyList() }
            if (fast.isNotEmpty()) runOnUiThread { all = fast; renderChips(); filter() }
            // 2. Full pass: resolve any missing contact names in the background
            val c = try { Sms.conversations(this, lookupContacts = true) } catch (e: Exception) { fast }
            Spam.warmGuardCache(this, c.filter { !it.saved && !it.group }.map { it.address })
            runOnUiThread { all = c; renderChips(); filter() }
            saveCache(c)
            Cleanup.run(this)
            Scheduled.sendDue(this)
        }.start()
    }

    private fun bucketOf(cv: Conversation): Int = when {
        ChatVault.isLocked(this, cv.threadId, cv.lookup, hiddenLookups) -> 3
        Spam.isSpam(this, cv.threadId, cv.address, cv.snippet) -> 2
        Archive.isArchived(this, cv.threadId) -> 1
        else -> 0
    }

    private fun setFolder(f: Int) {
        if (f == 3 && !ChatVault.unlocked()) {
            ChatVault.unlock(this) { setFolder(3) }
            return
        }
        folder = f
        clearSelection()
        syncSelectionChrome()
        filter()
    }

    private fun filter() {
        val words = Norm.text(query).split(' ').filter { it.isNotBlank() }
        val dq = Norm.digits(query)
        val inFolder = all.filter { bucketOf(it) == folder }
        shown = inFolder.filter { cv ->
            (folder != 0 || ((!unreadOnly || cv.unread > 0) && (cat == -1 || cv.category == cat) && (dateThreads?.contains(cv.threadId) ?: true))) &&
                (words.isEmpty() || words.all { w ->
                    Norm.text(cv.name ?: "").contains(w) || Norm.text(cv.snippet).contains(w)
                } || (dq.length >= 3 && Norm.digits(cv.address).contains(dq)))
        }
        // Search inside every message asynchronously (debounced) so typing never blocks the UI thread
        if (words.isNotEmpty() && query.length >= 2) {
            val q = query
            if (q == lastDeepQuery && searchMatches.isNotEmpty()) {
                val extraIds = searchMatches.keys.filter { t -> shown.none { it.threadId == t } }.toSet()
                if (extraIds.isNotEmpty()) shown = shown + inFolder.filter { it.threadId in extraIds }
            } else {
                pendingSearch?.let { main.removeCallbacks(it) }
                val r = Runnable {
                    Thread {
                        val matches = deepSearch(q)
                        runOnUiThread {
                            if (query == q) {
                                lastDeepQuery = q
                                searchMatches = matches
                                filter()
                            }
                        }
                    }.start()
                }
                pendingSearch = r
                main.postDelayed(r, 180)
            }
        } else {
            pendingSearch?.let { main.removeCallbacks(it) }
            pendingSearch = null
            lastDeepQuery = ""
            searchMatches = emptyMap()
        }
        val validIds = all.map { it.threadId }.toSet()
        selected.retainAll(validIds)
        syncSelectionChrome()
        refreshBankSummary()
        adapter.notifyDataSetChanged()
        empty.visibility = if (shown.isEmpty() && granted()) View.VISIBLE else View.GONE
    }

    private fun deepSearch(q: String): Map<Long, Pair<Long, String>> = try {
        val out = LinkedHashMap<Long, Pair<Long, String>>()
        contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.BODY),
            "${Telephony.Sms.BODY} LIKE ?",
            arrayOf("%$q%"),
            "${Telephony.Sms.DATE} DESC"
        )?.use {
            while (it.moveToNext() && out.size < 60) {
                val tid = it.getLong(1)
                if (tid !in out) out[tid] = it.getLong(0) to (it.getString(2) ?: "")
            }
        }
        out
    } catch (e: Exception) { emptyMap() }

    private fun refreshBankSummary() {
        if (folder != 0 || cat != Category.BANKS || !granted()) {
            bankCard.visibility = View.GONE
            return
        }
        val bankConvs = all.filter { bucketOf(it) == 0 && it.category == Category.BANKS }
        if (bankConvs.isEmpty()) {
            bankCard.visibility = View.GONE
            return
        }
        val key = bankConvs.joinToString(",") { "${it.threadId}:${it.date}" }
        if (key == lastBankKey && lastBankReport != null) {
            renderBankCard(lastBankReport!!)
            return
        }
        Thread {
            val report = BankSummary.analyze(this, bankConvs)
            runOnUiThread {
                lastBankKey = key
                if (folder == 0 && cat == Category.BANKS) renderBankCard(report)
            }
        }.start()
    }

    private fun renderBankCard(report: BankReport) {
        lastBankReport = report
        bankCard.removeAllViews()
        bankCard.visibility = View.VISIBLE
        val headerRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { bankExpanded = !bankExpanded; renderBankCard(report) }
        }
        headerRow.addView(icon(R.drawable.ic_work, col(R.color.emerald), 20))
        val spendStr = report.monthSpend.entries.joinToString(" · ") { BankSummary.formatMoney(it.key, it.value) }
            .ifEmpty { getString(R.string.no_bank_tx) }
        val hdrText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(px(10), 0, px(8), 0) }
        hdrText.addView(text(getString(R.string.bank_summary), 14f, col(R.color.emerald), true))
        hdrText.addView(text("${getString(R.string.this_month_spend)}: $spendStr", 13f, col(R.color.text), true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        headerRow.addView(hdrText, LinearLayout.LayoutParams(0, -2, 1f))
        headerRow.addView(text(if (bankExpanded) "▲" else "▼", 13f, col(R.color.sub), true).apply { setPadding(px(6), 0, px(2), 0) })
        bankCard.addView(headerRow)

        if (!bankExpanded) return

        if (report.monthCredit.isNotEmpty()) {
            val credStr = report.monthCredit.entries.joinToString(" · ") { BankSummary.formatMoney(it.key, it.value) }
            bankCard.addView(text("${getString(R.string.this_month_credit)}: $credStr", 12.5f, col(R.color.ok), true).apply {
                setPadding(0, px(8), 0, 0)
            })
        }
        if (report.balances.isNotEmpty()) {
            bankCard.addView(text(getString(R.string.latest_balances), 12f, col(R.color.sub), true).apply { setPadding(0, px(8), 0, px(2)) })
            for (b in report.balances.take(4)) {
                val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(2), 0, px(2)) }
                r.addView(text(b.bank, 13f, col(R.color.text)).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(text(BankSummary.formatMoney(b.currency, b.balance), 13f, col(R.color.emerald), true))
                bankCard.addView(r)
            }
        }
        if (report.recent.isNotEmpty()) {
            bankCard.addView(text(getString(R.string.recent_transactions), 12f, col(R.color.sub), true).apply { setPadding(0, px(8), 0, px(2)) })
            for (tx in report.recent.take(5)) {
                val r = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(4), 0, px(4))
                    setOnClickListener {
                        all.firstOrNull { it.threadId == tx.threadId }?.let { open(it) }
                    }
                }.press(10f)
                r.addView(text(tx.merchant, 13f, col(R.color.text)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f))
                val sign = if (tx.isDebit) "-" else "+"
                r.addView(text("$sign${BankSummary.formatMoney(tx.currency, tx.amount)}", 13f,
                    col(if (tx.isDebit) R.color.danger else R.color.ok), true))
                bankCard.addView(r)
            }
            bankCard.addView(text(getString(R.string.export_csv), 13f, 0xFFFFFFFF.toInt(), true).apply {
                gravity = Gravity.CENTER
                background = round(col(R.color.emerald), 14f)
                setPadding(px(14), px(8), px(14), px(8))
                setOnClickListener {
                    try {
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("text/csv")
                            .putExtra(Intent.EXTRA_TITLE, "SahlBankSummary-" + java.time.LocalDate.now() + ".csv"), 70)
                    } catch (_: Exception) { }
                }
            }.press(14f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        }
    }

    private fun markReadLocally(threadId: Long) = markReadLocally(setOf(threadId))

    private fun markReadLocally(threadIds: Set<Long>) {
        var changed = false
        all = all.map { if (it.threadId in threadIds && it.unread > 0) { changed = true; it.copy(unread = 0) } else it }
        if (changed) { renderChips(); filter(); saveCache(all) }
    }

    private fun open(cv: Conversation) {
        if (cv.unread > 0 && Sms.isDefault(this)) markReadLocally(cv.threadId)
        val i = Intent(this, ThreadActivity::class.java).putExtra("thread", cv.threadId)
            .putExtra("address", if (cv.group) cv.members.joinToString(",") else cv.address)
        searchMatches[cv.threadId]?.let { (msgId, _) ->
            i.putExtra("highlight_msg_id", msgId)
            if (query.isNotBlank()) i.putExtra("search_query", query)
        }
        startActivity(i)
    }

    private fun syncSelectionChrome() {
        val on = selecting
        normalHeader.visibility = if (on || folder != 0) View.GONE else View.VISIBLE
        selectBar.visibility = if (on) View.VISIBLE else View.GONE
        folderBar.visibility = if (!on && folder != 0) View.VISIBLE else View.GONE
        chipsScroll.visibility = if (folder == 0) View.VISIBLE else View.GONE
        datesScroll.visibility = if (folder == 0) View.VISIBLE else View.GONE
        fabView.visibility = if (on || folder != 0) View.GONE else View.VISIBLE
        if (on) selectTitle.text = getString(R.string.selected_count, selected.size)
        if (!on && folder != 0) {
            folderTitle.text = when (folder) {
                1 -> getString(R.string.archived)
                2 -> getString(R.string.spam_blocked)
                else -> getString(R.string.locked_chats)
            }
            when (folder) {
                2 -> {
                    folderAction.visibility = View.VISIBLE
                    folderAction.text = getString(R.string.rules)
                    folderAction.setOnClickListener { startActivity(Intent(this, SpamRulesActivity::class.java)) }
                }
                3 -> {
                    folderAction.visibility = View.VISIBLE
                    folderAction.text = getString(R.string.lock_now)
                    folderAction.setOnClickListener { ChatVault.lock(); setFolder(0) }
                }
                else -> folderAction.visibility = View.GONE
            }
        }
    }

    private fun updateSelectionUi() {
        syncSelectionChrome()
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelect(threadId: Long) {
        if (!selected.add(threadId)) selected.remove(threadId)
        updateSelectionUi()
    }

    private fun clearSelection() {
        selected.clear()
        updateSelectionUi()
    }

    private fun selectAllShown() {
        selected.addAll(shown.map { it.threadId })
        updateSelectionUi()
    }

    private fun selectedConversations(): List<Conversation> = all.filter { it.threadId in selected }

    private fun overflowMenu() {
        var archCount = 0; var spamCount = 0; var lockCount = 0
        for (cv in all) when (bucketOf(cv)) {
            1 -> archCount++
            2 -> spamCount++
            3 -> lockCount++
        }
        val schedCount = Scheduled.all(this).size
        val items = mutableListOf(
            Triple(R.drawable.ic_check, getString(R.string.mark_all_read)) { markAllRead() }
        )
        if (shown.isNotEmpty()) {
            items.add(Triple(R.drawable.ic_chat, getString(R.string.select_all)) { selectAllShown() })
        }
        items.add(Triple(R.drawable.ic_history, getString(R.string.archived) + if (archCount > 0) " ($archCount)" else "") { setFolder(1) })
        items.add(Triple(R.drawable.ic_block, getString(R.string.spam_blocked) + if (spamCount > 0) " ($spamCount)" else "") { setFolder(2) })
        items.add(Triple(R.drawable.ic_lock, getString(R.string.locked_chats) + if (lockCount > 0) " ($lockCount)" else "") { setFolder(3) })
        items.add(Triple(R.drawable.ic_history, getString(R.string.scheduled_messages) + if (schedCount > 0) " ($schedCount)" else "") {
            startActivity(Intent(this, ScheduledActivity::class.java))
        })
        sheet(null, items)
    }

    private fun markAllRead() {
        if (!Sms.isDefault(this)) { toast(R.string.make_default); return }
        if (all.none { it.unread > 0 }) { toast(R.string.no_unread); return }
        all = all.map { if (it.unread > 0) it.copy(unread = 0) else it }
        renderChips(); filter(); saveCache(all)
        toast(R.string.all_marked_read)
        Thread { Sms.markAllRead(this) }.start()
    }

    private fun markSelectedRead() {
        if (!Sms.isDefault(this)) { toast(R.string.make_default); return }
        val ids = selected.toSet()
        clearSelection()
        markReadLocally(ids)
        toast(R.string.marked_read)
        Thread { for (id in ids) Sms.markRead(this, id) }.start()
    }

    private fun markSelectedUnread() {
        if (!Sms.isDefault(this)) { toast(R.string.make_default); return }
        val ids = selected.toSet()
        clearSelection()
        var changed = false
        all = all.map { if (it.threadId in ids && it.unread == 0) { changed = true; it.copy(unread = 1) } else it }
        if (changed) { renderChips(); filter(); saveCache(all) }
        Thread { for (id in ids) Sms.markUnread(this, id) }.start()
    }

    private fun togglePinSelected() {
        val cvs = selectedConversations()
        val pin = cvs.any { !it.pinned }
        val ids = selected.toSet()
        Prefs.setPinned(this, ids, pin)
        clearSelection()
        load()
    }

    private fun toggleMuteSelected() {
        val cvs = selectedConversations()
        val mute = cvs.any { !it.muted }
        val ids = selected.toSet()
        Prefs.setMuted(this, ids, mute)
        clearSelection()
        load()
    }

    private fun toggleHiddenSelected() {
        val ids = selected.toSet()
        val hide = ids.any { !Prefs.hiddenChat(this, it) }
        Prefs.setHiddenChat(this, ids, hide)
        toast(if (hide) R.string.previews_hidden else R.string.previews_shown)
        clearSelection()
    }

    private fun archiveSelected(on: Boolean) {
        val ids = selected.toSet()
        Archive.setArchived(this, ids, on)
        toast(if (on) R.string.archived_toast else R.string.unarchived_toast)
        clearSelection()
        filter()
    }

    private fun blockSelected(block: Boolean) {
        val cvs = selectedConversations()
        for (cv in cvs) {
            if (block) Spam.block(this, cv.threadId, cv.address)
            else Spam.unblock(this, cv.threadId, cv.address)
        }
        toast(if (block) R.string.blocked_moved_spam else R.string.unblocked_toast)
        clearSelection()
        filter()
    }

    private fun lockSelected(lock: Boolean) {
        val ids = selected.toSet()
        ChatVault.setLocked(this, ids, lock)
        toast(if (lock) R.string.locked_toast else R.string.unlocked_toast)
        clearSelection()
        filter()
    }

    private fun deleteSelected() {
        val ids = selected.toSet()
        val msg = if (ids.size == 1) getString(R.string.delete_chat_q) else getString(R.string.delete_chats_q, ids.size)
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                clearSelection()
                all = all.filter { it.threadId !in ids }
                renderChips(); filter(); saveCache(all)
                Thread { for (id in ids) Sms.deleteThread(this, id); runOnUiThread { load() } }.start()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun selectionMenu() {
        val cvs = selectedConversations()
        val allShownSelected = shown.isNotEmpty() && shown.all { it.threadId in selected }
        val items = mutableListOf<Triple<Int, String, () -> Unit>>(
            Triple(R.drawable.ic_chat, getString(if (allShownSelected) R.string.deselect_all else R.string.select_all)) {
                if (allShownSelected) clearSelection() else selectAllShown()
            },
            Triple(R.drawable.ic_check, getString(R.string.mark_read)) { markSelectedRead() },
            Triple(R.drawable.ic_mail, getString(R.string.mark_unread)) { markSelectedUnread() },
            Triple(R.drawable.ic_star, getString(if (cvs.any { !it.pinned }) R.string.pin else R.string.unpin)) { togglePinSelected() },
            Triple(R.drawable.ic_bell, getString(if (cvs.any { !it.muted }) R.string.mute else R.string.unmute)) { toggleMuteSelected() },
            Triple(R.drawable.ic_visibility_off, getString(if (selected.any { !Prefs.hiddenChat(this, it) }) R.string.hide_previews else R.string.show_previews)) { toggleHiddenSelected() }
        )
        items.add(Triple(R.drawable.ic_history, getString(if (folder == 1) R.string.unarchive else R.string.archive)) {
            archiveSelected(folder != 1)
        })
        items.add(Triple(R.drawable.ic_block, getString(if (folder == 2) R.string.unblock else R.string.block_spam)) {
            blockSelected(folder != 2)
        })
        items.add(Triple(R.drawable.ic_lock, getString(if (folder == 3) R.string.unlock_chat else R.string.lock_chat)) {
            lockSelected(folder != 3)
        })
        if (cvs.size == 1) {
            items.add(Triple(R.drawable.ic_call, getString(R.string.call)) { Sahl.callVia(this, cvs[0].address); clearSelection() })
        }
        items.add(Triple(R.drawable.ic_delete, getString(R.string.delete_chat)) { deleteSelected() })
        sheet(getString(R.string.selected_count, selected.size), items)
    }

    private fun highlightSearch(text: String): CharSequence {
        val q = query.trim()
        if (q.length < 2) return text
        val sp = SpannableString(text)
        val lt = text.lowercase()
        val lq = q.lowercase()
        var idx = lt.indexOf(lq)
        val bg = col(R.color.gold)
        val fg = 0xFF1B2A26.toInt()
        while (idx >= 0) {
            sp.setSpan(BackgroundColorSpan(bg), idx, idx + lq.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(fg), idx, idx + lq.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            idx = lt.indexOf(lq, idx + lq.length)
        }
        return sp
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
        override fun hasStableIds() = true
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val c = shown[p]
            val title = c.name ?: c.address
            val isSel = c.threadId in selected
            val row = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(px(18), px(10), px(18), px(10))
                setBackgroundColor(col(if (isSel) R.color.tonal else R.color.bg))
                setOnClickListener { if (selecting) toggleSelect(c.threadId) else open(c) }
                setOnLongClickListener { toggleSelect(c.threadId); true }
            }.press(0f)
            val (f, iv, letter) = avatar(50)
            if (isSel) {
                f.background = oval(col(R.color.emerald))
                letter.visibility = View.GONE
                iv.visibility = View.GONE
                iv.tag = null
                f.addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                }, FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER))
            } else {
                f.background = oval(avatarColor(title))
                letter.text = if (c.name != null) Norm.section(c.name).let { if (it == "#") "" else it } else ""
                if (letter.text.isEmpty()) f.addView(ImageView(this@MainActivity).apply {
                    setImageResource(if (c.group) R.drawable.ic_people else if (c.address.any { it.isLetter() }) R.drawable.ic_work else R.drawable.ic_person_add)
                    imageTintList = ColorStateList.valueOf(0xCCFFFFFF.toInt())
                }, FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER))
                Thumbs.load(this@MainActivity, c.photo, iv, letter)
            }
            row.addView(f)
            val t = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(px(14), 0, px(8), 0) }
            val top = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            val bold = c.unread > 0
            top.addView(text(title, 16f, col(R.color.text), bold).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, -2, 1f))
            if (c.name != null && !c.saved) top.addView(text(getString(R.string.not_in_contacts), 11f, col(R.color.sub)).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = px(6)
                setPaddingRelative(0, 0, px(6), 0)
            })
            if (c.pinned) top.addView(icon(R.drawable.ic_star, col(R.color.gold), 14).apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = px(4) })
            if (c.muted) top.addView(icon(R.drawable.ic_bell, col(R.color.sub), 14).apply { alpha = 0.5f; (layoutParams as LinearLayout.LayoutParams).marginEnd = px(4) })
            simLabels[c.subId]?.let { sim ->
                top.addView(text(sim, 10f, col(R.color.sub), true).apply {
                    background = round(col(R.color.tonal), 8f); setPadding(px(6), px(1), px(6), px(1))
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6) })
            }
            top.addView(text(stamp(c.date), 12f, if (bold) col(R.color.emerald) else col(R.color.sub), bold))
            t.addView(top)
            val bottom = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            Sms.otp(c.snippet)?.let { code -> bottom.addView(text(code, 12f, col(R.color.emerald), true).apply {
                background = round(col(R.color.tonal), 10f); setPadding(px(8), px(2), px(8), px(2))
                setOnClickListener {
                    if (selecting) toggleSelect(c.threadId)
                    else {
                        getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("code", code))
                        toast(R.string.copied)
                    }
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6) }) }
            val matchedBody = searchMatches[c.threadId]?.second?.replace('\n', ' ')
            val draft = if (matchedBody == null) Drafts.get(this@MainActivity, c.threadId, c.address).trim().replace('\n', ' ') else ""
            val snippetText: CharSequence = when {
                matchedBody != null -> highlightSearch(matchedBody)
                draft.isNotEmpty() -> {
                    val prefix = getString(R.string.draft_prefix)
                    SpannableString(prefix + draft).apply {
                        setSpan(ForegroundColorSpan(col(R.color.danger)), 0, prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                else -> (if (c.outgoing) getString(R.string.you) + " " else "") + c.snippet.replace('\n', ' ')
            }
            bottom.addView(text(snippetText, 14f,
                if (bold) col(R.color.text) else col(R.color.sub)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, px(2), 0, 0) },
                LinearLayout.LayoutParams(0, -2, 1f))
            if (bold) bottom.addView(text(c.unread.toString(), 11f, 0xFFFFFFFF.toInt(), true).apply {
                gravity = Gravity.CENTER; background = round(col(R.color.emerald), 10f); setPadding(px(7), px(2), px(7), px(2))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8) })
            t.addView(bottom)
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            if (selecting) return row
            val leftIcon = if (c.unread > 0 || folder != 0) R.drawable.ic_check else R.drawable.ic_history
            val leftCol = if (c.unread > 0 || folder != 0) col(R.color.emerald) else col(R.color.gold)
            return SwipeRow(this@MainActivity, row, col(R.color.bg),
                R.drawable.ic_call, col(R.color.ok), { Sahl.callVia(this@MainActivity, c.address) },
                leftIcon, leftCol, {
                    if (c.unread > 0) {
                        if (!Sms.isDefault(this@MainActivity)) { toast(R.string.make_default); return@SwipeRow }
                        markReadLocally(c.threadId)
                        Thread { Sms.markRead(this@MainActivity, c.threadId) }.start()
                    } else if (folder == 0) {
                        Archive.setArchived(this@MainActivity, listOf(c.threadId), true)
                        toast(R.string.archived_toast)
                        filter()
                    } else if (folder == 1) {
                        Archive.setArchived(this@MainActivity, listOf(c.threadId), false)
                        toast(R.string.unarchived_toast)
                        filter()
                    }
                })
        }
    }
}
