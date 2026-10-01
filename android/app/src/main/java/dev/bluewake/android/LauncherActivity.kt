package dev.bluewake.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

/**
 * The first screen: says what the game still needs (the translated game code
 * in the APK, the player's disc image, the files prepared from it), imports the
 * disc with the system file picker, and starts the game. Also backs up and
 * restores saves while no game is running.
 *
 * Built in code (no layout files) so it stays one readable file; it is a plain
 * column that is centered and capped in width, which suits both the Find N3's
 * inner screen (about 930 dp wide, nearly square) and its cover screen.
 */
class LauncherActivity : ComponentActivity() {
    private lateinit var paths: DataPaths
    private lateinit var saves: SaveFiles
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var codeRow: Row
    private lateinit var discRow: Row
    private lateinit var filesRow: Row
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var chooseDisc: Button
    private lateinit var play: Button
    private lateinit var backUp: Button
    private lateinit var restore: Button
    private lateinit var remove: Button
    private lateinit var installPack: Button
    private lateinit var removePack: Button
    private lateinit var packNote: TextView
    private var busy = false
    // Counting the installed textures walks the whole pack (thousands of files): done on the
    // worker after the pack changes, not on every refresh.
    private var textureCount = 0

    private val pickDisc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importDisc(uri)
    }
    private val pickBackupTarget = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            // The destination may be a cloud or slow provider: copy off the main thread.
            runBusy("Backing up the saves…") {
                val problem = saves.backUpTo(uri)
                Runnable {
                    message(if (problem == null) "Saves backed up" else "Could not back up",
                        problem ?: "The file holds your saves as of your last in-game save. " +
                            "Use Restore saves to bring them back.")
                }
            }
        }
    }
    private val pickRestoreSource = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore(uri)
    }

    private val pickTexturePack = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) installTexturePack(uri)
    }

    private class Row(val icon: TextView, val name: TextView, val detail: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paths = DataPaths(this)
        saves = SaveFiles(contentResolver, paths)
        setContentView(buildContent())
        refresh()
        recountTextures()
    }

    private fun recountTextures() {
        worker.execute {
            val n = TexturePack(contentResolver, paths).count()
            ui.post { textureCount = n; refresh() }
        }
    }

    /**
     * Runs [work] on the worker with the screen marked busy; it returns what to do on the main
     * thread afterwards (a dialog, say). Used for copies to and from other apps' storage.
     */
    private fun runBusy(what: String, work: () -> Runnable) {
        busy = true
        progress.isIndeterminate = true
        status.text = what
        refresh()
        worker.execute {
            val after = work()
            ui.post {
                busy = false
                progress.isIndeterminate = false
                status.text = ""
                refresh()
                after.run()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- state

    private fun refresh() {
        val composite = paths.hasComposite()
        val disc = paths.hasDisc()
        val prepared = paths.hasPreparedFiles()
        setRow(codeRow, if (composite) State.READY else State.MISSING, "Game code",
            if (composite) "Built into this app from your disc."
            else "This app was built without the game's code. Build the app from your own disc with " +
                "scripts/android/build.sh (docs/ANDROID.md); it cannot be made on the device.")
        setRow(discRow, if (busy) State.BUSY else if (disc) State.READY else State.MISSING, "Disc image",
            if (disc) "Imported. It stays on this device; the game reads it while you play."
            else "Choose your own disc image of The Legend of Zelda: The Wind Waker, GameCube, USA " +
                "(GZLE01), as an .iso or .gcm file. It is copied into the app (about 1.5 GB) and checked.")
        setRow(filesRow, if (busy) State.BUSY else if (prepared) State.READY else State.MISSING, "Game files",
            if (prepared) "Prepared from your disc." else "Made from the disc image when you import it.")
        chooseDisc.isEnabled = !busy
        chooseDisc.text = if (disc) "Choose another disc image…" else "Choose disc image…"
        play.isEnabled = !busy && paths.ready()
        backUp.isEnabled = !busy && saves.hasCard()
        restore.isEnabled = !busy
        remove.isEnabled = !busy && (disc || prepared)
        val textures = textureCount
        installPack.isEnabled = !busy
        removePack.isEnabled = !busy && textures > 0
        packNote.text = if (textures > 0) "$textures textures installed. Turn them on in the game's menu: Mods › HD textures."
        else "Dolphin-format packs for GZLE01 (PNG or DDS), such as Hypatia's HD pack. You supply the pack."
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private enum class State { MISSING, READY, BUSY }

    private fun setRow(row: Row, state: State, name: String, detail: String) {
        row.icon.text = when (state) { State.READY -> "✓"; State.BUSY -> "…"; State.MISSING -> "○" }
        row.icon.setTextColor(when (state) {
            State.READY -> Color.rgb(0x4C, 0xD9, 0x64)
            State.BUSY -> Color.rgb(0xFF, 0xCC, 0x00)
            State.MISSING -> Color.rgb(0xFF, 0x6B, 0x6B)
        })
        row.name.text = name
        row.detail.text = detail
    }

    // ---------------------------------------------------------------- actions

    private fun importDisc(uri: Uri) {
        busy = true
        progress.progress = 0
        status.text = "Starting…"
        refresh()
        worker.execute {
            val result = DiscImporter(contentResolver, paths).import(uri, object : DiscImporter.Listener {
                override fun onProgress(fraction: Double, stage: String) {
                    ui.post {
                        progress.progress = (fraction * 1000).toInt()
                        status.text = stage
                    }
                }
            })
            ui.post {
                busy = false
                status.text = ""
                refresh()
                if (result is DiscImporter.Result.Failed) message("Could not use that file", result.message)
            }
        }
    }

    private fun startGame() {
        if (!paths.ready()) return
        startActivity(Intent(this, GameActivity::class.java))
    }

    private fun installTexturePack(tree: Uri) {
        busy = true
        progress.isIndeterminate = true
        status.text = "Copying textures…"
        refresh()
        worker.execute {
            var problem: String? = null
            var copied = 0
            try {
                copied = TexturePack(contentResolver, paths).install(tree) { n ->
                    ui.post { status.text = "Copied $n textures…" }
                }
            } catch (e: java.io.IOException) {
                problem = e.message ?: "The textures could not be copied."
            }
            ui.post {
                busy = false
                progress.isIndeterminate = false
                status.text = ""
                refresh()
                recountTextures()
                if (problem != null) message("Could not install the pack", problem)
                else message("Texture pack installed", "$copied textures. Turn them on in the game's menu: Mods › HD textures.")
            }
        }
    }

    private fun confirmRemovePack() {
        AlertDialog.Builder(this)
            .setTitle("Remove the texture pack?")
            .setMessage("The installed textures are deleted from this app's storage.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                runBusy("Removing the textures…") {
                    TexturePack(contentResolver, paths).remove()
                    Runnable { recountTextures() }
                }
            }
            .show()
    }

    private fun confirmRestore(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Replace your saves?")
            .setMessage("Your current saves will be replaced by the chosen file. A copy of them is kept in " +
                "the app's Backups folder.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Replace saves") { _, _ ->
                runBusy("Restoring the saves…") {
                    val problem = saves.restoreFrom(uri)
                    Runnable {
                        message(if (problem == null) "Saves restored" else "Saves not changed",
                            problem ?: "They will load the next time you start the game.")
                    }
                }
            }
            .show()
    }

    private fun confirmRemove() {
        AlertDialog.Builder(this)
            .setTitle("Remove the disc image?")
            .setMessage("This removes the imported disc image and the game files prepared from it, " +
                "about 1.5 GB. Your saves and settings stay. You will need to import the disc again to play.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                paths.removeDisc()
                refresh()
            }
            .show()
    }

    private fun message(title: String, text: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }

    // ---------------------------------------------------------------- views

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        if (primary) setBackgroundColor(Color.rgb(0x0A, 0x84, 0xFF)).also { setTextColor(Color.WHITE) }
        minHeight = dp(48f).toInt()
    }

    private fun row(): Pair<Row, View> {
        val icon = text(20f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val name = text(17f, Color.WHITE, true)
        val detail = text(14f, Color.argb(168, 255, 255, 255))
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(detail)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
            addView(icon, LinearLayout.LayoutParams(dp(32f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return Row(icon, name, detail) to container
    }

    private fun buildContent(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f).toInt(), dp(24f).toInt(), dp(24f).toInt(), dp(24f).toInt())
        }
        column.addView(text(28f, Color.WHITE, true).apply { text = "BlueWake" })
        column.addView(text(15f, Color.argb(190, 255, 255, 255)).apply {
            text = "The Legend of Zelda: The Wind Waker, on your own disc"
            setPadding(0, 0, 0, dp(16f).toInt())
        })
        val (code, codeView) = row(); codeRow = code; column.addView(codeView)
        val (disc, discView) = row(); discRow = disc; column.addView(discView)
        val (files, filesView) = row(); filesRow = files; column.addView(filesView)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        column.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f).toInt() })
        status = text(13f, Color.argb(190, 255, 255, 255))
        column.addView(status)

        chooseDisc = button("Choose disc image…", false) { pickDisc.launch(arrayOf("*/*")) }
        play = button("Play", true) { startGame() }
        backUp = button("Back up saves…", false) { pickBackupTarget.launch(saves.suggestedBackupName()) }
        restore = button("Restore saves…", false) { pickRestoreSource.launch(arrayOf("*/*")) }
        remove = button("Remove disc image and game files…", false) { confirmRemove() }
        val spacing = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f).toInt() }
        column.addView(chooseDisc, spacing)
        column.addView(play, spacing)
        column.addView(text(14f, Color.argb(190, 255, 255, 255)).apply {
            text = "Game data and saves"
            setPadding(0, dp(24f).toInt(), 0, 0)
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(backUp, spacing)
        column.addView(restore, spacing)
        column.addView(remove, spacing)
        installPack = button("Install an HD texture pack…", false) { pickTexturePack.launch(null) }
        removePack = button("Remove the texture pack…", false) { confirmRemovePack() }
        column.addView(text(14f, Color.argb(190, 255, 255, 255)).apply {
            text = "HD textures"
            setPadding(0, dp(24f).toInt(), 0, 0)
            setTypeface(typeface, Typeface.BOLD)
        })
        packNote = text(13f, Color.argb(170, 255, 255, 255))
        column.addView(packNote)
        column.addView(installPack, spacing)
        column.addView(removePack, spacing)
        column.addView(text(12f, Color.argb(150, 255, 255, 255)).apply {
            text = "No disc image, game files or saves are part of this app: it asks for your own legally " +
                "obtained disc. Nothing leaves this device."
            setPadding(0, dp(24f).toInt(), 0, 0)
        })

        // A centered column no wider than 640 dp, inside a scroll view that
        // keeps clear of the system bars and the camera cutout.
        val frame = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        frame.addView(column, LinearLayout.LayoutParams(dp(640f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(this).apply { addView(frame) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return scroll
    }
}
