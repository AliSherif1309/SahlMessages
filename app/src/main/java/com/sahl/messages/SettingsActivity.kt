package com.sahl.messages

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.*

class SettingsActivity : SahlActivity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(header(getString(R.string.settings), true))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(8), px(16), px(32)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        val p = Sahl.prefs(this)

        choice(R.string.language, listOf("system" to getString(R.string.system), "ar" to "العربية", "en" to "English"), "lang")
        choice(R.string.theme, listOf("system" to getString(R.string.system), "light" to getString(R.string.light), "dark" to getString(R.string.dark)), "theme")

        val dcard = card(R.string.default_sms)
        dcard.addView(text(getString(if (Sms.isDefault(this)) R.string.is_default else R.string.make_default), 14f,
            if (Sms.isDefault(this)) col(R.color.emerald) else col(R.color.sub)))

        val me = card(R.string.my_numbers)
        me.addView(text(getString(R.string.my_numbers_hint), 12f, col(R.color.sub)))
        me.addView(EditText(this).apply {
            setText(p.getString("my_numbers", "")); inputType = android.text.InputType.TYPE_CLASS_PHONE
            textSize = 16f; typeface = font; setTextColor(col(R.color.text)); hint = "55xxxxxx, 33xxxxxx"
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { p.edit().putString("my_numbers", s?.toString() ?: "").apply() }
            })
        })

        val sm = card(R.string.smart_title)
        fun sw(label: Int, key: String, def: Boolean) = sm.addView(Switch(this).apply {
            text = getString(label); typeface = font; setTextColor(col(R.color.text)); textSize = 15f
            isChecked = p.getBoolean(key, def); setPadding(0, px(6), 0, px(6))
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean(key, on).apply() }
        })
        sw(R.string.set_hide, "hide_previews", false)
        sw(R.string.set_undo, "undo_send", true)
        sw(R.string.set_delivery, "delivery", true)
        sw(R.string.set_clean, "clean_codes", false)
        sw(R.string.set_prayer_silence, "prayer_silence", false)

        val bk = card(R.string.backup)
        bk.addView(text(getString(R.string.backup_hint), 12f, col(R.color.sub)))
        bk.addView(Button(this).apply { text = getString(R.string.backup_export); typeface = font
            setOnClickListener { try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "SahlMessages-" + java.time.LocalDate.now() + ".json"), 60) } catch (e: Exception) { } } })
        bk.addView(Button(this).apply { text = getString(R.string.backup_import); typeface = font
            setOnClickListener { try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 61) } catch (e: Exception) { } } })

        val up = card(R.string.check_updates)
        up.addView(text(getString(R.string.version, BuildConfig.VERSION_NAME), 14f, col(R.color.sub)))
        up.addView(Button(this).apply {
            text = getString(R.string.check_updates); typeface = font
            setOnClickListener { checkUpdates(this) }
        })

        val un = card(R.string.union)
        un.addView(text(getString(R.string.union_desc), 14f, col(R.color.sub)))
        un.addView(SahlUnionApps.strip(this))
    }

    @Deprecated("Activity result")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (res != RESULT_OK) return
        if (req == 61 && !Sms.isDefault(this)) { toast(R.string.make_default); return }
        Thread {
            val n = try {
                if (req == 60) contentResolver.openOutputStream(uri)?.use { Backup.export(this, it) } ?: 0
                else contentResolver.openInputStream(uri)?.use { Backup.import(this, it) } ?: 0
            } catch (e: Exception) { -1 }
            runOnUiThread {
                Toast.makeText(this, if (n < 0) getString(R.string.backup_failed)
                    else getString(if (req == 60) R.string.backup_done else R.string.restore_done, n), Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun card(title: Int): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = round(col(R.color.card), 22f)
            setPadding(px(16), px(12), px(16), px(12))
        }
        c.addView(text(getString(title), 13f, col(R.color.emerald), true))
        body.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        return c
    }

    private fun choice(title: Int, options: List<Pair<String, String>>, key: String) {
        val c = card(title)
        val p = Sahl.prefs(this)
        val cur = p.getString(key, "system")
        val group = RadioGroup(this)
        options.forEachIndexed { i, (value, label) ->
            group.addView(RadioButton(this).apply {
                id = 1000 + i; text = label; typeface = font; setTextColor(col(R.color.text))
                isChecked = value == cur
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val v = options[checkedId - 1000].first
            if (v == p.getString(key, "system")) return@setOnCheckedChangeListener
            p.edit().putString(key, v).commit()
            // Restart so the new language / theme applies everywhere
            finishAffinity()
            startActivity(Intent(this, MainActivity::class.java))
        }
        c.addView(group)
    }

    private fun checkUpdates(btn: Button) {
        btn.isEnabled = false; btn.text = getString(R.string.checking)
        Thread {
            val r = Updater.fetchNewer()
            runOnUiThread {
                btn.isEnabled = true; btn.text = getString(R.string.check_updates)
                if (r == null) { toast(R.string.up_to_date); return@runOnUiThread }
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.update_available, r.name))
                    .setMessage(r.notes)
                    .setPositiveButton(R.string.install) { _, _ -> Updater.downloadAndInstall(this, r) }
                    .setNegativeButton(R.string.later, null).show()
            }
        }.start()
    }
}
