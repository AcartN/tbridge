package fr.acart.tbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

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
            GestureActions.DOUBLE_TAP -> handleDoubleTap(intent)
            GestureActions.CLICK_FOCUSED -> performOnFocused(AccessibilityNodeInfo.ACTION_CLICK, "ACTION_CLICK")
            GestureActions.LONG_CLICK_FOCUSED -> performOnFocused(AccessibilityNodeInfo.ACTION_LONG_CLICK, "ACTION_LONG_CLICK")
            // SCROLL_DOWN (= voir le contenu d'en bas) = geste 2 doigts du bas vers le haut.
            GestureActions.SCROLL_DOWN -> swipeTwoFingersVertical(fingerGoesDown = false)
            // SCROLL_UP (= voir le contenu d'en haut) = geste 2 doigts du haut vers le bas.
            GestureActions.SCROLL_UP -> swipeTwoFingersVertical(fingerGoesDown = true)
            GestureActions.LIST_ACTIONS -> listActionsOnFocused()
            GestureActions.DO_ACTION -> doActionOnFocused(intent.getIntExtra(GestureActions.EXTRA_ACTION_ID, -1))
            GestureActions.DUMP_TREE -> dumpTree(intent.getBooleanExtra(GestureActions.EXTRA_ALL_WINDOWS, false))
            GestureActions.FOCUSED -> logFocused()
            GestureActions.INPUT_TEXT -> inputTextOnFocused(intent.getStringExtra(GestureActions.EXTRA_TEXT))
            GestureActions.ACTIVATE_BY_ID -> activateById(intent.getStringExtra(GestureActions.EXTRA_VIEW_ID))
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

    private fun handleDoubleTap(intent: Intent) {
        val (w, h) = screenSize()
        val x = intent.extras?.getInt(GestureActions.EXTRA_X, -1)?.takeIf { it >= 0 }?.toFloat() ?: (w / 2f)
        val y = intent.extras?.getInt(GestureActions.EXTRA_Y, -1)?.takeIf { it >= 0 }?.toFloat() ?: (h / 2f)
        val tap1 = Path().apply { moveTo(x, y); lineTo(x, y) }
        val tap2 = Path().apply { moveTo(x, y); lineTo(x, y) }
        // tap1 : 0-50ms ; gap 100ms ; tap2 : 150-200ms (sous seuil double-tap Android ~300ms)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tap1, 0L, 50L))
            .addStroke(GestureDescription.StrokeDescription(tap2, 150L, 50L))
            .build()
        dispatchGestureLogged(gesture, label = "double-tap ($x,$y)")
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

    /**
     * Sérialise l'arbre AccessibilityNodeInfo de la window active vers cacheDir/dump.xml,
     * dans un format compatible avec celui produit par `uiautomator dump` (consommable par
     * scripts/parse_dump.py). Pas de UiAutomation = TalkBack n'est pas suspendu.
     */
    /**
     * Trouve un node par viewId et l'active. Tente ACTION_CLICK direct ;
     * fallback double-tap aux bounds si refusé. Plus fiable que tap par coords
     * quand le focus accessibility est volatile (cover auto-défilante volant
     * le focus, modal mal isolée, etc.).
     */
    private fun activateById(viewId: String?) {
        if (viewId == null) {
            Log.w(tag, "ActivateById: missing --es view_id extra")
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(tag, "ActivateById: rootInActiveWindow null")
            return
        }
        // Accepte forme courte ('avatar') ou full ('fr.tf1.mytf1:id/avatar').
        // Compose `Modifier.testTag` stocke souvent l'id sans préfixe package
        // (resource-id="avatar" direct), forme que `findAccessibilityNodeInfosByViewId`
        // gère mal. Donc on fait un walk manuel qui matche soit la full id soit
        // le suffixe après '/'.
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findByIdSuffix(root, viewId, nodes)
        if (nodes.isEmpty()) {
            Log.w(tag, "ActivateById: no node found matching '$viewId'")
            return
        }
        val node = nodes[0]
        // 1. Tenter ACTION_CLICK direct
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(tag, "ActivateById $viewId: ACTION_CLICK → true")
            return
        }
        // 2. Fallback : forcer le focus accessibility sur ce node, puis double-tap
        // au CENTRE ÉCRAN (TalkBack convertit le double-tap en activate du focused).
        // Important : NE PAS double-tap aux bounds du node — si entre les 2 taps
        // le focus migre (cover auto-défilante), on activerait le node sous le
        // doigt au lieu de notre cible. En forçant ACCESSIBILITY_FOCUS d'abord
        // et en tapant ailleurs, on contourne le risque.
        val focusOk = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        val (w, h) = screenSize()
        val cx = w / 2f
        val cy = h / 2f
        val tap1 = Path().apply { moveTo(cx, cy); lineTo(cx, cy) }
        val tap2 = Path().apply { moveTo(cx, cy); lineTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tap1, 0L, 50L))
            .addStroke(GestureDescription.StrokeDescription(tap2, 150L, 50L))
            .build()
        dispatchGestureLogged(gesture, "activate-by-id $viewId [click=false→focus=$focusOk→double-tap center]")
    }

    /** Walk récursif qui ajoute aux résultats les nodes dont viewIdResourceName
     *  == viewId OU se termine par "/viewId" OU == viewId brut (Compose testTag). */
    private fun findByIdSuffix(node: AccessibilityNodeInfo, viewId: String, out: MutableList<AccessibilityNodeInfo>) {
        val rid = node.viewIdResourceName
        if (rid != null && (rid == viewId || rid.endsWith("/$viewId") || rid == viewId.substringAfter(":id/"))) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByIdSuffix(child, viewId, out)
        }
    }

    private fun inputTextOnFocused(text: String?) {
        if (text == null) {
            Log.w(tag, "InputText: missing --es text extra")
            return
        }
        val node = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (node == null) {
            Log.w(tag, "InputText: no accessibility-focused node")
            return
        }
        val bundle = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        val nodeLabel = node.contentDescription ?: node.text ?: "?"
        Log.d(tag, "InputText ${text.length} chars on node '$nodeLabel' → $ok")
    }

    private fun logFocused() {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (node == null) {
            Log.d(tag, "Focused: (none)")
            return
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val label = node.contentDescription ?: node.text ?: ""
        val rid = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        Log.d(tag, "Focused: class=$cls label='$label' id='$rid' clickable=${node.isClickable} bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
    }

    /**
     * @param allWindows true = dump TOUTES les windows visibles via getWindows()
     *   (utile pour voir les overlays système : dialog permission, share sheet).
     *   false (défaut) = dump uniquement rootInActiveWindow.
     *
     * Pourquoi le défaut single : avec multi-window, le focus accessibility peut
     * migrer entre windows (ex: cover home auto-défilante vole le focus d'une
     * modal au-dessus). Single = on voit ce qui est réellement actif → plus
     * prévisible pour la nav et les clicks par coords.
     */
    private fun dumpTree(allWindows: Boolean) {
        val out = File(cacheDir, "dump.xml")
        val roots = mutableListOf<AccessibilityNodeInfo>()
        if (allWindows) {
            val windows = runCatching { windows }.getOrNull().orEmpty()
            for (w in windows) {
                w.root?.let { roots.add(it) }
            }
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { roots.add(it) }
        }
        if (roots.isEmpty()) {
            Log.w(tag, "DumpTree: no window root accessible")
            out.writeText("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<hierarchy/>\n")
            return
        }
        val sb = StringBuilder(64 * 1024)
        sb.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n")
        sb.append("<hierarchy rotation=\"0\">\n")
        for (root in roots) {
            serializeNode(root, sb, depth = 1)
        }
        sb.append("</hierarchy>\n")
        out.writeText(sb.toString())
        Log.d(tag, "DumpTree: ${sb.length} bytes, ${roots.size} window(s), all=$allWindows → ${out.absolutePath}")
    }

    private fun serializeNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = " ".repeat(depth)
        sb.append(indent).append("<node")
        appendAttr(sb, "class", node.className?.toString().orEmpty())
        appendAttr(sb, "resource-id", node.viewIdResourceName.orEmpty())
        appendAttr(sb, "content-desc", node.contentDescription?.toString().orEmpty())
        appendAttr(sb, "text", node.text?.toString().orEmpty())
        appendAttr(sb, "clickable", node.isClickable.toString())
        appendAttr(sb, "focusable", node.isFocusable.toString())
        appendAttr(sb, "scrollable", node.isScrollable.toString())
        appendAttr(sb, "enabled", node.isEnabled.toString())
        appendAttr(sb, "long-clickable", node.isLongClickable.toString())
        if (node.isAccessibilityFocused) appendAttr(sb, "accessibility-focused", "true")
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        appendAttr(sb, "bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
        val childCount = node.childCount
        if (childCount == 0) {
            sb.append(" />\n")
            return
        }
        sb.append(">\n")
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            serializeNode(child, sb, depth + 1)
        }
        sb.append(indent).append("</node>\n")
    }

    private fun appendAttr(sb: StringBuilder, name: String, value: String) {
        sb.append(' ').append(name).append("=\"").append(xmlEscape(value)).append('"')
    }

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

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
