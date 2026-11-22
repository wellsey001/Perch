package com.wellsey.perch

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Shares Perch's current accent colour and glass tone with Clack, so the keyboard and the home
 * screen look like one system. Read-only, and it exposes nothing but those two values.
 */
class ThemeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val c = context ?: return MatrixCursor(arrayOf("accent", "tone"))
        Prefs.init(c)
        return MatrixCursor(arrayOf("accent", "tone")).apply { addRow(arrayOf<Any>(Prefs.accent, Prefs.glassTone)) }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.com.wellsey.perch.theme"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
