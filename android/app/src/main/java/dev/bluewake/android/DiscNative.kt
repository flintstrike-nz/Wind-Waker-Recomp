package dev.bluewake.android

/** The disc importer (apple/ios/src/disc_import.c), in libbwdisc.so. */
object DiscNative {
    interface ProgressListener {
        fun onProgress(fraction: Double, stage: String)
    }

    private var loaded = false

    /** Loads the library; false if this build has no native libraries. */
    @Synchronized
    fun available(): Boolean {
        if (!loaded) {
            loaded = try {
                System.loadLibrary("bwdisc")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }
        }
        return loaded
    }

    /** Null when the file is a GameCube disc image of The Wind Waker (USA), else a sentence for the player. */
    @JvmStatic external fun nativeCheck(path: String): String?

    /** Null when the file is a sound BlueWake memory card container, else a sentence for the player. */
    @JvmStatic external fun nativeCardCheck(path: String): String?

    /** Writes outDir/main.dol and outDir/rels from the disc. Null on success, else a sentence for the player. */
    @JvmStatic external fun nativePrepare(path: String, outDir: String, listener: ProgressListener?): String?
}
