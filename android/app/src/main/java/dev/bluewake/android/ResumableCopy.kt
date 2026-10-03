package dev.bluewake.android

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copies a stream that can break part-way. The disc image comes from another app's storage (a file
 * manager's document provider, a cloud drive that fetches on demand, an SD card), and a provider
 * that fails after a gigabyte should not cost the player the whole copy: on a read error the source is
 * opened again at the byte where it stopped and the copy goes on. Only reads from the source are
 * retried; a failed write to our own storage is a real error and is thrown at once.
 */
object ResumableCopy {
    /**
     * Copies [first] to [out] until the end and returns the number of source bytes consumed, [startAt]
     * included ([first] is already [startAt] bytes in). [reopen] gives a new stream at the start of the
     * source, or null when it cannot be opened. After [retries] read errors in a row without progress
     * the last error is thrown, saying how far the copy got. Closes whatever stream it ends with,
     * [first] included. An interrupted thread ends the copy with an IOException("cancelled").
     */
    fun copy(
        first: InputStream,
        reopen: () -> InputStream?,
        out: OutputStream,
        startAt: Long = 0L,
        retries: Int = 3,
        retryPauseMs: Long = 500L,
        onProgress: (Long) -> Unit = {},
    ): Long {
        var copied = startAt
        var input: InputStream? = first
        var failures = 0
        val buffer = ByteArray(1 shl 20)
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw IOException("cancelled")
                val n = try {
                    val stream = input ?: reopenAt(reopen, copied)
                    input = stream
                    stream.read(buffer)
                } catch (e: IOException) {
                    input.closeQuietly()
                    input = null
                    if (++failures > retries) throw IOException("${e.message} (after ${copied shr 20} MB)", e)
                    try {
                        Thread.sleep(retryPauseMs * failures)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("cancelled")
                    }
                    continue
                }
                if (n < 0) return copied
                out.write(buffer, 0, n)
                copied += n
                failures = 0
                onProgress(copied)
            }
        } finally {
            input.closeQuietly()
        }
    }

    private fun reopenAt(reopen: () -> InputStream?, offset: Long): InputStream {
        val stream = reopen() ?: throw IOException("The file could not be opened again")
        try {
            var left = offset
            while (left > 0) {
                val skipped = stream.skip(left)
                if (skipped > 0) {
                    left -= skipped
                } else {
                    // skip() may return 0 without being at the end: read one byte to tell.
                    if (stream.read() < 0) throw IOException("The file ended early when it was opened again")
                    left--
                }
            }
        } catch (e: IOException) {
            stream.closeQuietly()
            throw e
        }
        return stream
    }

    private fun InputStream?.closeQuietly() {
        try {
            this?.close()
        } catch (_: IOException) {
        }
    }
}
