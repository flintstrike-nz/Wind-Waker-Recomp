package dev.bluewake.android

import android.content.Context
import android.content.SharedPreferences
import android.system.Os

/**
 * The player's settings. Private to the app and never part of a release
 * (AGENTS.md). Some apply while the game runs (render scale, Smooth Motion,
 * camera inversion, button mapping, the controls); the mods apply at the next
 * launch because the game module reads them once, at boot ([applyLaunchEnvironment]).
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("bluewake", Context.MODE_PRIVATE)

    /** 0 the window's own pixels, 1..4 times the game's 480 lines. */
    var renderScale: Int
        get() = sp.getInt("renderScale", 2)
        set(v) = sp.edit().putInt("renderScale", v).apply()

    /** 1 the game's own filtering, or 4, 8, 16 forced anisotropic. */
    var anisotropy: Int
        get() = sp.getInt("anisotropy", 1)
        set(v) = sp.edit().putInt("anisotropy", v).apply()

    /** In-between frames drawn by the renderer: 0 off, 1 for 60 FPS, 2 for 120 FPS (a 120 Hz display). */
    var smoothMotion: Int
        get() = sp.getInt("smoothMotion", 0)
        set(v) = sp.edit().putInt("smoothMotion", v).apply()

    /** The saved controller button mapping: SDL gamepad button numbers by remappable button; null for the default. */
    fun remap(index: Int): Int? = if (sp.contains("remap.$index")) sp.getInt("remap.$index", 0) else null
    fun setRemap(index: Int, button: Int) = sp.edit().putInt("remap.$index", button).apply()
    fun clearRemap() {
        val e = sp.edit()
        for (k in sp.all.keys) if (k.startsWith("remap.")) e.remove(k)
        e.apply()
    }

    var invertCameraX: Boolean
        get() = sp.getBoolean("invertCameraX", false)
        set(v) = sp.edit().putBoolean("invertCameraX", v).apply()

    var invertCameraY: Boolean
        get() = sp.getBoolean("invertCameraY", false)
        set(v) = sp.edit().putBoolean("invertCameraY", v).apply()

    /** false: the picture keeps its aspect (letterboxed); true: it fills the screen. Applies at launch. */
    var fillScreen: Boolean
        get() = sp.getBoolean("fillScreen", false)
        set(v) = sp.edit().putBoolean("fillScreen", v).apply()

    /** 0 off, 1 16:9, 2 16:10. Applies at launch. */
    var widescreen: Int
        get() = sp.getInt("widescreen", 0)
        set(v) = sp.edit().putInt("widescreen", v).apply()

    var hdTextures: Boolean
        get() = sp.getBoolean("hdTextures", false)
        set(v) = sp.edit().putBoolean("hdTextures", v).apply()

    var betterWindWaker: Boolean
        get() = sp.getBoolean("betterWW", false)
        set(v) = sp.edit().putBoolean("betterWW", v).apply()

    /** Better Wind Waker's options the player changed from their defaults: name -> on. */
    fun gameOptions(): Map<String, Boolean> =
        sp.all.filterKeys { it.startsWith("option.") }
            .mapKeys { it.key.removePrefix("option.") }
            .mapValues { it.value as? Boolean ?: false }

    fun gameOption(name: String): Boolean? =
        if (sp.contains("option.$name")) sp.getBoolean("option.$name", false) else null

    fun setGameOption(name: String, on: Boolean) = sp.edit().putBoolean("option.$name", on).apply()

    // ---- the on-screen controls

    var showTouchControls: Boolean
        get() = sp.getBoolean("showTouch", true)
        set(v) = sp.edit().putBoolean("showTouch", v).apply()

    /** Hide the on-screen controls while a controller is in use. */
    var hideOnController: Boolean
        get() = sp.getBoolean("hideOnController", true)
        set(v) = sp.edit().putBoolean("hideOnController", v).apply()

    var controlOpacity: Float
        get() = sp.getFloat("controlOpacity", 0.6f)
        set(v) = sp.edit().putFloat("controlOpacity", v).apply()

    var controlSize: Float
        get() = sp.getFloat("controlSize", 1.0f)
        set(v) = sp.edit().putFloat("controlSize", v).apply()

    var showFps: Boolean
        get() = sp.getBoolean("showFps", false)
        set(v) = sp.edit().putBoolean("showFps", v).apply()

    /** Where the controls go: [PLACE_AUTO], [PLACE_BELOW] the picture or [PLACE_OVER] it. */
    var placement: Int
        get() = sp.getInt("placement", PLACE_AUTO)
        set(v) = sp.edit().putInt("placement", v).apply()

    /** A control's saved center, as fractions of the controls' area, per layout; null keeps its default. */
    fun controlCenter(layout: String, id: String): Pair<Float, Float>? {
        val s = sp.getString("pos.$layout.$id", null) ?: return null
        val parts = s.split(',')
        if (parts.size != 2) return null
        val x = parts[0].toFloatOrNull() ?: return null
        val y = parts[1].toFloatOrNull() ?: return null
        return x to y
    }

    fun setControlCenter(layout: String, id: String, x: Float, y: Float) =
        sp.edit().putString("pos.$layout.$id", "$x,$y").apply()

    fun resetControlLayout() {
        val e = sp.edit()
        for (k in sp.all.keys) if (k.startsWith("pos.")) e.remove(k)
        e.apply()
    }

    /**
     * Puts the launch-time choices into the process environment, where the host
     * reads them (android/native/src/android_entry.cpp, runtime/host/src/main.c).
     * Called before SDLActivity loads the native libraries.
     */
    fun applyLaunchEnvironment(paths: DataPaths) {
        fun set(name: String, value: String) = Os.setenv(name, value, true)
        set("BLUEWAKE_DATA_DIR", paths.data.absolutePath)
        set("BLUEWAKE_COMPOSITE", paths.composite.absolutePath)
        // The picture keeps the game's aspect unless the player fills the screen.
        if (fillScreen) set("DOL_AURORA_ASPECT_FIT", "0")
        val mods = ArrayList<String>()
        when (widescreen) {
            // The widescreen code renders anamorphic 16:9 (or 16:10), so the picture
            // is letterboxed to that shape whatever the aspect setting.
            2 -> { mods += "widescreen1610"; set("DOL_AURORA_ASPECT_RATIO", "1.6") }
            1 -> { mods += "widescreen"; set("DOL_AURORA_ASPECT_RATIO", "1.7778") }
        }
        // HD textures: Dolphin-format packs in <data>/Load/Textures/GZLE01.
        paths.texturePack.mkdirs()
        if (hdTextures) set("DOL_AURORA_TEXTURE_PACK", paths.texturePack.absolutePath)
        if (betterWindWaker) {
            mods += "betterww"
            val options = gameOptions().map { (name, on) -> if (on) name else "-$name" }
            if (options.isNotEmpty()) set("BLUEWAKE_OPTIONS", options.joinToString(","))
        }
        if (mods.isNotEmpty()) set("BLUEWAKE_MODS", mods.joinToString(","))
        // Touches are the app's own controls, not mouse input.
        set("SDL_TOUCH_MOUSE_EVENTS", "0")
        set("SDL_ORIENTATIONS", "LandscapeLeft LandscapeRight")
    }

    companion object {
        const val PLACE_AUTO = 0
        const val PLACE_BELOW = 1
        const val PLACE_OVER = 2
    }
}
