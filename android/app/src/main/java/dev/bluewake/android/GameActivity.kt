package dev.bluewake.android

import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.libsdl.app.SDLActivity
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The running game: SDL's activity (SDL owns the surface, audio and the game's
 * thread, which runs libmain.so's SDL_main) plus the app's own layer over it:
 * the on-screen controls, the menu, the FPS label, and the arrangement of the
 * screen.
 *
 * The screen is arranged for a foldable such as the Oppo Find N3, whose inner
 * display (7.82", 2440 x 2268) is nearly square: the game's 4:3 picture fills
 * the width and leaves a strip below it, where the controls go instead of
 * covering the picture. Half-folded (Flex mode) the picture takes the upper
 * half and the controls the lower. On the cover screen, or wherever there is no
 * room, the controls sit over the picture. See [applyLayout].
 */
class GameActivity : SDLActivity(), ControlsView.Host {
    private lateinit var prefs: Prefs
    private lateinit var paths: DataPaths
    private lateinit var menu: GameMenu
    private var controls: ControlsView? = null
    private var fpsLabel: TextView? = null
    private var posture = PostureTracker.Posture(null)
    private var postureTracker: PostureTracker? = null
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    // The launch-time choices that decide the picture's shape, as the game was started with.
    private var launchWidescreen = 0
    private var launchFill = false
    private var lastLayoutSize = 0L

    // SDL is linked into libmain.so, so that is the only library to load.
    override fun getLibraries(): Array<String> = arrayOf("main")

    // The manifest fixes the orientation to landscape (either way up); SDL would
    // otherwise allow portrait for a resizable window.
    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String?) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        paths = DataPaths(this)
        prefs = Prefs(this)
        launchWidescreen = prefs.widescreen
        launchFill = prefs.fillScreen
        // Before SDL loads the native libraries: the launch-time choices and paths.
        prefs.applyLaunchEnvironment(paths)
        super.onCreate(savedInstanceState)
        menu = GameMenu(this, prefs)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.also {
            it.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()
        preferHighestRefreshRate()

        val layout = mLayout as? RelativeLayout
        if (layout != null) {
            val view = ControlsView(this, prefs).also {
                it.host = this
                it.layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            }
            controls = view
            layout.addView(view)
            val label = TextView(this).apply {
                setTextColor(0xFFFFFFFF.toInt())
                setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
                textSize = 13f
                gravity = Gravity.START
                setPadding(24, 12, 24, 12)
                visibility = View.GONE
            }
            fpsLabel = label
            layout.addView(label, RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // The window changes size when the device folds or unfolds, and in
            // multi-window: arrange the screen again then.
            layout.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                val size = ((r - l).toLong() shl 32) or (b - t).toLong()
                if (size != lastLayoutSize) {
                    lastLayoutSize = size
                    layout.post { applyLayout() }
                }
            }
        }

        applyRenderSettings()
        applyCameraSettings()
        restoreRemap()
        applyShowFps()
        applyControlsVisibility()
        registerControllerWatch()
        registerThermalWatch()
        postureTracker = PostureTracker(this) { posture = it; applyLayout() }.also { it.start() }
        main.post(fpsTick)
    }

    override fun onDestroy() {
        postureTracker?.stop()
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        // Fingers lifted by the system must not stay held in the game.
        controls?.releaseTouches()
        try {
            NativeBridge.nativeClearPad()
        } catch (_: UnsatisfiedLinkError) {
        }
        super.onPause()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Asks for the display's highest refresh rate at its current size (the Find N3's is 120 Hz). */
    private fun preferHighestRefreshRate() {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (best.refreshRate > current.refreshRate + 1f) {
            window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
        }
    }

    // ---------------------------------------------------------------- the screen's arrangement

    /**
     * Decides where the picture and the controls go, from the window and the fold.
     *
     * The SDL surface is shrunk from the bottom by the height of a strip that
     * the controls take; the renderer then fits the picture in what is left, so
     * the picture sits at the top and the controls under it. The strip is:
     *  - below the hinge in Flex mode;
     *  - with "Automatic" placement, the room left under the picture's own shape when that is
     *    at least 140 dp (4:3 on the Find N3's inner screen leaves about 167 dp; 16:9 much more);
     *  - with "Below the picture", always: that room, or 160 dp if there is less;
     *  - none otherwise (the cover screen under "Automatic", "Over the picture", a picture
     *    that fills the screen): the controls cover the picture's lower part.
     */
    fun applyLayout() {
        val layout = mLayout as? RelativeLayout ?: return
        val surface = mSurface ?: return
        val view = controls ?: return
        val w = layout.width
        val h = layout.height
        if (w <= 0 || h <= 0) return
        val d = resources.displayMetrics.density
        var band = 0
        var key = "over"
        val hinge = posture.hinge
        if (hinge != null) {
            val location = IntArray(2).also { layout.getLocationInWindow(it) }
            val top = (hinge.top - location[1]).coerceIn(h / 3, h * 3 / 4)
            band = h - top
            key = "flex"
        } else if (prefs.placement == Prefs.PLACE_BELOW) {
            // Asked for: always a strip, the room left under the picture's shape or at least
            // 160 dp, and never more than half the window.
            val spare = if (launchFill) 0f else h - w / pictureAspect()
            band = max(spare, 160f * d).toInt().coerceAtMost(h / 2)
            key = "band"
        } else if (!launchFill && prefs.placement == Prefs.PLACE_AUTO) {
            val spare = h - w / pictureAspect()
            if (spare >= 140f * d) {
                band = spare.toInt()
                key = "band"
            }
        }
        val params = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        params.bottomMargin = band
        val current = surface.layoutParams as? RelativeLayout.LayoutParams
        if (current == null || current.bottomMargin != band || current.height != ViewGroup.LayoutParams.MATCH_PARENT)
            surface.layoutParams = params

        // The controls' area: the strip, but never shorter than 200 dp (the
        // controls may reach up over the picture's lower edge); the whole window
        // when they sit over the picture (so they can be moved anywhere).
        val areaHeight = if (band > 0) max(band, (200f * d).toInt()).coerceAtMost(h) else h
        val controlParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, areaHeight)
        controlParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        view.layoutParams = controlParams
        view.layoutKey = key
        view.relayout()
    }

    private fun pictureAspect() = when (launchWidescreen) { 1 -> 16f / 9f; 2 -> 1.6f; else -> 4f / 3f }

    // ---------------------------------------------------------------- controls and the menu

    override fun onMenuRequested() = openMenu()

    private fun openMenu() {
        if (controls?.editing == true) return
        menu.show()
    }

    fun beginLayoutEditing() {
        controls?.editing = true
        try {
            NativeBridge.nativePauseSet(NativeBridge.PAUSE_LAYOUT, true)
        } catch (_: UnsatisfiedLinkError) {
        }
        // The controls may be hidden by a controller or the setting: show them to be moved.
        controls?.controlsVisible = true
    }

    override fun onLayoutEditingFinished() {
        controls?.editing = false
        try {
            NativeBridge.nativePauseSet(NativeBridge.PAUSE_LAYOUT, false)
        } catch (_: UnsatisfiedLinkError) {
        }
        applyControlsVisibility()
    }

    fun relayoutControls() = controls?.relayout()
    fun redrawControls() = controls?.invalidate()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (controls?.editing == true) onLayoutEditingFinished() else openMenu()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A controller's Select/Back button opens the menu: the game uses its other buttons.
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT && event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0) {
            openMenu()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private var controllerPresent = false

    private fun registerControllerWatch() {
        val manager = getSystemService(InputManager::class.java)
        manager.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = scanControllers()
            override fun onInputDeviceRemoved(deviceId: Int) = scanControllers()
            override fun onInputDeviceChanged(deviceId: Int) = scanControllers()
        }, main)
        scanControllers()
    }

    private fun scanControllers() {
        controllerPresent = InputDevice.getDeviceIds().any { id ->
            val device = InputDevice.getDevice(id)
            device != null && !device.isVirtual &&
                (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        }
        applyControlsVisibility()
    }

    fun applyControlsVisibility() {
        val view = controls ?: return
        if (view.editing) return
        view.controlsVisible = prefs.showTouchControls && !(prefs.hideOnController && controllerPresent)
    }

    // ---------------------------------------------------------------- settings the game takes up at once

    fun applyRenderSettings() {
        try {
            NativeBridge.nativeSetRender(prefs.renderScale, prefs.anisotropy, prefs.smoothMotion)
        } catch (_: UnsatisfiedLinkError) {
        }
    }

    fun applyCameraSettings() {
        try {
            NativeBridge.nativeSetCameraInvert(prefs.invertCameraX, prefs.invertCameraY)
        } catch (_: UnsatisfiedLinkError) {
        }
    }

    /** Puts the saved controller mapping back (the native side keeps it only while running). */
    private fun restoreRemap() {
        try {
            for (index in 0 until GameMenu.REMAP_COUNT)
                prefs.remap(index)?.let { NativeBridge.nativeRemapSet(index, it) }
        } catch (_: UnsatisfiedLinkError) {
        }
    }

    /** Saves the mapping the menu just changed. */
    fun saveRemap() {
        prefs.clearRemap()
        try {
            for (index in 0 until GameMenu.REMAP_COUNT)
                prefs.setRemap(index, NativeBridge.nativeRemapCurrent(index))
        } catch (_: UnsatisfiedLinkError) {
        }
    }

    fun applyShowFps() {
        fpsLabel?.visibility = if (prefs.showFps) View.VISIBLE else View.GONE
    }

    private val fpsTick = object : Runnable {
        private val values = FloatArray(4)
        override fun run() {
            if (prefs.showFps) {
                try {
                    NativeBridge.nativeFpsRead(values)
                    fpsLabel?.text = "%.0f fps  (game %.0f%%, worst %.0f ms)".format(values[0], values[1], values[2])
                } catch (_: UnsatisfiedLinkError) {
                }
            }
            main.postDelayed(this, 500)
        }
    }

    /** Reports the device's thermal state to the session log's frame lines. */
    private fun registerThermalWatch() {
        val power = getSystemService(PowerManager::class.java)
        power.addThermalStatusListener(mainExecutor) { status ->
            try {
                NativeBridge.nativeSetThermal(status.coerceIn(0, 3))
            } catch (_: UnsatisfiedLinkError) {
            }
        }
    }

    // ---------------------------------------------------------------- data and saves

    fun backUpSaves() {
        if (!paths.card.isFile) {
            Toast.makeText(this, "No saves yet: save in the game first.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, SaveFiles(contentResolver, paths).suggestedBackupName())
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_BACKUP)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_BACKUP && resultCode == RESULT_OK && uri != null) {
            io.execute {
                val problem = SaveFiles(contentResolver, paths).backUpTo(uri)
                main.post {
                    Toast.makeText(this, problem ?: "Saves backed up (as of your last in-game save).",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Shares the newest session log (a copy: the running session is still writing it). */
    fun shareSessionLog() {
        val log = paths.latestLog()
        if (log == null) {
            Toast.makeText(this, "There is no session log yet.", Toast.LENGTH_LONG).show()
            return
        }
        io.execute {
            val copy = File(File(cacheDir, "shared").also { it.mkdirs() }, "BlueWake-session-log.txt")
            log.copyTo(copy, overwrite = true)
            val uri = FileProvider.getUriForFile(this, "$packageName.logs", copy)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "BlueWake session log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            main.post { startActivity(Intent.createChooser(send, "Share the session log")) }
        }
    }

    /** Ends the game. Every guest write to the memory card replaces the file, so nothing is left to flush. */
    fun quitGame() {
        finishAndRemoveTask()
        main.postDelayed({ Process.killProcess(Process.myPid()) }, 300)
    }

    companion object {
        private const val REQUEST_BACKUP = 4201
    }
}
