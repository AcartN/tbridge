package fr.acart.tbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

internal class MainActivity : AppCompatActivity() {

    private lateinit var statusLabel: TextView
    private lateinit var tokenValue: TextView
    private lateinit var listenSwitch: MaterialSwitch
    private lateinit var shellOnlySwitch: MaterialSwitch
    private lateinit var settings: TBridgeSettings

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            TBridgeSettings.KEY_TOKEN -> refreshToken()
            TBridgeSettings.KEY_LISTEN_ENABLED -> refreshListenSwitch()
            TBridgeSettings.KEY_SHELL_ONLY -> refreshShellOnlySwitch()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = TBridgeSettings(this)

        statusLabel = findViewById(R.id.statusLabel)
        tokenValue = findViewById(R.id.tokenValue)
        listenSwitch = findViewById(R.id.listenSwitch)
        shellOnlySwitch = findViewById(R.id.shellOnlySwitch)

        findViewById<MaterialButton>(R.id.openSettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        listenSwitch.setOnCheckedChangeListener { _, checked ->
            settings.listenEnabled = checked
        }
        shellOnlySwitch.setOnCheckedChangeListener { _, checked ->
            settings.shellOnly = checked
        }

        findViewById<MaterialButton>(R.id.copyTokenButton).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("tbridge token", settings.token))
            Toast.makeText(this, R.string.toast_token_copied, Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.regenerateTokenButton).setOnClickListener {
            settings.regenerateToken()
            Toast.makeText(this, R.string.toast_token_regenerated, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
        refreshListenSwitch()
        refreshShellOnlySwitch()
        refreshToken()
        settings.registerListener(prefsListener)
    }

    override fun onPause() {
        settings.unregisterListener(prefsListener)
        super.onPause()
    }

    private fun refreshShellOnlySwitch() {
        shellOnlySwitch.isChecked = settings.shellOnly
    }

    private fun refreshServiceStatus() {
        statusLabel.setText(
            if (isAccessibilityServiceEnabled()) R.string.status_enabled else R.string.status_disabled
        )
    }

    private fun refreshListenSwitch() {
        listenSwitch.isChecked = settings.listenEnabled
    }

    private fun refreshToken() {
        tokenValue.text = settings.token
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, TalkBackGestureService::class.java).flattenToString()
        val raw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return raw.split(":").any { it.equals(expected, ignoreCase = true) }
    }
}
