package dev.bluewake.android

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backing up and restoring the memory card (GZLE01.card), through the system
 * file picker, so the player can keep their saves outside the app.
 *
 * The card file holds the saves as of the last in-game save. Restoring is done
 * from the launcher, never while the game runs: the running game keeps the old
 * card in memory and writes all of it on its next save.
 */
class SaveFiles(private val resolver: ContentResolver, private val paths: DataPaths) {
    fun suggestedBackupName(): String = "BlueWake-saves-${stamp("yyyy-MM-dd")}.card"

    fun hasCard() = paths.card.isFile

    /** Copies the card to the picked document. Null on success, else a sentence for the player. */
    fun backUpTo(target: Uri): String? = try {
        if (!hasCard()) "BlueWake has no memory card yet. Save in the game first."
        else {
            resolver.openOutputStream(target, "wt")?.use { out -> paths.card.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("the destination could not be opened")
            null
        }
    } catch (e: IOException) {
        "The saves could not be backed up: ${e.message}"
    }

    /**
     * Replaces the card with the picked file after keeping a copy of the current one
     * in Backups. Null on success, else a sentence for the player; the card is
     * unchanged when it fails.
     */
    fun restoreFrom(source: Uri): String? {
        try {
            val staged = File(paths.card.path + ".restore")
            resolver.openInputStream(source)?.use { input -> staged.outputStream().use { input.copyTo(it) } }
                ?: return "The save file could not be opened."
            // BlueWake's card files start with this tag (GXRuntime memory_card.c).
            val magic = ByteArray(8)
            val read = staged.inputStream().use { it.read(magic) }
            if (read != 8 || String(magic, Charsets.US_ASCII) != "DOLCARD1") {
                staged.delete()
                return "That is not a BlueWake save file. Pick a .card file made by Back Up Saves " +
                    "or copied from BlueWake's data folder."
            }
            if (paths.card.isFile) {
                paths.backups.mkdirs()
                val backup = File(paths.backups, "GZLE01-${stamp("yyyyMMdd-HHmmss")}.card")
                paths.card.copyTo(backup, overwrite = true)
            }
            if (!staged.renameTo(paths.card)) {
                staged.delete()
                return "The save file could not be put in place."
            }
            return null
        } catch (e: IOException) {
            return "Your saves were not changed. ${e.message}"
        }
    }

    private fun stamp(pattern: String) = SimpleDateFormat(pattern, Locale.US).format(Date())
}
