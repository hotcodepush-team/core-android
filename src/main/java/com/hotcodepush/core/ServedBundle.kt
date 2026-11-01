package com.hotcodepush.core

import java.io.File

/** Which bundle a persisted server base path names: one laid out under the projections directory, or none for the embedded bundle. */
object ServedBundle {
    fun resolveBundleId(persistedPath: String?, projectionsDirectory: File): String? {
        if (persistedPath.isNullOrEmpty()) return null
        val directory = File(persistedPath)
        return if (directory.parentFile == projectionsDirectory && directory.isDirectory) directory.name else null
    }
}

