package fr.acart.tbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 */
internal class TalkBackGestureService : AccessibilityService() {

    private val tag = "TBGesture"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            Log.d(tag, "Action: $action")
            handleAction(action, intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter().apply { GestureActions.ALL.forEach(::addAction) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        Log.d(tag, "Service connected, receiver registered for ${GestureActions.ALL.size} actions")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
            .onFailure { Log.w(tag, "Receiver was not registered: ${it.message}") }
        super.onDestroy()
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
            GestureActions.SWIPE_TWO_FINGERS_UP -> swipeTwoFingersUp()
            else -> Log.w(tag, "Unknown action: $action")
        }
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

    private fun swipeTwoFingersUp() {
        val (w, h) = screenSize()
        val cy1 = h * 0.7f
        val cy2 = h * 0.3f
        val x1 = w * 0.4f
        val x2 = w * 0.6f

        val path1 = Path().apply {
            moveTo(x1, cy1)
            lineTo(x1, cy2)
        }
        val path2 = Path().apply {
            moveTo(x2, cy1)
            lineTo(x2, cy2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0L, 200L))
            .addStroke(GestureDescription.StrokeDescription(path2, 0L, 200L))
            .build()
        dispatchGestureLogged(gesture, label = "SWIPE_TWO_FINGERS_UP")
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
