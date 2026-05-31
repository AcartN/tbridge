package fr.acart.tbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.Date
import java.util.concurrent.TimeUnit

internal class MainActivity : AppCompatActivity() {

    private lateinit var settings: TBridgeSettings
    private lateinit var guard: BiometricGuard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = TBridgeSettings(this)
        settings.cleanupLegacyKeys()
        guard = BiometricGuard(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TbridgeScreen(
                        settings = settings,
                        isAccessibilityServiceEnabled = { isAccessibilityServiceEnabled() },
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onOpenTalkBackSettings = {
                            // Raccourci direct vers les réglages Google TalkBack (l'activity
                            // n'est pas lançable depuis le launcher). Fallback sur les réglages
                            // d'accessibilité Android si TalkBack absent (ex. device Samsung sans
                            // Google TalkBack installé).
                            try {
                                startActivity(
                                    Intent().setClassName(
                                        "com.google.android.marvin.talkback",
                                        "com.android.talkback.TalkBackPreferencesActivity",
                                    )
                                )
                            } catch (e: Exception) {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                toast(getString(R.string.toast_talkback_settings_unavailable))
                            }
                        },
                        onActivate = { durationMs ->
                            // Activation = grant access → biometric required.
                            guard.protect(
                                title = getString(R.string.guard_activate_title),
                                subtitle = getString(R.string.guard_subtitle),
                                onSuccess = {
                                    settings.activateFor(durationMs)
                                    toast(getString(R.string.toast_activated, formatDuration(durationMs)))
                                },
                            )
                        },
                        onDeactivate = {
                            // Deactivation = remove access → no biometric.
                            settings.deactivate()
                            toast(getString(R.string.toast_deactivated))
                        },
                        onShellOnlyChange = { newValue ->
                            if (!newValue) {
                                // ON→OFF = loosen restriction = grant access → biometric.
                                guard.protect(
                                    title = getString(R.string.guard_shell_only_loosen_title),
                                    subtitle = getString(R.string.guard_subtitle),
                                    onSuccess = { settings.shellOnly = false },
                                )
                            } else {
                                // OFF→ON = tighten restriction → free.
                                settings.shellOnly = true
                            }
                        },
                        onAutoDisableChange = { newValue ->
                            if (!newValue) {
                                // ON→OFF = relax security → biometric.
                                guard.protect(
                                    title = getString(R.string.guard_auto_disable_off_title),
                                    subtitle = getString(R.string.guard_subtitle),
                                    onSuccess = { settings.autoDisableOnExpiry = false },
                                )
                            } else {
                                // OFF→ON = tighten → free.
                                settings.autoDisableOnExpiry = true
                            }
                        },
                        onDisableService = {
                            // Disable service Android = remove access → no biometric.
                            val svc = TalkBackGestureService.instance
                            if (svc != null) {
                                svc.disableSelf()
                                toast(getString(R.string.toast_service_disabled))
                            } else {
                                toast(getString(R.string.toast_service_unavailable))
                            }
                        },
                        onCopyToken = {
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("tbridge token", settings.token))
                            toast(getString(R.string.toast_token_copied))
                        },
                        onRegenerateToken = {
                            // Rotation token — destructive pour la session adb. Garde biométrique.
                            guard.protect(
                                title = getString(R.string.guard_regenerate_title),
                                subtitle = getString(R.string.guard_subtitle),
                                onSuccess = {
                                    settings.regenerateToken()
                                    toast(getString(R.string.toast_token_regenerated))
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, TalkBackGestureService::class.java)
        val raw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return raw.split(":").any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
    }
}

/**
 * Snapshot des settings, observé via SharedPreferences listener + tick périodique
 * pour le countdown de la fenêtre d'activation.
 */
private data class SettingsSnapshot(
    val isListeningActive: Boolean,
    val listenActiveUntilMs: Long,
    val lastUsedDurationMs: Long,
    val shellOnly: Boolean,
    val autoDisableOnExpiry: Boolean,
    val token: String,
)

@Composable
private fun rememberSettingsSnapshot(settings: TBridgeSettings): SettingsSnapshot {
    var snapshot by remember {
        mutableStateOf(captureSnapshot(settings))
    }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            snapshot = captureSnapshot(settings)
        }
        settings.registerListener(listener)
        onDispose { settings.unregisterListener(listener) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            val current = captureSnapshot(settings)
            if (current.isListeningActive != snapshot.isListeningActive || current.isListeningActive) {
                snapshot = current
            }
        }
    }
    return snapshot
}

private fun captureSnapshot(settings: TBridgeSettings): SettingsSnapshot = SettingsSnapshot(
    isListeningActive = settings.isListeningActive,
    listenActiveUntilMs = settings.listenActiveUntilMs,
    lastUsedDurationMs = settings.lastUsedDurationMs,
    shellOnly = settings.shellOnly,
    autoDisableOnExpiry = settings.autoDisableOnExpiry,
    token = settings.token,
)

@Composable
private fun TbridgeScreen(
    settings: TBridgeSettings,
    isAccessibilityServiceEnabled: () -> Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenTalkBackSettings: () -> Unit,
    onActivate: (Long) -> Unit,
    onDeactivate: () -> Unit,
    onShellOnlyChange: (Boolean) -> Unit,
    onAutoDisableChange: (Boolean) -> Unit,
    onDisableService: () -> Unit,
    onCopyToken: () -> Unit,
    onRegenerateToken: () -> Unit,
) {
    val snapshot = rememberSettingsSnapshot(settings)
    var selectedDurationMs by remember { mutableLongStateOf(snapshot.lastUsedDurationMs) }
    var serviceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled()) }

    // Refresh à ON_RESUME (retour depuis Settings Android) + reset zombie state.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val enabled = isAccessibilityServiceEnabled()
                serviceEnabled = enabled
                if (!enabled && settings.isListeningActive) {
                    settings.deactivate()
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // ContentObserver sur la URI Settings → refresh immédiat dès que enabled_accessibility_services
    // change (in-app via disableSelf, ou hors-app via Paramètres Android).
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                serviceEnabled = isAccessibilityServiceEnabled()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.subtitle),
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(modifier = Modifier.height(24.dp))

        ServiceStatusCard(
            enabled = serviceEnabled,
            onOpenSettings = onOpenAccessibilitySettings,
            onOpenTalkBackSettings = onOpenTalkBackSettings,
        )

        Spacer(modifier = Modifier.height(24.dp))

        ActivationCard(
            isActive = snapshot.isListeningActive,
            listenActiveUntilMs = snapshot.listenActiveUntilMs,
            selectedDurationMs = selectedDurationMs,
            onSelectDuration = { selectedDurationMs = it },
            onActivate = { onActivate(selectedDurationMs) },
            onDeactivate = onDeactivate,
        )

        Spacer(modifier = Modifier.height(16.dp))

        SecurityCard(
            shellOnly = snapshot.shellOnly,
            autoDisableOnExpiry = snapshot.autoDisableOnExpiry,
            token = snapshot.token,
            onShellOnlyChange = onShellOnlyChange,
            onAutoDisableChange = onAutoDisableChange,
            onDisableService = onDisableService,
            onCopyToken = onCopyToken,
            onRegenerateToken = onRegenerateToken,
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.howto_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.howto_body),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ServiceStatusCard(
    enabled: Boolean,
    onOpenSettings: () -> Unit,
    onOpenTalkBackSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(
                    if (enabled) R.string.status_enabled else R.string.status_disabled
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.open_settings))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenTalkBackSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.open_talkback_settings))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivationCard(
    isActive: Boolean,
    listenActiveUntilMs: Long,
    selectedDurationMs: Long,
    onSelectDuration: (Long) -> Unit,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.activation_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            val statusText = if (isActive) {
                val untilFormatted = DateFormat.getTimeFormat(context).format(Date(listenActiveUntilMs))
                val remaining = (listenActiveUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
                stringResource(R.string.activation_status_active, untilFormatted, formatDuration(remaining))
            } else {
                stringResource(R.string.activation_status_inactive)
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.duration_label),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            DurationChips(
                selected = selectedDurationMs,
                onSelect = onSelectDuration,
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = onActivate,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.activate))
                }
                if (isActive) {
                    OutlinedButton(
                        onClick = onDeactivate,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.deactivate))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DurationChips(
    selected: Long,
    onSelect: (Long) -> Unit,
) {
    val items = listOf(
        TBridgeSettings.DURATION_PRESETS_MS[0] to R.string.duration_5min,
        TBridgeSettings.DURATION_PRESETS_MS[1] to R.string.duration_15min,
        TBridgeSettings.DURATION_PRESETS_MS[2] to R.string.duration_30min,
        TBridgeSettings.DURATION_PRESETS_MS[3] to R.string.duration_1h,
        TBridgeSettings.DURATION_PRESETS_MS[4] to R.string.duration_2h,
        TBridgeSettings.DURATION_PRESETS_MS[5] to R.string.duration_4h,
    )
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (durationMs, labelRes) ->
            FilterChip(
                selected = selected == durationMs,
                onClick = { onSelect(durationMs) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }
}

@Composable
private fun SecurityCard(
    shellOnly: Boolean,
    autoDisableOnExpiry: Boolean,
    token: String,
    onShellOnlyChange: (Boolean) -> Unit,
    onAutoDisableChange: (Boolean) -> Unit,
    onDisableService: () -> Unit,
    onCopyToken: () -> Unit,
    onRegenerateToken: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(12.dp))
            SwitchRow(
                label = stringResource(R.string.shell_only_switch_label),
                checked = shellOnly,
                onChange = onShellOnlyChange,
            )
            Text(
                text = stringResource(R.string.shell_only_hint),
                style = MaterialTheme.typography.labelSmall,
            )

            Spacer(modifier = Modifier.height(12.dp))
            SwitchRow(
                label = stringResource(R.string.auto_disable_switch_label),
                checked = autoDisableOnExpiry,
                onChange = onAutoDisableChange,
            )
            Text(
                text = stringResource(R.string.auto_disable_hint),
                style = MaterialTheme.typography.labelSmall,
            )

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.token_label),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = token,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilledTonalButton(
                    onClick = onCopyToken,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.copy_token))
                }
                OutlinedButton(
                    onClick = onRegenerateToken,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.regenerate_token))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            OutlinedButton(
                onClick = onDisableService,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.disable_service))
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.disable_service_hint),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
        )
    }
}

@Composable
private fun stringResource(resId: Int): String =
    androidx.compose.ui.res.stringResource(resId)

@Composable
private fun stringResource(resId: Int, vararg formatArgs: Any): String =
    androidx.compose.ui.res.stringResource(resId, *formatArgs)

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0 s"
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}"
        minutes > 0 -> "$minutes min"
        else -> "$seconds s"
    }
}
