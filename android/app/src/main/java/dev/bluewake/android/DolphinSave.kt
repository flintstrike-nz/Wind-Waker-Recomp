package dev.bluewake.android

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.IOException

/** One of the Wind Waker save's three quest logs, as the shared importer describes it. */
class QuestLog(val empty: Boolean, val checksumOk: Boolean, val maxLife: Int, val rupees: Int, val name: String) {
    /** "Link · 6¾ hearts · 180 rupees". */
    fun summary(): String {
        val quarters = arrayOf("", "¼", "½", "¾")
        val hearts = "${maxLife / 4}${quarters[maxLife % 4]} heart${if (maxLife == 4) "" else "s"}"
        return "${name.ifEmpty { "No name" }} · $hearts · $rupees rupee${if (rupees == 1) "" else "s"}"
    }

    companion object {
        /** From the native "empty;checksumOk;maxLife;rupees;name". */
        fun parse(text: String): QuestLog {
            val p = text.split(';', limit = 5)
            return QuestLog(p.getOrNull(0) == "1", p.getOrNull(1) == "1", p.getOrNull(2)?.toIntOrNull() ?: 0,
                p.getOrNull(3)?.toIntOrNull() ?: 0, p.getOrNull(4) ?: "")
        }
    }
}

/**
 * Importing a save made by Dolphin (a .gci from Memory Card Manager › Export, or a raw memory card image)
 * into BlueWake's card: the player picks one of its quest logs and the quest log of BlueWake's it replaces.
 * The parsing, the checksums and the card edit are the shared C importer the iOS app uses
 * (apple/ios/src/dolphin_save_import.c); this is the Android side of the flow. Done from the launcher,
 * never while the game runs: the running game keeps its card in memory and writes all of it on its next save.
 */
class DolphinSaveImport(
    private val resolver: ContentResolver,
    private val paths: DataPaths,
    private val saves: SaveFiles,
    private val cacheDir: File,
) {
    /** What was found in the picked file and on BlueWake's card. [here] is empty when the card has no saves. */
    class Inspection(val file: File, val name: String, val theirs: List<QuestLog>, val here: List<QuestLog>) {
        val cardHasSaves get() = here.isNotEmpty()
    }

    private val staged get() = File(cacheDir, "dolphin-import.tmp")

    /** Copies the picked file (at most 32 MB: a raw Dolphin card is about 16) and reads it. Throws IOException with a sentence. */
    fun inspect(source: Uri, displayName: String): Inspection {
        if (!DiscNative.available()) throw IOException("This build has no native libraries, so it cannot read a save.")
        if (!paths.card.isFile)
            throw IOException("BlueWake makes its memory card when the game starts. Start the game once, " +
                "save, and then try again.")
        val file = staged
        try {
            resolver.openInputStream(source)?.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_FILE_BYTES) throw IOException("That file is too large to be a Dolphin save.")
                        out.write(buffer, 0, n)
                    }
                }
            } ?: throw IOException("The file could not be opened.")
            val theirs = DiscNative.nativeDolphinQuestLogs(file.absolutePath)
            if (theirs[0].isNotEmpty()) throw IOException(theirs[0])
            val card = DiscNative.nativeCardQuestLogs(paths.card.absolutePath)
            if (card[0].isNotEmpty()) throw IOException(card[0])
            return Inspection(file, displayName, (1..3).map { QuestLog.parse(theirs[it]) },
                if (card[1] == "1") (2..4).map { QuestLog.parse(card[it]) } else emptyList())
        } catch (e: IOException) {
            file.delete()
            throw e
        }
    }

    /**
     * Puts quest log [src] (1-3) of the inspected file into [dst] (1-3) of BlueWake's card, which is read
     * again here in case the game saved meanwhile; a copy of the current card is kept in Backups. Null on
     * success, else a sentence for the player. The staged file is removed either way.
     */
    fun import(inspection: Inspection, src: Int, dst: Int): String? {
        val result = File(paths.card.path + ".import")
        try {
            DiscNative.nativeDolphinImport(inspection.file.absolutePath, paths.card.absolutePath, src, dst,
                result.absolutePath)?.let { return it }
            // The result must be a sound container before it replaces anything.
            DiscNative.nativeCardCheck(result.absolutePath)?.let { return it }
            return saves.swapIn(result)
        } finally {
            result.delete()
            discard(inspection)
        }
    }

    fun discard(inspection: Inspection) {
        inspection.file.delete()
    }

    /**
     * Removes whatever an import left in the cache: the staged copy of the picked file and a half-made
     * result. For when the screen goes away with an import in flight, or came back after the process
     * was killed in one; an inspection that was never handed to the UI cannot be found to [discard].
     */
    fun discardStaged() {
        staged.delete()
        File(paths.card.path + ".import").delete()
    }

    companion object {
        private const val MAX_FILE_BYTES = 32L shl 20
    }
}
