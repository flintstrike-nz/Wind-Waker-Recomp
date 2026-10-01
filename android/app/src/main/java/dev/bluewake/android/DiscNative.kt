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

    /** {error ("" when fine), quest log 1, 2, 3} of a Dolphin .gci or raw card; see [QuestLog.parse]. */
    @JvmStatic external fun nativeDolphinQuestLogs(path: String): Array<String>

    /** {error, "1" if the card has saves else "0", quest log 1, 2, 3} of BlueWake's own card. */
    @JvmStatic external fun nativeCardQuestLogs(path: String): Array<String>

    /**
     * Writes to outPath the card made by putting quest log src (1-3) of the Dolphin save into slot dst
     * (1-3) of BlueWake's card (both ignored when the card has no saves yet: the whole file is added).
     * Neither input is changed. Null on success, else a sentence for the player.
     */
    @JvmStatic external fun nativeDolphinImport(dolphinPath: String, cardPath: String, src: Int, dst: Int, outPath: String): String?

    /** Writes outDir/main.dol and outDir/rels from the disc. Null on success, else a sentence for the player. */
    @JvmStatic external fun nativePrepare(path: String, outDir: String, listener: ProgressListener?): String?
}
