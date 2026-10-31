package com.hotcodepush.core

import java.io.File

internal class BspatchException(val failure: Bspatch.Failure) : Exception("The patch did not apply: $failure")

// FreeBSD's bspatch, the native library built from `src/main/cpp`.
internal object Bspatch {
    enum class Failure { CORRUPT_PATCH, IO_ERROR, OUT_OF_MEMORY }

    init {
        System.loadLibrary("hotcodepush_bspatch")
    }

    // A control triple that reaches outside the old file adds nothing there, as bsdiff 4.3 defined it.
    // So only the hash of the result proves the patch was the right one.

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
