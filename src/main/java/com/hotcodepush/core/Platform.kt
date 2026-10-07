package com.hotcodepush.core

import java.io.File

/** What the platform knows about the binary and the OS. */
data class DeviceFacts(
    val platform: String,
    val binaryVersion: String,
    val binaryBuild: String,
    val osVersion: String,
    val sdkVersion: String,
    val isDebugBuild: Boolean,
)

/** The framework's side of running a bundle: where a bundle is laid out and which one the WebView serves. */
interface BundleLoader {
    /** The directory a bundle is laid out in by path for the WebView. */
    fun projectionDirectory(bundleId: String): File

    /** Removes that directory, and with it the links that kept the bundle's files alive. */
    fun deleteProjection(bundleId: String)

    /** Records which bundle the framework loads at the next start; `null` is the embedded bundle. */
    fun persistServedBundle(bundleId: String?)

    /** Points the WebView at the bundle now and reloads it; `null` is the embedded bundle. */
    fun loadServedBundle(bundleId: String?)

    /** The bundle the WebView runs right now, `null` for the embedded bundle. */
    fun servedBundleId(): String?

    /** Whether the connection is metered or constrained, for the `unmetered` download strategy. */
    fun isConnectionMetered(): Boolean
}

/** The five events, named by what happened to the update; a cycle's start and end fire nothing. */
interface CoreListener {
    fun updateAvailable(event: UpdateAvailableEvent)
    fun updateDownloaded(event: UpdateDownloadedEvent)
    fun updateFailed(event: UpdateFailedEvent)
    fun downloadProgress(releaseId: String, downloadedBytes: Long, totalBytes: Long)
    fun rolledBack(event: RolledBackEvent)
}

fun interface ScheduledTask {
    fun cancel()
}

fun interface Scheduler {
    fun schedule(afterSeconds: Double, block: () -> Unit): ScheduledTask
}

fun interface Clock {
    fun now(): Long
}

/** The two programming mistakes the SDK reports with a plain error: nothing an app handles. */
class PlainException(message: String) : Exception(message)

object AttributeRules {
    private const val VALUE_MAX_CODE_POINTS = 256
    private val keyPattern = Regex("^[A-Za-z0-9_.-]{1,64}$")

    fun validate(key: String, value: String) {
        if (!isValidKey(key)) throw PlainException("An attribute key is an identifier of letters, digits, '_', '-' and '.', at most 64 characters: $key")
        validate(value)
    }

    /** The value rule alone, shared with the app's rollback reason. */
    fun validate(value: String) {
        if (!isValidValue(value)) throw PlainException("A value is at most $VALUE_MAX_CODE_POINTS Unicode code points without a control character")
    }

    fun isValidKey(key: String): Boolean = keyPattern.matches(key)

    /** At most 256 code points, counted neither in UTF-16 units nor in the characters a reader sees, and no control character, Unicode's `Cc`. */
    fun isValidValue(value: String): Boolean = value.codePointCount(0, value.length) <= VALUE_MAX_CODE_POINTS && value.none { it.isControlCharacter() }
}
