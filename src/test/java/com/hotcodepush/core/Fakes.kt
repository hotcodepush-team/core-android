package com.hotcodepush.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

class InMemoryStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    val integers = mutableMapOf<String, Int>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }

    override fun getInt(key: String): Int? = integers[key]

    override fun putInt(key: String, value: Int?) {
        if (value == null) integers.remove(key) else integers[key] = value
    }
}

class FakeLoader(private val root: File) : BundleLoader {
    var persisted: String? = null
    var hasPersisted = false
    val loaded = mutableListOf<String?>()
    var served: String? = null
    var isMetered = false

    /** Thrown from persistServedBundle and loadServedBundle, as a framework's loader with a bug throws. */
    var persistFailure: Throwable? = null
    var loadFailure: Throwable? = null

    /** Holds persistServedBundle until it opens, as a slow store would. */
    var persistLatch: CountDownLatch? = null

    override fun projectionDirectory(bundleId: String): File = File(File(root, "www"), bundleId)

    override fun deleteProjection(bundleId: String) {
        projectionDirectory(bundleId).deleteRecursively()
    }

    override fun persistServedBundle(bundleId: String?) {
        persistLatch?.await()
        persistFailure?.let { throw it }
        persisted = bundleId
        hasPersisted = true
    }

    override fun loadServedBundle(bundleId: String?) {
        loadFailure?.let { throw it }
        loaded += bundleId
        served = bundleId
    }

    override fun servedBundleId(): String? = served

    override fun isConnectionMetered(): Boolean = isMetered
}

class FakeListener : CoreListener {
    val available = mutableListOf<UpdateAvailableEvent>()
    val downloaded = mutableListOf<UpdateDownloadedEvent>()
    val failed = mutableListOf<UpdateFailedEvent>()
    val rolledBack = mutableListOf<RolledBackEvent>()

    /** Thrown from the listener, as an SDK's listener with a bug throws. */
    var updateAvailableFailure: Throwable? = null
    var updateFailedFailure: Throwable? = null

    override fun updateAvailable(event: UpdateAvailableEvent) {
        updateAvailableFailure?.let { throw it }
        available += event
    }

    override fun updateDownloaded(event: UpdateDownloadedEvent) { downloaded += event }

    override fun updateFailed(event: UpdateFailedEvent) {
        updateFailedFailure?.let { throw it }
        failed += event
    }

    override fun downloadProgress(releaseId: String, downloadedBytes: Long, totalBytes: Long) {}
    override fun rolledBack(event: RolledBackEvent) { rolledBack += event }
}

class ManualScheduler : Scheduler {
    class Task(val seconds: Double, val block: () -> Unit) : ScheduledTask {
        var isCancelled = false
        override fun cancel() { isCancelled = true }
    }

    val tasks = mutableListOf<Task>()

    override fun schedule(afterSeconds: Double, block: () -> Unit): ScheduledTask = Task(afterSeconds, block).also { tasks += it }

    /** Fires every pending task that is still alive, the way time would. */
    fun fire() {
        val pending = tasks.toList()
        tasks.clear()
        pending.filter { !it.isCancelled }.forEach { it.block() }
    }
}

class FixedClock(var now: Long) : Clock {
    override fun now(): Long = now
}

/** A core over fakes, in a fresh temporary directory. */
class Harness(configuration: Configuration = Fixture.configuration(), isDebugBuild: Boolean = false, private val device: DeviceFacts = DeviceFacts("android", "2.4.1", "57", "14", "0.0.0", isDebugBuild)) {
    val root: File = Files.createTempDirectory("hotcodepush-tests").toFile()
    val store = InMemoryStore()
    val http = FakeHttpClient()
    val loader = FakeLoader(root)
    val listener = FakeListener()
    val scheduler = ManualScheduler()
    val embedded = InMemoryEmbeddedBundle().apply { files[Hashing.sha256Hex(Fixture.embeddedIndexHtml)] = Fixture.embeddedIndexHtml }
    val clock = FixedClock(Fixture.BUILT_AT + 3_600_000)
    val files = FileStore(File(root, "store"))
    /** What escaped the core's own tasks to the scope: on a device, the process's crash. */
    val uncaught = mutableListOf<Throwable>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, failure -> uncaught += failure })

    /** What the process's importance says at the next start: `false` for one the system started without a foreground activity. */
    var isProcessInForeground = true
    var core: Core = build(configuration)

    private fun build(configuration: Configuration, scope: CoroutineScope = this.scope) =
        Core(configuration, device, store, files, embedded, http, loader, listener, scheduler, clock, scope, File(root, "tmp")) { isProcessInForeground }

    /** A second core over the same store and files: the next start of the app, its tasks on the scope given. */
    fun restart(configuration: Configuration = Fixture.configuration(), scope: CoroutineScope = this.scope) {
        core = build(configuration, scope)
    }

    /** The events endpoint answering every batch with the same server time. */
    fun acknowledgeEvents(reportedAt: String = "2023-11-14T23:00:00.000Z") {
        http.stubJson(Fixture.eventsUrl(), JSONObject().put("reportedAt", reportedAt), status = 202)
    }

    fun publish(releases: List<Fixture.Published>, sequence: Long, revoked: List<String> = emptyList(), isPaused: Boolean = false, cappedAt: Long? = null, etag: String = "\"e1\"") {
        http.stubJson(Fixture.indexUrl(), Fixture.index(sequence, releases.map { it.release }, revoked, isPaused, cappedAt).toJson(), headers = mapOf("ETag" to etag))
        for (entry in releases) {
            http.stubJson(entry.release.manifestUrl, entry.envelope.toJson())
            http.stub(entry.envelope.pack.url, body = entry.pack)
        }
    }
}
