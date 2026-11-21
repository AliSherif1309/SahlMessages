package com.sahl.messages

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** All starred messages in one place. */
class StarredActivity : SahlActivity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(getString(R.string.starred), true))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14), px(4), px(14), px(30)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); build() }

    private fun build() {
        body.removeAllViews()
        val stars = Stars.list(this)
        if (stars.isEmpty()) {
            body.addView(icon(R.drawable.ic_star_border, col(R.color.line), 64).apply { (layoutParams as LinearLayout.LayoutParams).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = px(80) } })
            body.addView(text(getString(R.string.no_starred), 15f, col(R.color.sub)).apply { gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0) })
            return
        }
        val fmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", resources.configuration.locales[0])
        for (s in stars) {
            val c = card().apply {
                setOnClickListener { startActivity(Intent(this@StarredActivity, ThreadActivity::class.java).putExtra("thread", s.thread).putExtra("address", s.address)) }
                setOnLongClickListener {
                    sheet(null, listOf(
                        Triple(R.drawable.ic_copy, getString(R.string.copy)) {
                            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("text", s.body)); toast(R.string.copied)
                        },
                        Triple(R.drawable.ic_star_border, getString(R.string.unstar)) { Stars.toggle(this@StarredActivity, s.key, s.thread, s.address, s.body, s.date); build() }))
                    true
                }
            }.press(22f)
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(icon(R.drawable.ic_star, col(R.color.gold), 16))
            top.addView(text(Names.of(this, s.address.substringBefore(',')).first ?: s.address, 14f, col(R.color.emerald), true).apply {
                setPaddingRelative(px(6), 0, 0, 0); maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(text(Instant.ofEpochMilli(s.date).atZone(ZoneId.systemDefault()).format(fmt), 12f, col(R.color.sub)))
            c.addView(top)
            c.addView(text(s.body, 15f).apply { setPadding(0, px(6), 0, 0); setTextIsSelectable(false) })
            body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        }
    }
}
