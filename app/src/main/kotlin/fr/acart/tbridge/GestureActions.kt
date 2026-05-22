package fr.acart.tbridge

/** Action namespace for broadcasts received from adb. */
internal object GestureActions {
    const val PREFIX = "fr.acart.tbridge.gesture."

    const val SWIPE_RIGHT = "${PREFIX}SWIPE_RIGHT"
    const val SWIPE_LEFT = "${PREFIX}SWIPE_LEFT"
    const val SWIPE_UP = "${PREFIX}SWIPE_UP"
    const val SWIPE_DOWN = "${PREFIX}SWIPE_DOWN"
    const val TAP = "${PREFIX}TAP"
    /**
     * Double-tap atomique (= geste "activate" TalkBack). Dispatch 2 strokes courts
     * dans la même GestureDescription avec un gap ~150ms entre — sous le seuil
     * Android (300-500ms). À utiliser pour activer un node clickable=false ET
     * dont ACTION_CLICK retourne false, où le user TalkBack double-tape pour
     * activer (TalkBack peut alors faire fallback synthétique).
     */
    const val DOUBLE_TAP = "${PREFIX}DOUBLE_TAP"
    const val CLICK_FOCUSED = "${PREFIX}CLICK_FOCUSED"
    const val LONG_CLICK_FOCUSED = "${PREFIX}LONG_CLICK_FOCUSED"
    /** SCROLL_DOWN = voir le contenu en-dessous = geste 2 doigts du bas vers le haut. */
    const val SCROLL_DOWN = "${PREFIX}SCROLL_DOWN"

    /** SCROLL_UP = voir le contenu au-dessus = geste 2 doigts du haut vers le bas. */
    const val SCROLL_UP = "${PREFIX}SCROLL_UP"

    /** Inspection / actions custom du node accessibility-focused. */
    const val LIST_ACTIONS = "${PREFIX}LIST_ACTIONS"
    const val DO_ACTION = "${PREFIX}DO_ACTION"

    /**
     * Log le node actuellement accessibility-focused (class, label, id, bounds) dans
     * logcat tag TBGesture. Utilisé avant CLICK_FOCUSED pour s'assurer qu'on clique
     * bien sur le bon élément (les utterances logcat sont batched et peuvent induire
     * en erreur si le focus a avancé entre 2 captures).
     */
    const val FOCUSED = "${PREFIX}FOCUSED"

    /**
     * Dump l'arbre AccessibilityNodeInfo de la window active dans cacheDir/dump.xml.
     * Contrairement à `uiautomator dump` qui suspend tous les a11y services (UiAutomation),
     * cette action passe par `getRootInActiveWindow()` directement → TalkBack n'est pas
     * interrompu, donc pas d'annonce "TalkBack désactivé/activé" parasite.
     */
    const val DUMP_TREE = "${PREFIX}DUMP_TREE"

    /** Extra int requis par DO_ACTION : l'ID de l'action (cf. output de LIST_ACTIONS). */
    const val EXTRA_ACTION_ID = "action_id"

    /**
     * Saisit du texte dans le node accessibility-focused via ACTION_SET_TEXT.
     * Le focused doit être un EditText / TextInput (autres nodes renvoient false).
     * Remplace le contenu existant. Pré-requis : naviguer au champ via right/left
     * puis activate (le champ doit avoir l'input focus, pas juste l'accessibility focus).
     */
    const val INPUT_TEXT = "${PREFIX}INPUT_TEXT"

    /** Extra string requis par INPUT_TEXT. */
    const val EXTRA_TEXT = "text"

    /** Extra bool optionnel pour DUMP_TREE : true = toutes les windows visibles
     *  (overlays système inclus), false = active window seule (défaut). */
    const val EXTRA_ALL_WINDOWS = "all"

    /**
     * Trouve le node via findAccessibilityNodeInfosByViewId(extra `view_id`)
     * puis l'active : essai ACTION_CLICK direct, fallback double-tap aux bounds
     * si false. Plus fiable que tap par coords quand le focus est volatile
     * (cover auto-défilante qui migre le focus régulièrement).
     */
    const val ACTIVATE_BY_ID = "${PREFIX}ACTIVATE_BY_ID"

    /** Extra string requis par ACTIVATE_BY_ID : viewId (court ou avec package). */
    const val EXTRA_VIEW_ID = "view_id"

    // Global actions (performGlobalAction)
    const val GLOBAL_BACK = "${PREFIX}GLOBAL_BACK"
    const val GLOBAL_HOME = "${PREFIX}GLOBAL_HOME"
    const val GLOBAL_RECENTS = "${PREFIX}GLOBAL_RECENTS"
    const val GLOBAL_NOTIFICATIONS = "${PREFIX}GLOBAL_NOTIFICATIONS"
    const val GLOBAL_QUICK_SETTINGS = "${PREFIX}GLOBAL_QUICK_SETTINGS"
    const val GLOBAL_POWER_DIALOG = "${PREFIX}GLOBAL_POWER_DIALOG"
    const val GLOBAL_LOCK = "${PREFIX}GLOBAL_LOCK"
    const val GLOBAL_SCREENSHOT = "${PREFIX}GLOBAL_SCREENSHOT"

    /** Optional intent extras for [TAP]. */
    const val EXTRA_X = "x"
    const val EXTRA_Y = "y"

    val ALL: Array<String> = arrayOf(
        SWIPE_RIGHT,
        SWIPE_LEFT,
        SWIPE_UP,
        SWIPE_DOWN,
        TAP,
        DOUBLE_TAP,
        CLICK_FOCUSED,
        LONG_CLICK_FOCUSED,
        SCROLL_DOWN,
        SCROLL_UP,
        LIST_ACTIONS,
        DO_ACTION,
        DUMP_TREE,
        FOCUSED,
        INPUT_TEXT,
        ACTIVATE_BY_ID,
        GLOBAL_BACK,
        GLOBAL_HOME,
        GLOBAL_RECENTS,
        GLOBAL_NOTIFICATIONS,
        GLOBAL_QUICK_SETTINGS,
        GLOBAL_POWER_DIALOG,
        GLOBAL_LOCK,
        GLOBAL_SCREENSHOT,
    )
}
