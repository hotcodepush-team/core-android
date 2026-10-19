package com.hotcodepush.core

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

/** What the platform knows about the binary and the OS. */
data class DeviceFacts(
    val platform: String,
    val binaryVersion: String,
    val binaryBuild: String,
    val osVersion: String,
    val sdkVersion: String,
    val isDebugBuild: Boolean,
) {
    companion object {
        /** The running binary's facts: its version and build from the package manager, the OS version, and a debug build when the app is debuggable. */
        fun current(context: Context, sdkVersion: String): DeviceFacts {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            return DeviceFacts(
                platform = "android",
                binaryVersion = info.versionName ?: "",
                binaryBuild = versionCode.toString(),
                osVersion = Build.VERSION.RELEASE ?: "",
                sdkVersion = sdkVersion,
                isDebugBuild = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            )
        }
    }
}

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
    fun downloadProgress(event: DownloadProgressEvent)
    fun updateRolledBack(event: UpdateRolledBackEvent)
}

fun interface ScheduledTask {
    fun cancel()
}

fun interface Scheduler {
    fun schedule(afterSeconds: Double, block: () -> Unit): ScheduledTask
}

/** The core's timers on the main looper; `cancelAll()` drops every one still pending, for a host that goes away. */
class HandlerScheduler : Scheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(afterSeconds: Double, block: () -> Unit): ScheduledTask {
        val runnable = Runnable(block)
        handler.postDelayed(runnable, (afterSeconds * 1000).toLong())
        return ScheduledTask { handler.removeCallbacks(runnable) }
    }

    fun cancelAll() = handler.removeCallbacksAndMessages(null)
}

fun interface Clock {
    fun now(): Long
}

/** Whether the process runs in the foreground: the system starts one in the background for a push, a job or a headless task. */
fun interface ProcessState {
    fun isInForeground(): Boolean
}

/** The importance the activity manager gives this process, read as React Native's host reads it. */
object ActivityManagerProcessState : ProcessState {
    override fun isInForeground(): Boolean {
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        return process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}

/** Whether the connection is metered, for a loader's `isConnectionMetered()`: the active network as the connectivity manager reports it. */
class NetworkMonitor(private val context: Context) {
    fun isConnectionMetered(): Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered ?: false
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
