package fr.acart.tbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.util.Date
import java.util.concurrent.TimeUnit

internal class MainActivity : AppCompatActivity() {

    private lateinit var settings: TBridgeSettings
    private lateinit var guard: BiometricGuard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = TBridgeSettings(this)
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
                        onActivate = { durationMs ->
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
                            guard.protect(
                                title = getString(R.string.guard_deactivate_title),
                                subtitle = getString(R.string.guard_subtitle),
                                onSuccess = {
                                    settings.deactivate()
                                    toast(getString(R.string.toast_deactivated))
                                },
                            )
                        },
                        onShellOnlyChange = { newValue ->
                            guard.protect(
                                title = getString(R.string.guard_shell_only_title),
                                subtitle = getString(R.string.guard_subtitle),
                                onSuccess = { settings.shellOnly = newValue },
                            )
                        },
                        onCopyToken = {
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("tbridge token", settings.token))
                            toast(getString(R.string.toast_token_copied))
                        },
                        onRegenerateToken = {
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
        val expected = ComponentName(this, TalkBackGestureService::class.java).flattenToString()
        val raw = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return raw.split(":").any { it.equals(expected, ignoreCase = true) }
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
    val token: String,
)

@Composable
private fun rememberSettingsSnapshot(settings: TBridgeSettings): SettingsSnapshot {
    var snapshot by remember {
        mutableStateOf(captureSnapshot(settings))
    }
    // SharedPreferences listener → push update à chaque changement
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            snapshot = captureSnapshot(settings)
        }
        settings.registerListener(listener)
        onDispose { settings.unregisterListener(listener) }
    }
    // Tick périodique pour rafraîchir le countdown sans nouveau Pref change
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            val current = captureSnapshot(settings)
            // Replace seulement si l'état activé/désactivé a changé OU si on est actif
            // (pour mettre à jour le countdown affiché)
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
    token = settings.token,
)

@Composable
private fun TbridgeScreen(
    settings: TBridgeSettings,
    isAccessibilityServiceEnabled: () -> Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onActivate: (Long) -> Unit,
    onDeactivate: () -> Unit,
    onShellOnlyChange: (Boolean) -> Unit,
    onCopyToken: () -> Unit,
    onRegenerateToken: () -> Unit,
) {
    val snapshot = rememberSettingsSnapshot(settings)
    var selectedDurationMs by remember { mutableLongStateOf(snapshot.lastUsedDurationMs) }
    var serviceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled()) }
    // Refresh service status à chaque ON_RESUME (= retour depuis Paramètres Accessibilité).
    // Si on détecte un état zombie (service désactivé mais écoute "active" dans les prefs —
    // ça arrive après un installDebug qui retire le service de enabled_accessibility_services
    // par sécurité Android), on reset la fenêtre d'écoute pour cohérence.
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
            token = snapshot.token,
            onShellOnlyChange = onShellOnlyChange,
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
private fun ServiceStatusCard(enabled: Boolean, onOpenSettings: () -> Unit) {
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
    token: String,
    onShellOnlyChange: (Boolean) -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.shell_only_switch_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = shellOnly,
                    onCheckedChange = { onShellOnlyChange(it) },
                )
            }
            Text(
                text = stringResource(R.string.shell_only_hint),
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
        }
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
