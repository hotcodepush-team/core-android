package com.hotcodepush.protocol

import java.io.File

/** Which bundle a persisted server base path names: one laid out under the projections directory, or none for the embedded bundle. */
object ServedBundle {
    fun resolveBundleId(persistedPath: String?, projectionsDirectory: File): String? {
        if (persistedPath.isNullOrEmpty()) return null
        val directory = File(persistedPath)
        return if (directory.parentFile == projectionsDirectory && directory.isDirectory) directory.name else null
    }
}

/**
 * Work that needs the WebView's local server, held until the WebView has loaded; the framework creates the server
 * after the plugins load, so a switch requested at start waits for the first page, and one requested later runs at once.
 */
class WebViewGate {
    private var isLoaded = false
    private var pending: (() -> Unit)? = null

    @Synchronized
    fun runWhenLoaded(action: () -> Unit) {
        if (isLoaded) action() else pending = action
    }

    @Synchronized
    fun markLoaded() {
        isLoaded = true
        pending?.invoke()
        pending = null
    }

    /** Drops the held work: the WebView it waited for is gone with its activity. */
    @Synchronized
    fun close() {
        pending = null
    }
}
