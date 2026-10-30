package com.depthwall.clocktransparency

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.io.File

class MainActivity : AppCompatActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var sliderOpacity: Slider
    private lateinit var textOpacityBadge: TextView
    private lateinit var switchIncludeDate: MaterialSwitch
    private lateinit var switchCustomFont: MaterialSwitch
    private lateinit var textSelectedFont: TextView
    private lateinit var switchFontApplyDate: MaterialSwitch
    private lateinit var rowResetFont: View
    private lateinit var dividerResetFont: View

    private val fontPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleSelectedFont(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Handle edge-to-edge system insets
        val scrollView = findViewById<View>(R.id.main_scroll_view)
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                top = systemBars.top,
                bottom = systemBars.bottom
            )
            insets
        }

        initViews()
        loadPreferences()
        setupListeners()
        makePrefsReadable()
    }

    override fun onResume() {
        super.onResume()
        loadPreferences()
        makePrefsReadable()
    }

    private fun initViews() {
        switchEnabled = findViewById(R.id.switch_enabled)
        sliderOpacity = findViewById(R.id.slider_opacity)
        textOpacityBadge = findViewById(R.id.text_opacity_badge)
        switchIncludeDate = findViewById(R.id.switch_include_date)

        switchCustomFont = findViewById(R.id.switch_custom_font)
        textSelectedFont = findViewById(R.id.text_selected_font)
        switchFontApplyDate = findViewById(R.id.switch_font_apply_date)
        rowResetFont = findViewById(R.id.row_reset_font)
        dividerResetFont = findViewById(R.id.divider_reset_font)
    }

    private fun loadPreferences() {
        val isEnabled = prefs.getBoolean("pref_enabled", true)
        switchEnabled.isChecked = isEnabled

        val opacity = prefs.getInt("pref_clock_opacity", 50)
        sliderOpacity.value = opacity.toFloat().coerceIn(0f, 100f)
        textOpacityBadge.text = "$opacity%"

        switchIncludeDate.isChecked = prefs.getBoolean("pref_include_date", true)

        val isCustomFont = prefs.getBoolean("pref_enable_custom_font", false)
        switchCustomFont.isChecked = isCustomFont

        val fontName = prefs.getString("pref_custom_font_name", null)
        val fontFile = File(filesDir, "custom_clock_font.ttf")

        // Auto-encode existing font file to Base64 in preferences if missing
        if (fontFile.exists() && prefs.getString("pref_custom_font_base64", null).isNullOrEmpty()) {
            runCatching {
                val bytes = fontFile.readBytes()
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                prefs.edit().putString("pref_custom_font_base64", b64).apply()
            }
        }

        if (!fontName.isNullOrEmpty()) {
            textSelectedFont.text = "Selected: $fontName"
            rowResetFont.visibility = View.VISIBLE
            dividerResetFont.visibility = View.VISIBLE
        } else {
            textSelectedFont.text = "Tap to choose .ttf or .otf file"
            rowResetFont.visibility = View.GONE
            dividerResetFont.visibility = View.GONE
        }

        switchFontApplyDate.isChecked = prefs.getBoolean("pref_font_apply_to_date", false)
    }

    private fun setupListeners() {
        // Master switch
        findViewById<View>(R.id.row_master_switch).setOnClickListener {
            val newState = !switchEnabled.isChecked
            switchEnabled.isChecked = newState
            prefs.edit().putBoolean("pref_enabled", newState).apply()
            makePrefsReadable()
            notifySettingsChanged()
        }

        // Opacity slider
        sliderOpacity.addOnChangeListener { _, value, fromUser ->
            val intVal = value.toInt()
            textOpacityBadge.text = "$intVal%"
            prefs.edit().putInt("pref_clock_opacity", intVal).apply()
            makePrefsReadable()
            if (fromUser) {
                notifySettingsChanged()
            }
        }

        // Include date switch
        findViewById<View>(R.id.row_include_date).setOnClickListener {
            val newState = !switchIncludeDate.isChecked
            switchIncludeDate.isChecked = newState
            prefs.edit().putBoolean("pref_include_date", newState).apply()
            makePrefsReadable()
            notifySettingsChanged()
        }

        // Custom font switch
        findViewById<View>(R.id.row_custom_font).setOnClickListener {
            val newState = !switchCustomFont.isChecked
            switchCustomFont.isChecked = newState
            prefs.edit().putBoolean("pref_enable_custom_font", newState).apply()
            makePrefsReadable()
            notifySettingsChanged()
        }

        // Select font
        findViewById<View>(R.id.row_select_font).setOnClickListener {
            fontPickerLauncher.launch(arrayOf("*/*"))
        }

        // Font apply to date switch
        findViewById<View>(R.id.row_font_apply_date).setOnClickListener {
            val newState = !switchFontApplyDate.isChecked
            switchFontApplyDate.isChecked = newState
            prefs.edit().putBoolean("pref_font_apply_to_date", newState).apply()
            makePrefsReadable()
            notifySettingsChanged()
        }

        // Reset font
        rowResetFont.setOnClickListener {
            resetCustomFont()
        }

        // Restart SystemUI
        findViewById<View>(R.id.row_restart_systemui).setOnClickListener {
            restartSystemUI()
        }
    }

    private fun notifySettingsChanged() {
        runCatching {
            val intent = Intent(PreferencesHelper.ACTION_SETTINGS_CHANGED)
            sendBroadcast(intent)
            sendBroadcast(Intent(intent).setPackage("com.android.systemui"))
            sendBroadcast(Intent(intent).setPackage("com.motorola.deptheffect"))
            sendBroadcast(Intent(intent).setPackage("com.motorola.personalize"))
        }
    }

    private fun restartSystemUI() {
        Toast.makeText(this, "Restarting SystemUI...", Toast.LENGTH_SHORT).show()
        Thread {
            runCatching {
                makePrefsReadable()
                val cmd = "cp /data/user/0/com.depthwall.clocktransparency/shared_prefs/clock_transparency_prefs.xml /data/misc/6bfb0440-adf0-4889-94f5-311346c9b822/prefs/com.depthwall.clocktransparency/clock_transparency_prefs.xml 2>/dev/null; pkill -f com.android.systemui; pkill -9 -f com.motorola.deptheffect; kill -9 \$(pidof com.motorola.deptheffect:live_wallpaper) 2>/dev/null"
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    runOnUiThread {
                        Toast.makeText(this, "Root permission required to restart SystemUI", Toast.LENGTH_LONG).show()
                    }
                }
            }.onFailure {
                runOnUiThread {
                    Toast.makeText(this, "Failed to execute root command: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun handleSelectedFont(uri: Uri) {
        var displayName = "custom_clock_font.ttf"

        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx != -1 && cursor.moveToFirst()) {
                    cursor.getString(nameIdx)?.let { displayName = it }
                }
            }
        }

        val destFile = File(filesDir, "custom_clock_font.ttf")

        val copySuccess = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        }.getOrDefault(false)

        if (!copySuccess || !destFile.exists()) {
            Toast.makeText(this, getString(R.string.toast_font_invalid), Toast.LENGTH_LONG).show()
            return
        }

        // Verify valid typeface
        val isValid = runCatching {
            val tf = Typeface.createFromFile(destFile)
            tf != null
        }.getOrDefault(false)

        if (!isValid) {
            destFile.delete()
            Toast.makeText(this, getString(R.string.toast_font_invalid), Toast.LENGTH_LONG).show()
            return
        }

        // Make world readable
        makePrefsReadable()

        // Universal fallback to /data/local/tmp if root available
        runCatching {
            val cmd = "cp ${destFile.absolutePath} /data/local/tmp/custom_clock_font.ttf && chmod 644 /data/local/tmp/custom_clock_font.ttf"
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        }

        // Encode font to Base64 for zero-SELinux-dependency IPC
        val fontBytes = runCatching { destFile.readBytes() }.getOrNull()
        val base64Font = fontBytes?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }

        // Save preferences
        prefs.edit()
            .putString("pref_custom_font_name", displayName)
            .putString("pref_custom_font_path", destFile.absolutePath)
            .putString("pref_custom_font_base64", base64Font)
            .putLong("pref_custom_font_last_modified", destFile.lastModified())
            .putBoolean("pref_enable_custom_font", true)
            .apply()

        switchCustomFont.isChecked = true
        textSelectedFont.text = "Selected: $displayName"
        rowResetFont.visibility = View.VISIBLE
        dividerResetFont.visibility = View.VISIBLE

        notifySettingsChanged()
        Toast.makeText(this, getString(R.string.toast_font_saved, displayName), Toast.LENGTH_SHORT).show()
    }

    private fun resetCustomFont() {
        val destFile = File(filesDir, "custom_clock_font.ttf")
        if (destFile.exists()) {
            destFile.delete()
        }

        runCatching {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -f /data/local/tmp/custom_clock_font.ttf"))
        }

        prefs.edit()
            .remove("pref_custom_font_name")
            .remove("pref_custom_font_path")
            .remove("pref_custom_font_base64")
            .remove("pref_custom_font_last_modified")
            .putBoolean("pref_enable_custom_font", false)
            .apply()

        switchCustomFont.isChecked = false
        textSelectedFont.text = "Tap to choose .ttf or .otf file"
        rowResetFont.visibility = View.GONE
        dividerResetFont.visibility = View.GONE

        makePrefsReadable()
        notifySettingsChanged()
        Toast.makeText(this, getString(R.string.toast_font_reset), Toast.LENGTH_SHORT).show()
    }

    private fun makePrefsReadable() {
        runCatching {
            val dataDir = File(applicationInfo.dataDir)
            val prefsDir = File(dataDir, "shared_prefs")
            val prefsFile = File(prefsDir, "$PREFS_NAME.xml")
            val fontFile = File(filesDir, "custom_clock_font.ttf")

            dataDir.setReadable(true, false)
            dataDir.setExecutable(true, false)

            prefsDir.setReadable(true, false)
            prefsDir.setExecutable(true, false)

            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
            }

            filesDir.setReadable(true, false)
            filesDir.setExecutable(true, false)

            if (fontFile.exists()) {
                fontFile.setReadable(true, false)
            }
        }
    }

    companion object {
        const val PREFS_NAME = "clock_transparency_prefs"
    }
}
