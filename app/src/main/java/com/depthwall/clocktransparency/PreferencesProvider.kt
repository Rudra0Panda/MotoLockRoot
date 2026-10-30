package com.depthwall.clocktransparency

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

class PreferencesProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.depthwall.clocktransparency.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")
        const val METHOD_GET_PREFS = "getPreferences"
        private const val TAG = "ClockPrefsProvider"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_GET_PREFS) {
            val ctx = context ?: return null
            val prefs = ctx.getSharedPreferences("clock_transparency_prefs", Context.MODE_PRIVATE)
            val bundle = Bundle()
            val isEnabled = prefs.getBoolean("pref_enabled", true)
            val opacity = prefs.getInt("pref_clock_opacity", 50)
            val includeDate = prefs.getBoolean("pref_include_date", true)
            val isCustomFont = prefs.getBoolean("pref_enable_custom_font", false)
            val applyToDate = prefs.getBoolean("pref_font_apply_to_date", false)
            val fontName = prefs.getString("pref_custom_font_name", null)

            bundle.putBoolean("enabled", isEnabled)
            bundle.putInt("opacity", opacity)
            bundle.putFloat("alpha", (opacity.coerceIn(0, 100)) / 100.0f)
            bundle.putBoolean("include_date", includeDate)
            bundle.putBoolean("custom_font_enabled", isCustomFont)
            bundle.putBoolean("custom_font_apply_to_date", applyToDate)
            bundle.putString("custom_font_name", fontName)

            val fontFile = File(ctx.filesDir, "custom_clock_font.ttf")
            if (isCustomFont && fontFile.exists()) {
                val lastMod = fontFile.lastModified()
                bundle.putLong("font_last_modified", lastMod)
                runCatching {
                    val bytes = fontFile.readBytes()
                    bundle.putByteArray("font_bytes", bytes)
                }.onFailure {
                    Log.e(TAG, "Error reading font bytes", it)
                }
            }

            return bundle
        }
        return null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val fontFile = File(ctx.filesDir, "custom_clock_font.ttf")
        return if (fontFile.exists()) {
            ParcelFileDescriptor.open(fontFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
