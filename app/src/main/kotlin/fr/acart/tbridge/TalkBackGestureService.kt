package fr.acart.tbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AccessibilityService that translates adb broadcasts into real TalkBack gestures
 * via [dispatchGesture] and [performAction].
 *
 * Gestures dispatched through the accessibility pipeline are visible to TalkBack
 * (unlike `input swipe` which is filtered as a synthetic touch event).
 *
 * Security : the receiver is exported (any installed app can send the broadcasts).
 * Two gates protect against unwanted use :
 *  1. [TBridgeSettings.listenEnabled] toggle (user-controlled via MainActivity)
 *  2. shared token extra : the broadcast must include `--es token "<secret>"`
 *     matching the token stored on device.
 */
internal class TalkBackGestureService : AccessibilityService() {

    private val tag = "TBGesture"

    private lateinit var settings: TBridgeSettings
    private var receiverRegistered = false

    /**
     * Permission Android requise du SENDER pour que son broadcast atteigne notre
     * receiver quand on est en mode shell-only. WRITE_SECURE_SETTINGS est
     * signature|privileged → adb shell (UID 2000) l'a, les apps user non.
     *
     * Pour rappel : `Binder.getCallingUid()` dans `BroadcastReceiver.onReceive`
     * retourne notre propre UID, pas celui du sender. La vraie restriction se
     * fait au niveau de `registerReceiver(..., broadcastPermission, ...)`.
     */
    private val shellOnlyPermission = "android.permission.WRITE_SECURE_SETTINGS"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return

            if (!settings.isListeningActive) {
                Log.d(tag, "Ignored ($action) : listening window expired or never started")
                if (settings.autoDisableOnExpiry && settings.listenActiveUntilMs > 0) {
                    Log.d(tag, "autoDisableOnExpiry triggered → disableSelf()")
                    disableSelf()
                }
                return
            }
            val incomingToken = intent.getStringExtra(TBridgeSettings.INTENT_EXTRA_TOKEN)
            if (incomingToken == null || incomingToken != settings.token) {
                Log.w(tag, "Ignored ($action) : token mismatch")
                return
            }

            Log.d(tag, "Action: $action")
            handleAction(action, intent)
        }
    }

    /**
     * Re-register le receiver à chaque toggle de shellOnly : si ON, on requiert
     * WRITE_SECURE_SETTINGS du sender ; si OFF, n'importe quel sender (token only).
     */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == TBridgeSettings.KEY_SHELL_ONLY) registerReceiverWithCurrentMode()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = TBridgeSettings(this)
        settings.registerListener(prefsListener)
        instance = this
        registerReceiverWithCurrentMode()
        Log.d(tag, "Service connected, receiver registered for ${GestureActions.ALL.size} actions (shellOnly=${settings.shellOnly})")

        // Si on arrive avec une fenêtre déjà expirée + autoDisable ON → kill direct
        if (settings.autoDisableOnExpiry && !settings.isListeningActive && settings.listenActiveUntilMs > 0) {
            Log.d(tag, "onServiceConnected with expired window + autoDisable → disableSelf()")
            disableSelf()
        }
    }

    private fun registerReceiverWithCurrentMode() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        val filter = IntentFilter().apply { GestureActions.ALL.forEach(::addAction) }
        val broadcastPermission = if (settings.shellOnly) shellOnlyPermission else null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, broadcastPermission, null, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter, broadcastPermission, null)
        }
        receiverRegistered = true
        Log.d(tag, "Receiver registered (broadcastPermission=$broadcastPermission)")
    }

    override fun onDestroy() {
        settings.unregisterListener(prefsListener)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }
                .onFailure { Log.w(tag, "Receiver was not registered: ${it.message}") }
            receiverRegistered = false
        }
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** Référence weak-ish exposée pour permettre à MainActivity de déclencher
         *  `disableSelf()` sans passer par un broadcast (qui requerrait token etc.).
         *  Mise à jour en onServiceConnected / onDestroy. */
        @Volatile
        var instance: TalkBackGestureService? = null
            private set
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun handleAction(action: String, intent: Intent) {
        when (action) {
            GestureActions.SWIPE_RIGHT -> swipeHorizontal(rightward = true)
            GestureActions.SWIPE_LEFT -> swipeHorizontal(rightward = false)
            GestureActions.SWIPE_UP -> swipeVertical(downward = false)
            GestureActions.SWIPE_DOWN -> swipeVertical(downward = true)
            GestureActions.TAP -> handleTap(intent)
            GestureActions.CLICK_FOCUSED -> performOnFocused(AccessibilityNodeInfo.ACTION_CLICK, "ACTION_CLICK")
            GestureActions.LONG_CLICK_FOCUSED -> performOnFocused(AccessibilityNodeInfo.ACTION_LONG_CLICK, "ACTION_LONG_CLICK")
            // SCROLL_DOWN (= voir le contenu d'en bas) = geste 2 doigts du bas vers le haut.
            GestureActions.SCROLL_DOWN -> swipeTwoFingersVertical(fingerGoesDown = false)
            // SCROLL_UP (= voir le contenu d'en haut) = geste 2 doigts du haut vers le bas.
            GestureActions.SCROLL_UP -> swipeTwoFingersVertical(fingerGoesDown = true)
            GestureActions.LIST_ACTIONS -> listActionsOnFocused()
            GestureActions.DO_ACTION -> doActionOnFocused(intent.getIntExtra(GestureActions.EXTRA_ACTION_ID, -1))
            GestureActions.GLOBAL_BACK -> performGlobalLogged(GLOBAL_ACTION_BACK, "GLOBAL_BACK")
            GestureActions.GLOBAL_HOME -> performGlobalLogged(GLOBAL_ACTION_HOME, "GLOBAL_HOME")
            GestureActions.GLOBAL_RECENTS -> performGlobalLogged(GLOBAL_ACTION_RECENTS, "GLOBAL_RECENTS")
            GestureActions.GLOBAL_NOTIFICATIONS -> performGlobalLogged(GLOBAL_ACTION_NOTIFICATIONS, "GLOBAL_NOTIFICATIONS")
            GestureActions.GLOBAL_QUICK_SETTINGS -> performGlobalLogged(GLOBAL_ACTION_QUICK_SETTINGS, "GLOBAL_QUICK_SETTINGS")
            GestureActions.GLOBAL_POWER_DIALOG -> performGlobalLogged(GLOBAL_ACTION_POWER_DIALOG, "GLOBAL_POWER_DIALOG")
            GestureActions.GLOBAL_LOCK -> performGlobalLogged(GLOBAL_ACTION_LOCK_SCREEN, "GLOBAL_LOCK")
            GestureActions.GLOBAL_SCREENSHOT -> performGlobalLogged(GLOBAL_ACTION_TAKE_SCREENSHOT, "GLOBAL_SCREENSHOT")
            else -> Log.w(tag, "Unknown action: $action")
        }
    }

    private fun performGlobalLogged(globalAction: Int, label: String) {
        val ok = performGlobalAction(globalAction)
        Log.d(tag, "$label → $ok")
    }

    private fun swipeHorizontal(rightward: Boolean) {
        val (w, h) = screenSize()
        val cy = h / 2f
        val startX: Float
        val endX: Float
        if (rightward) {
            startX = w * 0.2f
            endX = w * 0.8f
        } else {
            startX = w * 0.8f
            endX = w * 0.2f
        }
        dispatchSingleStrokeGesture(startX, cy, endX, cy, durationMs = 200L)
    }

    private fun swipeVertical(downward: Boolean) {
        val (w, h) = screenSize()
        val cx = w / 2f
        val startY: Float
        val endY: Float
        if (downward) {
            startY = h * 0.3f
            endY = h * 0.7f
        } else {
            startY = h * 0.7f
            endY = h * 0.3f
        }
        dispatchSingleStrokeGesture(cx, startY, cx, endY, durationMs = 200L)
    }

    private fun handleTap(intent: Intent) {
        val (w, h) = screenSize()
        val x = intent.extras?.getInt(GestureActions.EXTRA_X, -1)?.takeIf { it >= 0 }?.toFloat() ?: (w / 2f)
        val y = intent.extras?.getInt(GestureActions.EXTRA_Y, -1)?.takeIf { it >= 0 }?.toFloat() ?: (h / 2f)
        dispatchSingleStrokeGesture(x, y, x, y, durationMs = 50L)
    }

    /**
     * Geste 2 doigts vertical.
     * @param fingerGoesDown true = les doigts se déplacent du haut vers le bas (= scroll UP, voir
     *                        contenu d'en haut), false = doigts du bas vers le haut (= scroll DOWN).
     */
    private fun swipeTwoFingersVertical(fingerGoesDown: Boolean) {
        val (w, h) = screenSize()
        val yStart: Float
        val yEnd: Float
        if (fingerGoesDown) {
            yStart = h * 0.3f
            yEnd = h * 0.7f
        } else {
            yStart = h * 0.7f
            yEnd = h * 0.3f
        }
        val x1 = w * 0.4f
        val x2 = w * 0.6f

        val path1 = Path().apply {
            moveTo(x1, yStart)
            lineTo(x1, yEnd)
        }
        val path2 = Path().apply {
            moveTo(x2, yStart)
            lineTo(x2, yEnd)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0L, 200L))
            .addStroke(GestureDescription.StrokeDescription(path2, 0L, 200L))
            .build()
        // Le label loggé reflète l'EFFET (scroll), pas la direction physique du doigt.
        val label = if (fingerGoesDown) "SCROLL_UP" else "SCROLL_DOWN"
        dispatchGestureLogged(gesture, label = label)
    }

    private fun listActionsOnFocused() {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (node == null) {
            Log.w(tag, "ListActions: no accessibility-focused node")
            return
        }
        val nodeLabel = node.contentDescription ?: node.text ?: "?"
        Log.d(tag, "ListActions: node='$nodeLabel' class=${node.className} actions=${node.actionList.size}")
        node.actionList.forEachIndexed { idx, action ->
            // action.id est l'int à passer à DO_ACTION. action.label est le label visible
            // (souvent null pour les actions standard ; non-null pour les actions custom).
            val labelText = action.label ?: "(no label)"
            Log.d(tag, "ListActions: [$idx] id=${action.id} label='$labelText'")
        }
    }

    private fun doActionOnFocused(actionId: Int) {
        if (actionId < 0) {
            Log.w(tag, "DoAction: invalid action_id extra (use --ei action_id <int>)")
            return
        }
        val node = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (node == null) {
            Log.w(tag, "DoAction id=$actionId : no accessibility-focused node")
            return
        }
        val ok = node.performAction(actionId)
        val nodeLabel = node.contentDescription ?: node.text ?: "?"
        Log.d(tag, "DoAction id=$actionId on node '$nodeLabel' → $ok")
    }

    private fun dispatchSingleStrokeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        dispatchGestureLogged(gesture, label = "single-stroke ($startX,$startY)→($endX,$endY) ${durationMs}ms")
    }

    private fun dispatchGestureLogged(gesture: GestureDescription, label: String) {
        val ok = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    Log.d(tag, "Gesture completed: $label")
                }

                override fun onCancelled(description: GestureDescription?) {
                    Log.w(tag, "Gesture cancelled: $label")
                }
            },
            null,
        )
        if (!ok) Log.w(tag, "dispatchGesture returned false for $label")
    }

    private fun performOnFocused(action: Int, label: String) {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (node == null) {
            Log.w(tag, "$label : no accessibility-focused node")
            return
        }
        val ok = node.performAction(action)
        Log.d(tag, "$label on node '${node.contentDescription ?: node.text ?: "?"}' → $ok")
        // recycle() est deprecated depuis Android 11+ ; le GC gère désormais.
    }

    private fun screenSize(): Pair<Int, Int> {
        val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            android.graphics.Rect().also { rect ->
                @Suppress("DEPRECATION")
                display.getRectSize(rect)
            }
        }
        return metrics.width() to metrics.height()
    }

    private val windowManager: android.view.WindowManager
        get() = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
}
