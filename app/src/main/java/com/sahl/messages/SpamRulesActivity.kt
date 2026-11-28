package com.sahl.messages

import android.os.Bundle
import android.view.Gravity
import android.widget.*

/** Manage custom spam keyword/sender rules and blocked senders. */
class SpamRulesActivity : SahlActivity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(getString(R.string.spam_rules), true))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(8), px(16), px(30)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun render() {
        body.removeAllViews()
        body.addView(label(getString(R.string.spam_rules)))

        val addCard = card()
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val et = EditText(this).apply {
            hint = getString(R.string.spam_rule_hint); typeface = font; textSize = 15f
            setTextColor(col(R.color.text)); setHintTextColor(col(R.color.sub))
            background = null; isSingleLine = true
        }
        row.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(getString(R.string.add), 14f, 0xFFFFFFFF.toInt(), true).apply {
            background = round(col(R.color.emerald), 14f)
            setPadding(px(14), px(8), px(14), px(8))
            setOnClickListener {
                val r = et.text.toString().trim()
                if (r.isNotEmpty()) {
                    Spam.addRule(this@SpamRulesActivity, r)
                    render()
                }
            }
        }.press(14f))
        addCard.addView(row)
        body.addView(addCard, LinearLayout.LayoutParams(-1, -2))

        val rules = Spam.rules(this)
        if (rules.isEmpty()) {
            body.addView(text(getString(R.string.no_spam_rules), 13f, col(R.color.sub)).apply { setPadding(px(6), px(10), px(6), px(10)) })
        } else {
            val listCard = card()
            rules.forEachIndexed { idx, r ->
                val item = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6), 0, px(6)) }
                item.addView(text(r, 15f, col(R.color.text)).apply { }, LinearLayout.LayoutParams(0, -2, 1f))
                item.addView(iconButton(R.drawable.ic_close, 0, col(R.color.danger), 36, 18) {
                    Spam.removeRule(this, r); render()
                })
                listCard.addView(item)
                if (idx < rules.lastIndex) listCard.addView(android.view.View(this).apply { setBackgroundColor(col(R.color.line)) },
                    LinearLayout.LayoutParams(-1, 1))
            }
            body.addView(listCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        }

        body.addView(label(getString(R.string.blocked_senders_title)).apply { setPaddingRelative(px(4), px(18), 0, px(8)) })
        val blocked = Spam.blockedSenders(this).sorted()
        if (blocked.isEmpty()) {
            body.addView(text(getString(R.string.no_blocked_senders), 13f, col(R.color.sub)).apply { setPadding(px(6), px(4), px(6), px(10)) })
        } else {
            val bCard = card()
            blocked.forEachIndexed { idx, key ->
                val item = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6), 0, px(6)) }
                item.addView(text(key, 15f, col(R.color.text)), LinearLayout.LayoutParams(0, -2, 1f))
                item.addView(text(getString(R.string.unblock), 13f, col(R.color.emerald), true).apply {
                    background = round(col(R.color.tonal), 12f)
                    setPadding(px(12), px(6), px(12), px(6))
                    setOnClickListener { Spam.unblockSender(this@SpamRulesActivity, key); render() }
                }.press(12f))
                bCard.addView(item)
                if (idx < blocked.lastIndex) bCard.addView(android.view.View(this).apply { setBackgroundColor(col(R.color.line)) },
                    LinearLayout.LayoutParams(-1, 1))
            }
            body.addView(bCard, LinearLayout.LayoutParams(-1, -2))
        }
    }
}
