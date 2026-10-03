package dev.bluewake.android

import android.content.ContentResolver
import android.net.Uri
import android.os.StatFs
import java.io.IOException
import java.io.InputStream

/**
 * Brings the player's own disc image into the app's private storage and
 * prepares the two inputs the host reads besides the disc itself: main.dol and
 * rels/ (libbwdisc, the same importer the iOS app uses).
 *
 * The disc is checked as a GameCube image of The Wind Waker, USA (GZLE01,
 * revision 0), before anything is kept; other discs are refused.
 */
class DiscImporter(private val resolver: ContentResolver, private val paths: DataPaths) {
    interface Listener {
        fun onProgress(fraction: Double, stage: String)
    }

    sealed class Result {
        object Ok : Result()
        data class Failed(val message: String) : Result()
    }

    /** Runs on the calling thread (call it off the main thread). */
    fun import(uri: Uri, listener: Listener): Result {
        if (!DiscNative.available())
            return Result.Failed("This build has no native libraries, so it cannot read a disc.")
        val part = paths.discPart
        try {
            // One open serves the size, the header and the copy: some document providers fail on the
            // second open of a large file, and the header was already read through the first.
            val source = open(uri) ?: return Result.Failed("The disc image could not be opened.")
            var handedOver = false
            try {
                val header = ByteArray(0x20)
                val read = source.input.readUpTo(header)
                // Refuse the wrong file before copying 1.4 GB of it.
                headerProblem(header, read)?.let { return Result.Failed(it) }
                val total = source.total
                val free = StatFs(paths.data.absolutePath).availableBytes
                val need = if (total > 0) total + (200L shl 20) else 1_700L shl 20
                if (free < need)
                    return Result.Failed("There is not enough free storage: the disc image needs about " +
                        "${need shr 20} MB and ${free shr 20} MB are free.")
                listener.onProgress(0.0, "Copying the disc image")
                handedOver = true
                val copied = part.outputStream().buffered(1 shl 20).use { out ->
                    out.write(header)
                    var lastReport = 0L
                    // A provider that breaks part-way is opened again where it stopped.
                    ResumableCopy.copy(source.input, { open(uri)?.input }, out, startAt = header.size.toLong()) { done ->
                        if (total > 0 && done - lastReport >= (8 shl 20)) {
                            lastReport = done
                            // The copy is most of the wait; preparing the files is the rest.
                            listener.onProgress(0.85 * done / total, "Copying the disc image")
                        }
                    }
                }
                if (total > 0 && copied < total) {
                    part.delete()
                    return Result.Failed("The disc image ended early: ${copied shr 20} MB of ${total shr 20} MB " +
                        "could be read. Copy the file to the phone again and choose it from there.")
                }
            } finally {
                if (!handedOver) source.input.close()
            }
            listener.onProgress(0.85, "Checking the disc image")
            DiscNative.nativeCheck(part.absolutePath)?.let {
                part.delete()
                return Result.Failed(it)
            }
            // main.dol and rels/ replace the old ones only once all of them are written.
            val failure = DiscNative.nativePrepare(part.absolutePath, paths.data.absolutePath,
                object : DiscNative.ProgressListener {
                    override fun onProgress(fraction: Double, stage: String) =
                        listener.onProgress(0.85 + 0.15 * fraction, stage)
                })
            if (failure != null) {
                part.delete()
                return Result.Failed(failure)
            }
            paths.disc.delete()
            if (!part.renameTo(paths.disc)) throw IOException("could not move the disc image into place")
            listener.onProgress(1.0, "Ready")
            return Result.Ok
        } catch (e: IOException) {
            part.delete()
            val why = (e.message ?: e.javaClass.simpleName).trimEnd('.', ' ')
            return Result.Failed("The disc image could not be copied: $why." +
                if (why.contains("space", ignoreCase = true)) " It needs about 1.5 GB of free storage."
                else " If it keeps failing, copy the file into the phone's Download folder and choose it from there.")
        }
    }

    private class Source(val total: Long, val input: InputStream)

    /** Opens the picked file once for its length and its bytes; null when there is no such file. */
    private fun open(uri: Uri): Source? {
        try {
            resolver.openAssetFileDescriptor(uri, "r")?.let { afd ->
                return Source(afd.length.takeIf { it >= 0 } ?: -1L, afd.createInputStream())
            }
        } catch (_: Exception) {
            // Fall through: openInputStream reports the provider's own error if there is one.
        }
        return resolver.openInputStream(uri)?.let { Source(-1L, it) }
    }

    /** A GameCube image starts with the game id "GZLE01" and has the GameCube magic at 0x1C. [read] bytes of [header] are valid. */
    private fun headerProblem(header: ByteArray, read: Int): String? {
        val magic = ((header[0x1C].toInt() and 0xFF) shl 24) or ((header[0x1D].toInt() and 0xFF) shl 16) or
            ((header[0x1E].toInt() and 0xFF) shl 8) or (header[0x1F].toInt() and 0xFF)
        return when {
            read < header.size || magic != 0xC2339F3D.toInt() ->
                "That is not a GameCube disc image (an .iso or .gcm file). Compressed images " +
                    "(RVZ, GCZ, WIA, CISO) are not supported on Android yet."
            String(header, 0, 6, Charsets.US_ASCII) != "GZLE01" ->
                "This is not The Wind Waker, USA (GZLE01). Only that version is supported."
            else -> null
        }
    }

    /** Reads until the buffer is full or the stream ends; the count read. (readNBytes needs Android 13.) */
    private fun InputStream.readUpTo(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
