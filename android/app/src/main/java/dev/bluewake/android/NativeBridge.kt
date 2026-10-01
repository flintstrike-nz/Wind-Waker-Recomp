package dev.bluewake.android

/**
 * The host's side of the app (android/native/src/jni_bridge.cpp), in libmain.so.
 * SDLActivity loads that library before GameActivity runs, so these resolve
 * once the game is starting; do not call them from the launcher.
 */
object NativeBridge {
    // Touch button bits (apple/ios/src/touch_controls.h, SunPad's normalized layout).
    const val DPAD_LEFT = 1 shl 0
    const val DPAD_RIGHT = 1 shl 1
    const val DPAD_DOWN = 1 shl 2
    const val DPAD_UP = 1 shl 3
    const val Z = 1 shl 4
    const val R = 1 shl 5
    const val L = 1 shl 6
    const val A = 1 shl 8
    const val B = 1 shl 9
    const val X = 1 shl 10
    const val Y = 1 shl 11
    const val START = 1 shl 12

    // Why the guest is held; it runs only while none is set.
    const val PAUSE_INACTIVE = 1 shl 0
    const val PAUSE_MENU = 1 shl 1
    const val PAUSE_SETTINGS = 1 shl 2
    const val PAUSE_LAYOUT = 1 shl 3
    const val PAUSE_ALERT = 1 shl 4
    const val PAUSE_AUDIO = 1 shl 5

    /** Sticks are -127..127 with +y up. */
    @JvmStatic external fun nativePublishPad(buttons: Int, stickX: Int, stickY: Int, cStickX: Int, cStickY: Int)
    @JvmStatic external fun nativeClearPad()
    @JvmStatic external fun nativePauseSet(reason: Int, on: Boolean)
    @JvmStatic external fun nativePauseReasons(): Int

    /**
     * renderScale: 0 the window's own pixels, 1..4 times the game's 480 lines.
     * smoothMotion: 0 off, 1 in-between frames for 60 FPS, 2 for 120 FPS.
     */
    @JvmStatic external fun nativeSetRender(renderScale: Int, anisotropy: Int, smoothMotion: Int)
    @JvmStatic external fun nativeSetCameraInvert(invertX: Boolean, invertY: Boolean)
    @JvmStatic external fun nativeRemapSet(index: Int, sdlButton: Int)
    @JvmStatic external fun nativeRemapReset()
    @JvmStatic external fun nativeRemapCurrent(index: Int): Int
    @JvmStatic external fun nativeSetThermal(state: Int)

    /** The controller buttons a mapping can use: {SDL gamepad button number, name}, or null past the last. */
    @JvmStatic external fun nativeRemapChoice(position: Int): Array<String>?

    /** The GameCube button remapped at a position: "A", "B", "X", "Y", "Z", "Start". */
    @JvmStatic external fun nativeRemapName(index: Int): String?

    /** Fills {shown fps, game speed %, worst frame gap ms, display fps}. */
    @JvmStatic external fun nativeFpsRead(out: FloatArray)

    /** {name, title, default on "1"/"0", on now "1"/"0"}, or null past the last option. */
    @JvmStatic external fun nativeGameOption(position: Int): Array<String>?
}
