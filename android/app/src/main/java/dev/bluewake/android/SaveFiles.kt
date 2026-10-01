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
/** The largest card container accepted on restore (a real card is far smaller). */
private const val MAX_CARD_BYTES = 64L shl 20

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
        val staged = File(paths.card.path + ".restore")
        try {
            // The picked document is another app's; copy at most a card's worth of it.
            val copied = resolver.openInputStream(source)?.use { input ->
                staged.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_CARD_BYTES) return "That file is too large to be a BlueWake save file."
                        out.write(buffer, 0, n)
                    }
                    true
                }
            } ?: return "The save file could not be opened."
            check(copied)
            // A BlueWake card container (GXRuntime memory_card.c): the tag, then the version, block
            // size, length and checksum are all checked before the current card is touched.
            val magic = ByteArray(8)
            val read = staged.inputStream().use { it.read(magic) }
            if (read != 8 || String(magic, Charsets.US_ASCII) != "DOLCARD1") {
                return "That is not a BlueWake save file. Pick a .card file made by Back Up Saves " +
                    "or copied from BlueWake's data folder."
            }
            if (DiscNative.available()) {
                DiscNative.nativeCardCheck(staged.absolutePath)?.let { return it }
            }
            return swapIn(staged)
        } catch (e: IOException) {
            return "Your saves were not changed. ${e.message}"
        } finally {
            // Whatever happened, no staged copy is left behind (after a rename there is none).
            staged.delete()
        }
    }

    /**
     * Puts a card file in place of the current one, after keeping a copy of the current one in Backups.
     * [staged] is used up either way. Null on success, else a sentence for the player; the card is
     * unchanged when it fails.
     */
    fun swapIn(staged: File): String? {
        try {
            backUpCard()
            return replaceCard(staged)
        } catch (e: IOException) {
            return "Your saves were not changed. ${e.message}"
        } finally {
            staged.delete()
        }
    }

    /** Copies the current card into Backups (the slow part of a swap); null when there is no card yet. */
    @Throws(IOException::class)
    fun backUpCard(): File? {
        if (!paths.card.isFile) return null
        paths.backups.mkdirs()
        // The name is reserved atomically, so two backups in the same second never share a file and a
        // caller that removes its own backup can never remove somebody else's.
        val stem = "GZLE01-${stamp("yyyyMMdd-HHmmss")}"
        var backup = File(paths.backups, "$stem.card")
        var n = 1
        while (!backup.createNewFile()) backup = File(paths.backups, "$stem-${n++}.card")
        try {
            paths.card.copyTo(backup, overwrite = true)
        } catch (e: IOException) {
            backup.delete()
            throw e
        }
        return backup
    }

    /** The instant half of a swap: renames [staged] over the card. Null on success, else a sentence. */
    fun replaceCard(staged: File): String? =
        if (staged.renameTo(paths.card)) null else "The save file could not be put in place."

    private fun stamp(pattern: String) = SimpleDateFormat(pattern, Locale.US).format(Date())
}
