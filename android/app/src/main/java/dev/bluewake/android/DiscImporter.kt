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
    fun import(source: Uri, listener: Listener): Result {
        if (!DiscNative.available())
            return Result.Failed("This build has no native libraries, so it cannot read a disc.")
        val part = paths.discPart
        try {
            // Refuse the wrong file before copying 1.4 GB of it.
            headerProblem(source)?.let { return Result.Failed(it) }
            val total = sizeOf(source)
            val free = StatFs(paths.data.absolutePath).availableBytes
            val need = if (total > 0) total + (200L shl 20) else 1_700L shl 20
            if (free < need)
                return Result.Failed("There is not enough free storage: the disc image needs about " +
                    "${need shr 20} MB and ${free shr 20} MB are free.")
            listener.onProgress(0.0, "Copying the disc image")
            resolver.openInputStream(source).use { input ->
                if (input == null) return Result.Failed("The disc image could not be opened.")
                part.outputStream().buffered(1 shl 20).use { out ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        copied += n
                        if (total > 0 && copied - lastReport >= (8 shl 20)) {
                            lastReport = copied
                            // The copy is most of the wait; preparing the files is the rest.
                            listener.onProgress(0.85 * copied / total, "Copying the disc image")
                        }
                    }
                }
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
            return Result.Failed("The disc image could not be copied: ${e.message}. " +
                "It needs about 1.5 GB of free storage.")
        }
    }

    /** A GameCube image starts with the game id "GZLE01" and has the GameCube magic at 0x1C. */
    private fun headerProblem(uri: Uri): String? = try {
        val header = ByteArray(0x20)
        val read = resolver.openInputStream(uri)?.use { it.readUpTo(header) } ?: -1
        val magic = ((header[0x1C].toInt() and 0xFF) shl 24) or ((header[0x1D].toInt() and 0xFF) shl 16) or
            ((header[0x1E].toInt() and 0xFF) shl 8) or (header[0x1F].toInt() and 0xFF)
        when {
            read < 0 -> "The disc image could not be opened."
            read < header.size || magic != 0xC2339F3D.toInt() ->
                "That is not a GameCube disc image (an .iso or .gcm file). Compressed images " +
                    "(RVZ, GCZ, WIA, CISO) are not supported on Android yet."
            String(header, 0, 6, Charsets.US_ASCII) != "GZLE01" ->
                "This is not The Wind Waker, USA (GZLE01). Only that version is supported."
            else -> null
        }
    } catch (e: IOException) {
        "The disc image could not be read: ${e.message}"
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

    private fun sizeOf(uri: Uri): Long = try {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (_: Exception) {
        -1L
    }
}
