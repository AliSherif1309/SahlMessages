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
    @Volatile private var blockedCache: Set<String>? = null
    @Volatile private var spamThreadsCache: Set<String>? = null
    @Volatile private var rulesCache: List<String>? = null
    @Volatile private var rulesNormCache: List<String>? = null
    private val guardCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun set(c: Context, k: String): Set<String> = Sahl.prefs(c).getStringSet(k, null)?.toSet() ?: emptySet()

    fun blockedSenders(c: Context): Set<String> = blockedCache ?: set(c, "blocked_senders").also { blockedCache = it }
    fun spamThreads(c: Context): Set<String> = spamThreadsCache ?: set(c, "spam_threads").also { spamThreadsCache = it }

    fun rules(c: Context): List<String> = rulesCache ?: try {
        val a = JSONArray(Sahl.prefs(c).getString("spam_rules", "[]") ?: "[]")
        (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
    } catch (_: Exception) { emptyList() }.also {
        rulesCache = it
        rulesNormCache = it.map { r -> Norm.text(r) }.filter { nr -> nr.isNotEmpty() }
    }

    private fun rulesNorm(c: Context): List<String> {
        rulesNormCache?.let { return it }
        val rs = rules(c)
        return rs.map { Norm.text(it) }.filter { it.isNotEmpty() }.also { rulesNormCache = it }
    }

    private fun saveRules(c: Context, list: List<String>) {
        rulesCache = list
        rulesNormCache = list.map { Norm.text(it) }.filter { it.isNotEmpty() }
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
        val rs = rulesNorm(c)
        if (rs.isEmpty()) return false
        val na = Norm.text(address)
        val nb = Norm.text(body)
        return rs.any { nr -> na.contains(nr) || nb.contains(nr) }
    }

    fun isGuardBlockedCached(address: String): Boolean {
        val k = senderKey(address)
        return k.isNotEmpty() && guardCache[k] == true
    }

    /** Background-thread only: warms Call Guard lookup cache without blocking the UI thread. */
    fun warmGuardCache(c: Context, addresses: Collection<String>) {
        val app = c.applicationContext
        for (addr in addresses) {
            if (addr.isBlank()) continue
            val k = senderKey(addr)
            if (k.isEmpty() || guardCache.containsKey(k)) continue
            guardCache[k] = Guard.blocked(app, addr)
        }
    }

    fun isSpam(c: Context, threadId: Long, address: String, body: String, checkGuard: Boolean = false): Boolean {
        if (isSpamThread(c, threadId) || isBlockedSender(c, address) || matchesRule(c, address, body)) return true
        val k = senderKey(address)
        if (k.isEmpty()) return false
        if (checkGuard) {
            val blocked = Guard.blocked(c, address)
            guardCache[k] = blocked
            return blocked
        }
        return guardCache[k] == true
    }

    fun block(c: Context, threadId: Long, address: String, syncGuard: Boolean = true) {
        val sp = spamThreads(c).toMutableSet().apply { add(threadId.toString()) }
        val bs = blockedSenders(c).toMutableSet()
        val k = senderKey(address)
        if (k.isNotEmpty()) {
            bs.add(k)
            guardCache[k] = true
        }
        spamThreadsCache = sp
        blockedCache = bs
        Sahl.prefs(c).edit().putStringSet("spam_threads", sp).putStringSet("blocked_senders", bs).apply()
        if (syncGuard && address.isNotBlank()) {
            val app = c.applicationContext
            Thread {
                try {
                    app.contentResolver.insert(
                        Uri.parse("content://com.sahl.tools.union/guard/block"),
                        ContentValues().apply { put("number", address) }
                    )
                } catch (_: Exception) { }
            }.start()
        }
    }

    fun unblock(c: Context, threadId: Long, address: String, syncGuard: Boolean = true) {
        val sp = spamThreads(c).toMutableSet().apply { remove(threadId.toString()) }
        val bs = blockedSenders(c).toMutableSet()
        val k = senderKey(address)
        if (k.isNotEmpty()) {
            bs.remove(k)
            guardCache[k] = false
        }
        spamThreadsCache = sp
        blockedCache = bs
        Sahl.prefs(c).edit().putStringSet("spam_threads", sp).putStringSet("blocked_senders", bs).apply()
        if (syncGuard && address.isNotBlank()) {
            val app = c.applicationContext
            Thread {
                try {
                    app.contentResolver.insert(
                        Uri.parse("content://com.sahl.tools.union/guard/allow"),
                        ContentValues().apply { put("number", address) }
                    )
                } catch (_: Exception) { }
            }.start()
        }
    }

    fun unblockSender(c: Context, key: String) {
        val bs = blockedSenders(c).toMutableSet().apply { remove(key) }
        blockedCache = bs
        guardCache[key] = false
        Sahl.prefs(c).edit().putStringSet("blocked_senders", bs).apply()
    }
}

/** Auto-saved unsent message drafts per thread or phone number. */
object Drafts {
    @Volatile private var cache: MutableMap<String, String>? = null

    private fun map(c: Context): MutableMap<String, String> {
        cache?.let { return it }
        val m = HashMap<String, String>()
        try {
            val o = JSONObject(Sahl.prefs(c).getString("drafts", "{}") ?: "{}")
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = o.optString(k, "")
                if (v.isNotEmpty()) m[k] = v
            }
        } catch (_: Exception) { }
        cache = m
        return m
    }

    private fun key(threadId: Long, address: String): String =
        if (threadId > 0L) "t:$threadId" else "a:${Norm.phoneKey(address)}"

    fun get(c: Context, threadId: Long, address: String): String {
        val m = map(c)
        if (threadId > 0L) m["t:$threadId"]?.takeIf { it.isNotEmpty() }?.let { return it }
        if (address.isNotBlank()) m["a:${Norm.phoneKey(address)}"]?.let { return it }
        return ""
    }

    fun save(c: Context, threadId: Long, address: String, text: String) {
        val m = map(c)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            m.remove("t:$threadId")
            if (address.isNotBlank()) m.remove("a:${Norm.phoneKey(address)}")
        } else {
            m[key(threadId, address)] = text
        }
        Sahl.prefs(c).edit().putString("drafts", JSONObject(m as Map<*, *>).toString()).apply()
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
    @Volatile private var cache: Set<String>? = null

    fun all(c: Context): Set<String> = cache ?: (Sahl.prefs(c).getStringSet("archived_threads", null)?.toSet() ?: emptySet()).also { cache = it }
    fun isArchived(c: Context, threadId: Long): Boolean = threadId.toString() in all(c)

    fun setArchived(c: Context, threads: Collection<Long>, on: Boolean) {
        val s = all(c).toMutableSet()
        for (id in threads) if (on) s.add(id.toString()) else s.remove(id.toString())
        cache = s
        Sahl.prefs(c).edit().putStringSet("archived_threads", s).apply()
    }

    fun unarchive(c: Context, threadId: Long) {
        if (isArchived(c, threadId)) setArchived(c, listOf(threadId), false)
    }
}

/** Biometric / screen-lock protected chats, optionally aligned with SahlContacts Vault. */
object ChatVault {
    private var unlockedUntil = 0L
    @Volatile private var cache: Set<String>? = null

    fun lockedThreads(c: Context): Set<String> = cache ?: (Sahl.prefs(c).getStringSet("locked_threads", null)?.toSet() ?: emptySet()).also { cache = it }
    fun alignContactsVault(c: Context): Boolean = Sahl.prefs(c).getBoolean("align_contacts_vault", true)

    fun setLocked(c: Context, threads: Collection<Long>, on: Boolean) {
        val s = lockedThreads(c).toMutableSet()
        for (id in threads) if (on) s.add(id.toString()) else s.remove(id.toString())
        cache = s
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
