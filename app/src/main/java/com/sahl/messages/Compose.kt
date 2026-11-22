package com.sahl.messages

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Place a call through Sahl Phone (Sahl Union), or the normal dialer. */
fun Sahl.callVia(a: android.app.Activity, number: String) {
    val i = try { a.packageManager.getPackageInfo("com.sahl.phone", 0)
        Intent("com.sahl.phone.action.CALL").setPackage("com.sahl.phone").putExtra("number", number)
    } catch (e: Exception) { Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))) }
    try { a.startActivity(i) } catch (e: Exception) { }
}

/** New message: pick a contact or type a number. Also opens for sms:/smsto: links and shares from other apps. */
class ComposeActivity : SahlActivity() {
    private data class P(val name: String, val number: String, val key: String, val digits: String, val photo: String?)
    private var people: List<P> = emptyList()
    private lateinit var list: ListView
    private var shared: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // sms:/smsto: links go straight to the conversation
        val d = intent.data
        shared = intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        if (d != null && (d.scheme == "sms" || d.scheme == "smsto" || d.scheme == "mms" || d.scheme == "mmsto")) {
            val to = d.schemeSpecificPart?.substringBefore('?')?.split(',', ';')?.firstOrNull()?.trim()
            if (!to.isNullOrBlank()) { open(to); finish(); return }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(getString(R.string.new_message), true, large = false))
        val (box, et) = searchBox(getString(R.string.to_hint)) { q -> filter(q) }
        et.inputType = android.text.InputType.TYPE_CLASS_TEXT
        et.setOnEditorActionListener { _, _, _ -> val t = et.text.toString().trim(); if (Norm.digits(t).length >= 3) open(t); true }
        root.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(px(16), px(4), px(16), px(6)) })
        list = ListView(this).apply { divider = null; selector = android.graphics.drawable.ColorDrawable(0) }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        et.requestFocus()
        Thread {
            val out = ArrayList<P>()
            try {
                contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.PHOTO_THUMBNAIL_URI), null, null, null)?.use {
                    val seen = HashSet<String>()
                    while (it.moveToNext()) {
                        val n = it.getString(1) ?: continue; val name = it.getString(0) ?: n
                        if (seen.add(name + Norm.phoneKey(n))) out.add(P(name, n, Norm.text(name), Norm.digits(n), it.getString(2)))
                    }
                }
            } catch (e: Exception) { }
            out.sortBy { it.key }
            runOnUiThread { people = out; filter("") }
        }.start()
    }

    private fun filter(q: String) {
        val words = Norm.text(q).split(' ').filter { it.isNotBlank() }
        val dq = Norm.digits(q)
        val l = people.filter { p -> words.isEmpty() || words.all { p.key.contains(it) } || (dq.length >= 3 && p.digits.contains(dq)) }.toMutableList()
        if (dq.length >= 3 && l.none { Norm.phoneKey(it.number) == Norm.phoneKey(q) }) l.add(0, P(getString(R.string.send_to, q.trim()), q.trim(), "", dq, null))
        list.adapter = object : BaseAdapter() {
            override fun getCount() = l.size
            override fun getItem(i: Int): Any = l[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup): View {
                val p = l[i]
                val row = LinearLayout(this@ComposeActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(px(18), px(9), px(18), px(9)); setOnClickListener { open(p.number) }
                }.press(0f)
                val (f, iv, letter) = avatar(44)
                f.background = oval(avatarColor(p.name)); letter.text = Norm.section(p.name).let { if (it == "#") "+" else it }
                Thumbs.load(this@ComposeActivity, p.photo, iv, letter)
                row.addView(f)
                val t = LinearLayout(this@ComposeActivity).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(px(14), 0, 0, 0) }
                t.addView(text(p.name, 16f).apply { maxLines = 1 })
                t.addView(text(p.number, 13f, col(R.color.sub)).apply { textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
                row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                return row
            }
        }
    }

    private fun open(number: String) {
        startActivity(Intent(this, ThreadActivity::class.java).putExtra("address", number)
            .putExtra("thread", Sms.existingThreadFor(this, number)).apply { shared?.let { putExtra("body", it) } })
        finish()
    }
}
