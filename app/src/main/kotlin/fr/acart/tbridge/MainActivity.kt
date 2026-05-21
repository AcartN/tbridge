package fr.acart.tbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Date
import java.util.concurrent.TimeUnit

internal class MainActivity : AppCompatActivity() {

    private lateinit var statusLabel: TextView
    private lateinit var activationStatus: TextView
    private lateinit var durationSpinner: Spinner
    private lateinit var activateButton: MaterialButton
    private lateinit var deactivateButton: MaterialButton
    private lateinit var shellOnlySwitch: MaterialSwitch
    private lateinit var tokenValue: TextView

    private lateinit var settings: TBridgeSettings
    private lateinit var guard: BiometricGuard

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshActivationStatus()
            // refresh every 30s tant que l'activity est visible
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            TBridgeSettings.KEY_TOKEN -> refreshToken()
            TBridgeSettings.KEY_SHELL_ONLY -> refreshShellOnlySwitch()
            TBridgeSettings.KEY_ACTIVE_UNTIL, TBridgeSettings.KEY_LAST_DURATION -> {
                refreshActivationStatus()
                refreshDurationSpinner()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = TBridgeSettings(this)
        guard = BiometricGuard(this)

        bindViews()
        setupDurationSpinner()
        setupActivationButtons()
        setupShellOnlySwitch()
        setupTokenButtons()
    }

    private fun bindViews() {
        statusLabel = findViewById(R.id.statusLabel)
        activationStatus = findViewById(R.id.activationStatus)
        durationSpinner = findViewById(R.id.durationSpinner)
        activateButton = findViewById(R.id.activateButton)
        deactivateButton = findViewById(R.id.deactivateButton)
        shellOnlySwitch = findViewById(R.id.shellOnlySwitch)
        tokenValue = findViewById(R.id.tokenValue)

        findViewById<MaterialButton>(R.id.openSettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun setupDurationSpinner() {
        val labels = listOf(
            getString(R.string.duration_5min),
            getString(R.string.duration_15min),
            getString(R.string.duration_30min),
            getString(R.string.duration_1h),
            getString(R.string.duration_2h),
            getString(R.string.duration_4h),
        )
        durationSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
    }

    private fun setupActivationButtons() {
        activateButton.setOnClickListener {
            val duration = TBridgeSettings.DURATION_PRESETS_MS[durationSpinner.selectedItemPosition]
            guard.protect(
                title = getString(R.string.guard_activate_title),
                subtitle = getString(R.string.guard_subtitle),
                onSuccess = {
                    settings.activateFor(duration)
                    Toast.makeText(
                        this,
                        getString(R.string.toast_activated, formatDuration(duration)),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }
        deactivateButton.setOnClickListener {
            guard.protect(
                title = getString(R.string.guard_deactivate_title),
                subtitle = getString(R.string.guard_subtitle),
                onSuccess = {
                    settings.deactivate()
                    Toast.makeText(this, R.string.toast_deactivated, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    private fun setupShellOnlySwitch() {
        shellOnlySwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == settings.shellOnly) return@setOnCheckedChangeListener
            guard.protect(
                title = getString(R.string.guard_shell_only_title),
                subtitle = getString(R.string.guard_subtitle),
                onSuccess = { settings.shellOnly = checked },
                onCancel = { shellOnlySwitch.isChecked = settings.shellOnly },
            )
        }
    }

    private fun setupTokenButtons() {
        findViewById<MaterialButton>(R.id.copyTokenButton).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("tbridge token", settings.token))
            Toast.makeText(this, R.string.toast_token_copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.regenerateTokenButton).setOnClickListener {
            guard.protect(
                title = getString(R.string.guard_regenerate_title),
                subtitle = getString(R.string.guard_subtitle),
                onSuccess = {
                    settings.regenerateToken()
                    Toast.makeText(this, R.string.toast_token_regenerated, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        settings.registerListener(prefsListener)
        refreshHandler.postDelayed(refreshTask, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshTask)
        settings.unregisterListener(prefsListener)
        super.onPause()
    }

    private fun refreshAll() {
        refreshServiceStatus()
        refreshActivationStatus()
        refreshDurationSpinner()
        refreshShellOnlySwitch()
        refreshToken()
    }

    private fun refreshServiceStatus() {
        statusLabel.setText(
            if (isAccessibilityServiceEnabled()) R.string.status_enabled else R.string.status_disabled
        )
    }

    private fun refreshActivationStatus() {
        val active = settings.isListeningActive
        if (active) {
            val until = settings.listenActiveUntilMs
            val remaining = until - System.currentTimeMillis()
            val untilFormatted = DateFormat.getTimeFormat(this).format(Date(until))
            activationStatus.text =
                getString(R.string.activation_status_active, untilFormatted, formatDuration(remaining))
            deactivateButton.visibility = View.VISIBLE
        } else {
            activationStatus.setText(R.string.activation_status_inactive)
            deactivateButton.visibility = View.GONE
        }
    }

    private fun refreshDurationSpinner() {
        val lastUsed = settings.lastUsedDurationMs
        val idx = TBridgeSettings.DURATION_PRESETS_MS.indexOfFirst { it == lastUsed }
        if (idx >= 0) durationSpinner.setSelection(idx)
    }

    private fun refreshShellOnlySwitch() {
        shellOnlySwitch.isChecked = settings.shellOnly
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

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return getString(R.string.time_remaining_seconds, 0)
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> getString(R.string.time_remaining_hours, hours, minutes)
            minutes > 0 -> getString(R.string.time_remaining_minutes, minutes)
            else -> getString(R.string.time_remaining_seconds, seconds)
        }
    }

    companion object {
        private val REFRESH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30)
    }
}
