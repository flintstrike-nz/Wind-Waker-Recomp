package dev.bluewake.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException

/**
 * Dolphin-format HD texture packs for GZLE01: PNG and DDS files, in the folder
 * layout Dolphin uses (Load/Textures/GZLE01), which the renderer searches.
 * The player supplies the pack (this app never includes one: AGENTS.md); it is
 * copied from a folder picked with the system file picker.
 */
class TexturePack(private val resolver: ContentResolver, private val paths: DataPaths) {
    /** The textures installed. */
    fun count(): Int = paths.texturePack.walkTopDown().count { it.isFile }

    fun remove() {
        paths.texturePack.deleteRecursively()
        paths.texturePack.mkdirs()
    }

    /**
     * Copies every PNG and DDS texture under the picked folder, keeping its
     * subfolders. Returns the number copied, or throws IOException with a
     * sentence for the player. Runs on the calling thread.
     */
    fun install(tree: Uri, onProgress: (Int) -> Unit): Int {
        val destination = paths.texturePack.also { it.mkdirs() }
        val root = destination.canonicalFile
        var copied = 0
        fun walk(documentId: String, directory: File) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE)
            val entries = ArrayList<Triple<String, String, String>>()
            resolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext())
                    entries += Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
            } ?: throw IOException("The folder could not be read.")
            for ((id, name, mime) in entries) {
                // A name from another app's storage is never trusted as a path.
                if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) continue
                val target = File(directory, name)
                if (!target.canonicalPath.startsWith(root.path + File.separator)) continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    target.mkdirs()
                    walk(id, target)
                } else if (name.endsWith(".png", true) || name.endsWith(".dds", true)) {
                    val source = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    resolver.openInputStream(source)?.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    } ?: throw IOException("$name could not be read.")
                    copied++
                    if (copied % 50 == 0) onProgress(copied)
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), destination)
        if (copied == 0)
            throw IOException("That folder has no PNG or DDS textures. Pick the folder that holds the pack's " +
                "textures (a Dolphin pack's GZLE01 folder).")
        return copied
    }
}
