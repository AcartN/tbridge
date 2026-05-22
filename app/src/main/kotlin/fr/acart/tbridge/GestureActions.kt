package fr.acart.tbridge

/** Action namespace for broadcasts received from adb. */
internal object GestureActions {
    const val PREFIX = "fr.acart.tbridge.gesture."

    const val SWIPE_RIGHT = "${PREFIX}SWIPE_RIGHT"
    const val SWIPE_LEFT = "${PREFIX}SWIPE_LEFT"
    const val SWIPE_UP = "${PREFIX}SWIPE_UP"
    const val SWIPE_DOWN = "${PREFIX}SWIPE_DOWN"
    const val TAP = "${PREFIX}TAP"
    const val CLICK_FOCUSED = "${PREFIX}CLICK_FOCUSED"
    const val LONG_CLICK_FOCUSED = "${PREFIX}LONG_CLICK_FOCUSED"
    /** SCROLL_DOWN = voir le contenu en-dessous = geste 2 doigts du bas vers le haut. */
    const val SCROLL_DOWN = "${PREFIX}SCROLL_DOWN"

    /** SCROLL_UP = voir le contenu au-dessus = geste 2 doigts du haut vers le bas. */
    const val SCROLL_UP = "${PREFIX}SCROLL_UP"

    /** Inspection / actions custom du node accessibility-focused. */
    const val LIST_ACTIONS = "${PREFIX}LIST_ACTIONS"
    const val DO_ACTION = "${PREFIX}DO_ACTION"

    /** Extra int requis par DO_ACTION : l'ID de l'action (cf. output de LIST_ACTIONS). */
    const val EXTRA_ACTION_ID = "action_id"

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
        CLICK_FOCUSED,
        LONG_CLICK_FOCUSED,
        SCROLL_DOWN,
        SCROLL_UP,
        LIST_ACTIONS,
        DO_ACTION,
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
