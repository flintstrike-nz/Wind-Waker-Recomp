package dev.bluewake.android

import android.app.AlertDialog
import android.content.DialogInterface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * The in-game menu: a stack of plain dialogs. The game is held (PAUSE_MENU)
 * for as long as any of them is open. Settings that the running game can take
 * up (resolution, Smooth Motion, camera, buttons, the controls) apply at once;
 * the mods and the picture shape apply at the next launch, because the game
 * module reads them once, at boot, and the menu says so.
 */
class GameMenu(private val activity: GameActivity, private val prefs: Prefs) {
    private var open = 0

    private class Item(val label: () -> String, val action: () -> Unit)

    // ---------------------------------------------------------------- plumbing

    private fun track(dialog: AlertDialog): AlertDialog {
        open++
        setPause(true)
        dialog.setOnDismissListener {
            open--
            // The next dialog of a submenu opens before this one finishes
            // closing, so the game is released only when none is left.
            activity.window.decorView.post { if (open == 0) setPause(false) }
        }
        dialog.show()
        return dialog
    }

    private fun setPause(on: Boolean) {
        try {
            NativeBridge.nativePauseSet(NativeBridge.PAUSE_MENU, on)
        } catch (_: UnsatisfiedLinkError) {
        }
    }

    private fun list(title: String, items: List<Item>, footer: String? = null, back: String = "Close") {
        val labels = items.map { it.label() }.toTypedArray()
        val builder = AlertDialog.Builder(activity).setTitle(title)
            .setItems(labels) { _: DialogInterface, which: Int -> items[which].action() }
            .setNegativeButton(back, null)
        val dialog = track(builder.create())
        if (footer != null) {
            // A note under the list; AlertDialog does not combine a message with items.
            dialog.setTitle("$title\n$footer")
        }
    }

    private fun choice(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        track(AlertDialog.Builder(activity).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, which ->
                onPick(which)
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .create())
    }

    private fun slider(title: String, current: Float, min: Float, max: Float, format: (Float) -> String,
                       onChange: (Float) -> Unit) {
        val d = activity.resources.displayMetrics.density
        val value = TextView(activity).apply {
            text = format(current)
            gravity = Gravity.CENTER
            textSize = 18f
        }
        val bar = SeekBar(activity).apply {
            this.max = 100
            progress = ((current - min) / (max - min) * 100f).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 100f
                    value.text = format(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
            addView(value, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(bar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        track(AlertDialog.Builder(activity).setTitle(title).setView(column)
            .setPositiveButton("OK", null).create())
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    // ---------------------------------------------------------------- menus

    fun show() {
        list("BlueWake", listOf(
            Item({ "Resume" }, {}),
            Item({ "Display…" }, { display() }),
            Item({ "Controls…" }, { controls() }),
            Item({ "Mods…" }, { mods() }),
            Item({ "Game data and saves…" }, { data() }),
            Item({ "Quit game…" }, { confirmQuit() }),
        ), back = "Resume")
    }

    private val resolutions = listOf("Screen's own pixels", "1x (480 lines, the original)", "2x (960 lines)",
        "3x (1440 lines)", "4x (1920 lines)")
    private val smooth = listOf("Off (the game's own 30 FPS)", "60 FPS", "120 FPS (needs a 120 Hz display)")
    private val filterings = listOf(1 to "The game's own", 4 to "4x anisotropic", 8 to "8x anisotropic", 16 to "16x anisotropic")

    private fun display() {
        list("Display", listOf(
            Item({ "Resolution: ${resolutions[prefs.renderScale.coerceIn(0, 4)]}" }, {
                choice("Resolution", resolutions, prefs.renderScale.coerceIn(0, 4)) {
                    prefs.renderScale = it; activity.applyRenderSettings(); display()
                }
            }),
            Item({ "Texture filtering: ${filterings.first { it.first == prefs.anisotropy }.second}" }, {
                choice("Texture filtering", filterings.map { it.second },
                    filterings.indexOfFirst { it.first == prefs.anisotropy }.coerceAtLeast(0)) {
                    prefs.anisotropy = filterings[it].first; activity.applyRenderSettings(); display()
                }
            }),
            Item({ "Smooth Motion: ${smooth[prefs.smoothMotion.coerceIn(0, 2)]}" }, {
                choice("Smooth Motion", smooth, prefs.smoothMotion.coerceIn(0, 2)) {
                    prefs.smoothMotion = it; activity.applyRenderSettings(); display()
                }
            }),
            Item({ "Picture: ${if (prefs.fillScreen) "fill the screen" else "original shape"} (next launch)" }, {
                prefs.fillScreen = !prefs.fillScreen; display()
            }),
            Item({ "Show FPS: ${onOff(prefs.showFps)}" }, {
                prefs.showFps = !prefs.showFps; activity.applyShowFps(); display()
            }),
        ), back = "Back")
    }

    private fun controls() {
        val placements = listOf("Automatic", "Below the picture", "Over the picture")
        list("Controls", listOf(
            Item({ "On-screen controls: ${onOff(prefs.showTouchControls)}" }, {
                prefs.showTouchControls = !prefs.showTouchControls; activity.applyControlsVisibility(); controls()
            }),
            Item({ "Hide when a controller is connected: ${onOff(prefs.hideOnController)}" }, {
                prefs.hideOnController = !prefs.hideOnController; activity.applyControlsVisibility(); controls()
            }),
            Item({ "Opacity: ${(prefs.controlOpacity * 100).toInt()}%" }, {
                slider("Opacity", prefs.controlOpacity, 0.15f, 1f, { "${(it * 100).toInt()}%" }) {
                    prefs.controlOpacity = it; activity.redrawControls()
                }
            }),
            Item({ "Size: ${(prefs.controlSize * 100).toInt()}%" }, {
                slider("Size", prefs.controlSize, 0.6f, 1.5f, { "${(it * 100).toInt()}%" }) {
                    prefs.controlSize = it; activity.relayoutControls()
                }
            }),
            Item({ "Placement: ${placements[prefs.placement.coerceIn(0, 2)]}" }, {
                choice("Where the controls go", placements, prefs.placement.coerceIn(0, 2)) {
                    prefs.placement = it; activity.applyLayout(); controls()
                }
            }),
            Item({ "Move the controls…" }, { activity.beginLayoutEditing() }),
            Item({ "Invert camera horizontally: ${onOff(prefs.invertCameraX)}" }, {
                prefs.invertCameraX = !prefs.invertCameraX; activity.applyCameraSettings(); controls()
            }),
            Item({ "Invert camera vertically: ${onOff(prefs.invertCameraY)}" }, {
                prefs.invertCameraY = !prefs.invertCameraY; activity.applyCameraSettings(); controls()
            }),
            Item({ "Controller buttons…" }, { buttons() }),
        ), back = "Back")
    }

    /** A physical controller's buttons: which of its buttons is the GameCube's A, B, X, Y, Z and Start. */
    private fun buttons() {
        val choices = generateSequence(0) { it + 1 }
            .map { NativeBridge.nativeRemapChoice(it) }.takeWhile { it != null }.filterNotNull().toList()
        val items = (0 until REMAP_COUNT).map { index ->
            val name = NativeBridge.nativeRemapName(index) ?: "?"
            Item({
                val current = NativeBridge.nativeRemapCurrent(index).toString()
                "GameCube $name: ${choices.firstOrNull { it[0] == current }?.get(1) ?: "?"}"
            }, {
                val current = NativeBridge.nativeRemapCurrent(index).toString()
                choice("GameCube $name", choices.map { it[1] }, choices.indexOfFirst { it[0] == current }) { picked ->
                    NativeBridge.nativeRemapSet(index, choices[picked][0].toInt())
                    activity.saveRemap()
                    buttons()
                }
            })
        } + Item({ "Reset to the defaults" }, {
            NativeBridge.nativeRemapReset(); activity.saveRemap(); buttons()
        })
        list("Controller buttons", items, back = "Back")
    }

    private fun mods() {
        val widescreen = listOf("Off (the original 4:3)", "Widescreen 16:9", "Widescreen 16:10")
        list("Mods", listOf(
            Item({ "Widescreen: ${widescreen[prefs.widescreen.coerceIn(0, 2)]}" }, {
                choice("Widescreen", widescreen, prefs.widescreen.coerceIn(0, 2)) { prefs.widescreen = it; mods() }
            }),
            Item({ "HD textures: ${onOff(prefs.hdTextures)}" }, { prefs.hdTextures = !prefs.hdTextures; mods() }),
            Item({ "Better Wind Waker: ${onOff(prefs.betterWindWaker)}" }, {
                prefs.betterWindWaker = !prefs.betterWindWaker; mods()
            }),
            Item({ "Better Wind Waker settings…" }, { betterWindWakerSettings() }),
        ), footer = "Mods apply the next time the game starts.", back = "Back")
    }

    private fun betterWindWakerSettings() {
        val items = ArrayList<Item>()
        var position = 0
        while (true) {
            val option = NativeBridge.nativeGameOption(position++) ?: break
            val name = option[0]
            val title = option[1]
            val default = option[2] == "1"
            items += Item({
                val on = prefs.gameOption(name) ?: default
                "$title: ${onOff(on)}"
            }, {
                val on = prefs.gameOption(name) ?: default
                prefs.setGameOption(name, !on)
                // Swift Sail and Brisk Sail are two tunings of one sail.
                val other = mapOf("swift_sail" to "brisk_sail", "brisk_sail" to "swift_sail")[name]
                if (!on && other != null) prefs.setGameOption(other, false)
                betterWindWakerSettings()
            })
        }
        if (items.isEmpty()) {
            track(AlertDialog.Builder(activity).setTitle("Better Wind Waker")
                .setMessage("Its settings are not available yet. Start the game with Better Wind Waker on once.")
                .setPositiveButton("OK", null).create())
            return
        }
        list("Better Wind Waker settings", items,
            footer = if (prefs.betterWindWaker) "Apply the next time the game starts."
            else "Turn Better Wind Waker on to use them.", back = "Back")
    }

    private fun data() {
        list("Game data and saves", listOf(
            Item({ "Back up saves…" }, { activity.backUpSaves() }),
            Item({ "Share the session log…" }, { activity.shareSessionLog() }),
        ), footer = "To restore saves or remove the disc image, use the launcher screen.", back = "Back")
    }

    private fun confirmQuit() {
        track(AlertDialog.Builder(activity).setTitle("Quit the game?")
            .setMessage("Progress since your last in-game save is lost.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Quit") { _, _ -> activity.quitGame() }
            .create())
    }

    companion object {
        const val REMAP_COUNT = 6
    }
}
