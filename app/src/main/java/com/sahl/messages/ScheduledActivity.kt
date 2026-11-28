package com.sahl.messages

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar

/** Central manager for all upcoming scheduled messages: view, edit, reschedule, or cancel. */
class ScheduledActivity : SahlActivity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(getString(R.string.scheduled_messages), true))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14), px(4), px(14), px(30)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        body.removeAllViews()
        val items = Scheduled.all(this).sortedBy { it.at }
        if (items.isEmpty()) {
            body.addView(icon(R.drawable.ic_history, col(R.color.line), 64).apply {
                (layoutParams as LinearLayout.LayoutParams).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = px(80) }
            })
            body.addView(text(getString(R.string.no_scheduled), 15f, col(R.color.sub)).apply {
                gravity = Gravity.CENTER; setPadding(0, px(12), 0, 0)
            })
            return
        }
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", resources.configuration.locales[0])
        for (item in items) {
            val c = card()
            val name = Names.of(this, item.address).first ?: item.address
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(icon(R.drawable.ic_history, col(R.color.emerald), 16))
            top.addView(text(name, 15f, col(R.color.text), true).apply {
                setPaddingRelative(px(8), 0, 0, 0); maxLines = 1
            }, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(text(Instant.ofEpochMilli(item.at).atZone(ZoneId.systemDefault()).format(fmt), 12f, col(R.color.emerald), true))
            c.addView(top)
            c.addView(text(item.body, 15f).apply { setPadding(0, px(8), 0, px(10)) })

            val actions = LinearLayout(this).apply { gravity = Gravity.END }
            fun btn(label: Int, color: Int, go: () -> Unit) = actions.addView(text(getString(label), 13f, color, true).apply {
                background = round(col(R.color.tonal), 12f)
                setPadding(px(12), px(6), px(12), px(6))
                setOnClickListener { go() }
            }.press(12f), LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8) })

            btn(R.string.edit, col(R.color.text)) { editItem(item) }
            btn(R.string.reschedule, col(R.color.emerald)) { rescheduleItem(item) }
            btn(R.string.cancel, col(R.color.danger)) {
                Scheduled.cancel(this, item.id)
                build()
            }
            c.addView(actions)
            body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        }
    }

    private fun editItem(item: Scheduled.Item) {
        val et = EditText(this).apply {
            setText(item.body); setSelection(item.body.length)
            typeface = font; textSize = 16f; setTextColor(col(R.color.text))
            setPadding(px(18), px(14), px(18), px(14))
        }
        AlertDialog.Builder(this).setTitle(R.string.edit).setView(et)
            .setPositiveButton(R.string.save) { _, _ ->
                val txt = et.text.toString().trim()
                if (txt.isNotEmpty()) {
                    Scheduled.update(this, item.id, txt, item.at)
                    build()
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun rescheduleItem(item: Scheduled.Item) {
        val cal = Calendar.getInstance().apply { timeInMillis = maxOf(item.at, System.currentTimeMillis()) }
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                val target = Calendar.getInstance().apply { set(y, m, d, h, min, 0) }
                if (target.timeInMillis <= System.currentTimeMillis()) {
                    toast(R.string.pick_future)
                    return@TimePickerDialog
                }
                Scheduled.update(this, item.id, item.body, target.timeInMillis)
                toast(R.string.scheduled_ok)
                build()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }
}
