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
    const val SWIPE_TWO_FINGERS_UP = "${PREFIX}SWIPE_TWO_FINGERS_UP"

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
        SWIPE_TWO_FINGERS_UP,
    )
}
