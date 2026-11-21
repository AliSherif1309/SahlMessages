package com.sahl.messages

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony

/**
 * Sahl Union door of Sahl Messages (only Sahl-signed apps can open it):
 *   content://com.sahl.messages.union/history/<number>?limit=30 -> date, type (1 received, 2 sent), body
 * Sahl Contacts uses it for the people timeline (calls + messages together).
 */
class UnionProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val c = context ?: return null
        if (uri.pathSegments.firstOrNull() != "history") return null
        val out = MatrixCursor(arrayOf("date", "type", "body"))
        val number = uri.pathSegments.getOrNull(1) ?: return out
        val limit = uri.getQueryParameter("limit")?.toIntOrNull()?.coerceIn(1, 200) ?: 30
        return try {
            val thread = Sms.threadFor(c, number)
            if (thread > 0) c.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.BODY),
                "${Telephony.Sms.THREAD_ID}=?", arrayOf(thread.toString()), Telephony.Sms.DATE + " DESC")?.use {
                while (it.moveToNext() && out.count < limit)
                    out.addRow(arrayOf<Any>(it.getLong(0), if (it.getInt(1) == Telephony.Sms.MESSAGE_TYPE_INBOX) 1 else 2, it.getString(2) ?: ""))
            }
            out
        } catch (e: Exception) { out }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
