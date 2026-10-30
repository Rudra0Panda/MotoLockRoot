package com.depthwall.clocktransparency

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import de.robv.android.xposed.XSharedPreferences
import java.io.File

object PreferencesHelper {
    private const val TAG = "LockscreenClockTransparency"
    const val AUTHORITY = "com.depthwall.clocktransparency.provider"
    val PROVIDER_URI: Uri = Uri.parse("content://$AUTHORITY")
    const val ACTION_SETTINGS_CHANGED = "com.depthwall.clocktransparency.ACTION_SETTINGS_CHANGED"

    @Volatile var isEnabled: Boolean = true
        private set
    @Volatile var clockAlpha: Float = 0.5f
        private set
    @Volatile var includeDate: Boolean = true
        private set
    @Volatile var isCustomFontEnabled: Boolean = false
        private set
    @Volatile var customFontApplyToDate: Boolean = false
        private set
    @Volatile var customFontName: String? = null
        private set
    @Volatile var fontLastModified: Long = 0L
        private set

    @Volatile var customTypeface: Typeface? = null
        private set

    private var appContext: Context? = null

    fun setContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext ?: context
        }
    }

    @Synchronized
    fun reload(context: Context? = null) {
        val ctx = context ?: appContext
        if (ctx != null) {
            setContext(ctx)
            val success = reloadFromProvider(ctx)
            if (success) return
        }

        // Fallback: XSharedPreferences
        reloadFromXPrefs()
    }

    private fun reloadFromProvider(ctx: Context): Boolean {
        return runCatching {
            val bundle = ctx.contentResolver.call(
                PROVIDER_URI,
                PreferencesProvider.METHOD_GET_PREFS,
                null,
                null
            ) ?: return false

            isEnabled = bundle.getBoolean("enabled", true)
            clockAlpha = bundle.getFloat("alpha", 0.5f)
            includeDate = bundle.getBoolean("include_date", true)
            isCustomFontEnabled = bundle.getBoolean("custom_font_enabled", false)
            customFontApplyToDate = bundle.getBoolean("custom_font_apply_to_date", false)
            customFontName = bundle.getString("custom_font_name")
            val newFontMod = bundle.getLong("font_last_modified", 0L)

            if (isCustomFontEnabled) {
                // If font modified or not yet loaded, load it
                if (customTypeface == null || fontLastModified != newFontMod) {
                    var loadedTf: Typeface? = null

                    // 1. Try ParcelFileDescriptor
                    runCatching {
                        val fontUri = Uri.parse("content://$AUTHORITY/font")
                        ctx.contentResolver.openFileDescriptor(fontUri, "r")?.use { pfd ->
                            loadedTf = Typeface.Builder(pfd.fileDescriptor).build()
                        }
                    }

                    // 2. Fallback to byte array via cache file in target process sandbox
                    if (loadedTf == null) {
                        val fontBytes = bundle.getByteArray("font_bytes")
                        if (fontBytes != null && fontBytes.isNotEmpty()) {
                            runCatching {
                                val cacheDir = ctx.cacheDir ?: File("/data/local/tmp")
                                val tempFile = File.createTempFile("active_clock_font", ".ttf", cacheDir)
                                try {
                                    tempFile.writeBytes(fontBytes)
                                    loadedTf = Typeface.createFromFile(tempFile)
                                } finally {
                                    tempFile.delete()
                                }
                            }
                        }
                    }

                    if (loadedTf != null) {
                        customTypeface = loadedTf
                        fontLastModified = newFontMod
                        XposedInit.log("Successfully loaded custom Typeface! font=$customFontName, mod=$newFontMod")
                    } else {
                        XposedInit.log("Failed to create Typeface from provider data")
                    }
                }
            } else {
                customTypeface = null
            }

            XposedInit.log("Provider reload complete: enabled=$isEnabled, alpha=$clockAlpha, customFont=$isCustomFontEnabled, tfLoaded=${customTypeface != null}")
            true
        }.getOrElse {
            XposedInit.log("Provider reload failed: ${it.message}")
            false
        }
    }

    private fun reloadFromXPrefs() {
        runCatching {
            val prefs = XSharedPreferences("com.depthwall.clocktransparency", "clock_transparency_prefs")
            prefs.reload()
            isEnabled = prefs.getBoolean("pref_enabled", isEnabled)
            val opacity = prefs.getInt("pref_clock_opacity", (clockAlpha * 100).toInt())
            clockAlpha = (opacity.coerceIn(0, 100)) / 100.0f
            includeDate = prefs.getBoolean("pref_include_date", includeDate)
            isCustomFontEnabled = prefs.getBoolean("pref_enable_custom_font", isCustomFontEnabled)
            customFontApplyToDate = prefs.getBoolean("pref_font_apply_to_date", customFontApplyToDate)
            XposedInit.log("XPrefs reload: enabled=$isEnabled, alpha=$clockAlpha, customFont=$isCustomFontEnabled")
        }.onFailure {
            XposedInit.log("XPrefs reload failed: ${it.message}")
        }
    }
}
