package com.hotcodepush.core

import java.io.File

internal class BspatchException(val failure: Bspatch.Failure) : Exception("The patch did not apply: $failure")

/** FreeBSD's bspatch, the native library built from `src/main/cpp`: a BSDIFF40 patch turns an old file into a new one. */
internal object Bspatch {
    /** The statuses of `hotcodepush_bspatch()` other than success. */
    enum class Failure { CORRUPT_PATCH, IO_ERROR, OUT_OF_MEMORY }

    init {
        System.loadLibrary("hotcodepush_bspatch")
    }

    /**
     * Writes the new file, never larger than `maximumBytes`, or throws and leaves no file. A control triple that reaches
     * outside the old file adds nothing there, as bsdiff 4.3 defined it, so only the result's hash says the patch was the right one.
     */
    fun apply(patch: File, old: File, new: File, maximumBytes: Long) {
        when (applyPatch(old.path, new.path, patch.path, maximumBytes)) {
            0 -> return
            1 -> throw BspatchException(Failure.CORRUPT_PATCH)
            3 -> throw BspatchException(Failure.OUT_OF_MEMORY)
            else -> throw BspatchException(Failure.IO_ERROR)
        }
    }

    @JvmStatic
    private external fun applyPatch(oldPath: String, newPath: String, patchPath: String, maximumBytes: Long): Int
}
