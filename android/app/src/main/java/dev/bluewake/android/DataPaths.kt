package dev.bluewake.android

import android.content.Context
import java.io.File

/**
 * Where the player's data lives. All of it is private to the app, user-provided
 * and never bundled (AGENTS.md: a release never carries a disc, game files,
 * saves or settings). The native side finds the same files through
 * BLUEWAKE_DATA_DIR (android/native/src/android_entry.cpp).
 */
class DataPaths(private val context: Context) {
    val data: File = File(context.filesDir, "BlueWake").also { it.mkdirs() }
    val disc: File get() = File(data, "GZLE01.iso")
    val discPart: File get() = File(data, "GZLE01.iso.part")
    val dol: File get() = File(data, "main.dol")
    val rels: File get() = File(data, "rels")
    val card: File get() = File(data, "GZLE01.card")
    val sram: File get() = File(data, "sram.bin")
    val logs: File get() = File(data, "logs")
    val backups: File get() = File(data, "Backups")
    val texturePack: File get() = File(data, "Load/Textures/GZLE01")

    /** The translated game, built on a PC from the same disc and packaged in the APK. */
    val composite: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libgGZLE01_recomp.so")

    fun hasComposite() = composite.isFile
    fun hasDisc() = disc.isFile
    fun hasPreparedFiles(): Boolean {
        val relFiles = rels.list { _, name -> name.endsWith(".rel") }
        return dol.isFile && relFiles != null && relFiles.isNotEmpty()
    }

    fun ready() = hasComposite() && hasDisc() && hasPreparedFiles()

    /** The newest session log, or null. */
    fun latestLog(): File? =
        logs.listFiles { f -> f.name.startsWith("session-") }?.maxByOrNull { it.name }

    /** Removes the imported disc and everything extracted from it; saves and settings stay. */
    fun removeDisc() {
        disc.delete()
        discPart.delete()
        dol.delete()
        rels.deleteRecursively()
    }
}
