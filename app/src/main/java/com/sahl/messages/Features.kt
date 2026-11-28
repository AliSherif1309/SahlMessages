package com.sahl.messages

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import org.json.JSONArray
import org.json.JSONObject

/** Built-in spam folder, sender blocking, custom keyword rules & Sahl Tools Call Guard sync. */
object Spam {
    private fun set(c: Context, k: String): Set<String> = Sahl.prefs(c).getStringSet(k, null)?.toSet() ?: emptySet()

    fun blockedSenders(c: Context): Set<String> = set(c, "blocked_senders")
    fun spamThreads(c: Context): Set<String> = set(c, "spam_threads")

    fun rules(c: Context): List<String> = try {
        val a = JSONArray(Sahl.prefs(c).getString("spam_rules", "[]"))
        (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
    } catch (_: Exception) { emptyList() }

    private fun saveRules(c: Context, list: List<String>) {
        Sahl.prefs(c).edit().putString("spam_rules", JSONArray(list).toString()).apply()
    }

    fun addRule(c: Context, rule: String) {
        val r = rule.trim()
        if (r.isEmpty()) return
        val cur = rules(c).toMutableList()
        if (cur.none { Norm.text(it) == Norm.text(r) }) {
            cur.add(r)
            saveRules(c, cur)
        }
    }

    fun removeRule(c: Context, rule: String) {
        saveRules(c, rules(c).filter { it != rule })
    }

    private fun senderKey(addr: String): String {
        val a = addr.trim()
        if (a.isEmpty()) return ""
        return if (a.any { it.isLetter() }) Norm.text(a) else Norm.phoneKey(a)
    }

    fun isBlockedSender(c: Context, address: String): Boolean {
        val k = senderKey(address)
        return k.isNotEmpty() && k in blockedSenders(c)
    }

    fun isSpamThread(c: Context, threadId: Long): Boolean = threadId.toString() in spamThreads(c)

    fun matchesRule(c: Context, address: String, body: String): Boolean {
        val rs = rules(c)
        if (rs.isEmpty()) return false
        val na = Norm.text(address)
        val nb = Norm.text(body)
        return rs.any { r ->
            val nr = Norm.text(r)
            nr.isNotEmpty() && (na.contains(nr) || nb.contains(nr))
        }
    }

    fun isSpam(c: Context, threadId: Long, address: String, body: String): Boolean =
        isSpamThread(c, threadId) || isBlockedSender(c, address) || matchesRule(c, address, body) || Guard.blocked(c, address)

    fun block(c: Context, threadId: Long, address: String, syncGuard: Boolean = true) {
        val sp = spamThreads(c).toMutableSet().apply { add(threadId.toString()) }
        val bs = blockedSenders(c).toMutableSet()
        val k = senderKey(address)
        if (k.isNotEmpty()) bs.add(k)
        Sahl.prefs(c).edit().putStringSet("spam_threads", sp).putStringSet("blocked_senders", bs).apply()
        if (syncGuard && address.isNotBlank()) {
            try {
                c.contentResolver.insert(
                    Uri.parse("content://com.sahl.tools.union/guard/block"),
                    ContentValues().apply { put("number", address) }
                )
            } catch (_: Exception) { }
        }
    }

    fun unblock(c: Context, threadId: Long, address: String, syncGuard: Boolean = true) {
        val sp = spamThreads(c).toMutableSet().apply { remove(threadId.toString()) }
        val bs = blockedSenders(c).toMutableSet()
        val k = senderKey(address)
        if (k.isNotEmpty()) bs.remove(k)
        Sahl.prefs(c).edit().putStringSet("spam_threads", sp).putStringSet("blocked_senders", bs).apply()
        if (syncGuard && address.isNotBlank()) {
            try {
                c.contentResolver.insert(
                    Uri.parse("content://com.sahl.tools.union/guard/allow"),
                    ContentValues().apply { put("number", address) }
                )
            } catch (_: Exception) { }
        }
    }

    fun unblockSender(c: Context, key: String) {
        val bs = blockedSenders(c).toMutableSet().apply { remove(key) }
        Sahl.prefs(c).edit().putStringSet("blocked_senders", bs).apply()
    }
}

/** Auto-saved unsent message drafts per thread or phone number. */
object Drafts {
    private fun obj(c: Context): JSONObject = try {
        JSONObject(Sahl.prefs(c).getString("drafts", "{}"))
    } catch (_: Exception) { JSONObject() }

    private fun key(threadId: Long, address: String): String =
        if (threadId > 0L) "t:$threadId" else "a:${Norm.phoneKey(address)}"

    fun get(c: Context, threadId: Long, address: String): String {
        val o = obj(c)
        if (threadId > 0L) o.optString("t:$threadId", "").takeIf { it.isNotEmpty() }?.let { return it }
        val ak = "a:${Norm.phoneKey(address)}"
        return if (address.isNotBlank()) o.optString(ak, "") else ""
    }

    fun save(c: Context, threadId: Long, address: String, text: String) {
        val o = obj(c)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            o.remove("t:$threadId")
            if (address.isNotBlank()) o.remove("a:${Norm.phoneKey(address)}")
        } else {
            o.put(key(threadId, address), text)
        }
        Sahl.prefs(c).edit().putString("drafts", o.toString()).apply()
    }

    fun clear(c: Context, threadId: Long, address: String) = save(c, threadId, address, "")
}

/** One-tap reusable text snippets (National Address, IBAN, Email, standard replies). */
object Snippets {
    data class Snippet(val id: Long, val title: String, val body: String)

    fun list(c: Context): List<Snippet> = try {
        val a = JSONArray(Sahl.prefs(c).getString("snippets", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
            .map { Snippet(it.getLong("id"), it.getString("t"), it.getString("b")) }
    } catch (_: Exception) { emptyList() }

    private fun save(c: Context, items: List<Snippet>) {
        val arr = JSONArray()
        for (s in items) arr.put(JSONObject().put("id", s.id).put("t", s.title).put("b", s.body))
        Sahl.prefs(c).edit().putString("snippets", arr.toString()).apply()
    }

    fun add(c: Context, title: String, body: String) {
        val t = title.trim(); val b = body.trim()
        if (t.isEmpty() || b.isEmpty()) return
        save(c, list(c) + Snippet(System.currentTimeMillis(), t, b))
    }

    fun update(c: Context, id: Long, title: String, body: String) {
        val t = title.trim(); val b = body.trim()
        if (t.isEmpty() || b.isEmpty()) return
        save(c, list(c).map { if (it.id == id) it.copy(title = t, body = b) else it })
    }

    fun delete(c: Context, id: Long) {
        save(c, list(c).filter { it.id != id })
    }
}

/** Archive finished conversations without deleting them; auto-unarchives on new reply. */
object Archive {
    fun all(c: Context): Set<String> = Sahl.prefs(c).getStringSet("archived_threads", null)?.toSet() ?: emptySet()
    fun isArchived(c: Context, threadId: Long): Boolean = threadId.toString() in all(c)

    fun setArchived(c: Context, threads: Collection<Long>, on: Boolean) {
        val s = all(c).toMutableSet()
        for (id in threads) if (on) s.add(id.toString()) else s.remove(id.toString())
        Sahl.prefs(c).edit().putStringSet("archived_threads", s).apply()
    }

    fun unarchive(c: Context, threadId: Long) {
        if (isArchived(c, threadId)) setArchived(c, listOf(threadId), false)
    }
}

/** Biometric / screen-lock protected chats, optionally aligned with SahlContacts Vault. */
object ChatVault {
    private var unlockedUntil = 0L

    fun lockedThreads(c: Context): Set<String> = Sahl.prefs(c).getStringSet("locked_threads", null)?.toSet() ?: emptySet()
    fun alignContactsVault(c: Context): Boolean = Sahl.prefs(c).getBoolean("align_contacts_vault", true)

    fun setLocked(c: Context, threads: Collection<Long>, on: Boolean) {
        val s = lockedThreads(c).toMutableSet()
        for (id in threads) if (on) s.add(id.toString()) else s.remove(id.toString())
        Sahl.prefs(c).edit().putStringSet("locked_threads", s).apply()
    }

    fun isLockedThread(c: Context, threadId: Long): Boolean = threadId.toString() in lockedThreads(c)

    fun contactsVaultLookups(c: Context): Set<String> {
        if (!alignContactsVault(c)) return emptySet()
        val out = HashSet<String>()
        try {
            c.contentResolver.query(Uri.parse(Sahl.CONTACTS_UNION + "/hidden"), null, null, null, null)?.use {
                while (it.moveToNext()) it.getString(0)?.takeIf { k -> k.isNotEmpty() }?.let { k -> out.add(k) }
            }
        } catch (_: Exception) { }
        return out
    }

    fun isLocked(c: Context, threadId: Long, contactLookup: String?, hiddenLookups: Set<String>): Boolean =
        isLockedThread(c, threadId) || (!contactLookup.isNullOrEmpty() && contactLookup in hiddenLookups)

    fun unlocked(): Boolean = System.currentTimeMillis() < unlockedUntil
    fun lock() { unlockedUntil = 0L }

    fun unlock(a: Activity, ok: () -> Unit) {
        val grant = { unlockedUntil = System.currentTimeMillis() + 5 * 60_000L; ok() }
        if (unlocked()) { grant(); return }
        if (Build.VERSION.SDK_INT < 28) { grant(); return }
        val b = BiometricPrompt.Builder(a).setTitle(a.getString(R.string.unlock_locked_chats))
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else if (Build.VERSION.SDK_INT == 29) {
            @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        } else {
            b.setNegativeButton(a.getString(R.string.cancel), a.mainExecutor) { _, _ -> }
        }
        try {
            b.build().authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { grant() }
            })
        } catch (_: Exception) {
            grant()
        }
    }
}
