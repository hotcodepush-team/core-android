package com.hotcodepush.core

import kotlinx.coroutines.CoroutineDispatcher
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

    var persistFailure: Throwable? = null
    var loadFailure: Throwable? = null

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
}

class FakeListener : CoreListener {
    val available = mutableListOf<UpdateAvailableEvent>()
    val downloaded = mutableListOf<UpdateDownloadedEvent>()
    val failed = mutableListOf<UpdateFailedEvent>()
    val progress = mutableListOf<DownloadProgressEvent>()
    val rollbacks = mutableListOf<UpdateRolledBackEvent>()

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

    override fun downloadProgress(event: DownloadProgressEvent) { progress += event }
    override fun updateRolledBack(event: UpdateRolledBackEvent) { rollbacks += event }
}

class ManualScheduler : Scheduler {
    class Task(val seconds: Double, val block: () -> Unit) : ScheduledTask {
        var isCancelled = false
        override fun cancel() { isCancelled = true }
    }

    val tasks = mutableListOf<Task>()

    override fun schedule(afterSeconds: Double, block: () -> Unit): ScheduledTask = Task(afterSeconds, block).also { tasks += it }

    fun fire() {
        val pending = tasks.toList()
        tasks.clear()
        pending.filter { !it.isCancelled }.forEach { it.block() }
    }
}

class FakeNetworkState : NetworkState {
    var isMetered = false

    override fun isConnectionMetered(): Boolean = isMetered
}

// The interface's `isInForeground()` takes the property's getter name on the JVM.
class FakeProcessState : ProcessState {
    var isInTheForeground = true

    override fun isInForeground(): Boolean = isInTheForeground
}

class FixedClock(var now: Long) : Clock {
    override fun now(): Long = now
}

// A test's own `dispatcher` runs the core's pauses in virtual time.
class Harness(configuration: Configuration = Fixture.configuration(), isDebugBuild: Boolean = false, private val device: DeviceFacts = DeviceFacts("android", "2.4.1", "57", "14", "0.0.0", isDebugBuild), dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) {
    val root: File = Files.createTempDirectory("hotcodepush-tests").toFile()
    val store = InMemoryStore()
    val http = FakeHttpClient()
    val loader = FakeLoader(root)
    val listener = FakeListener()
    val scheduler = ManualScheduler()
    val embedded = InMemoryEmbeddedBundle().apply { files[Hashing.sha256Hex(Fixture.embeddedIndexHtml)] = Fixture.embeddedIndexHtml }
    val clock = FixedClock(Fixture.BUILT_AT + 3_600_000)
    val files = FileStore(File(root, "store"))
    // On a device, a failure that escapes to the scope crashes the process.
    val uncaught = mutableListOf<Throwable>()
    val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, failure -> uncaught += failure })

    val network = FakeNetworkState()
    val processState = FakeProcessState()
    var core: Core = build(configuration)

    private fun build(configuration: Configuration, scope: CoroutineScope = this.scope) =
        Core(configuration, device, store, files, embedded, http, loader, listener, scheduler, clock, scope, File(root, "tmp"), networkState = network, processState = processState)

    fun restart(configuration: Configuration = Fixture.configuration(), scope: CoroutineScope = this.scope) {
        core = build(configuration, scope)
    }

    fun acknowledgeEvents(reportedAt: String = "2023-11-14T23:00:00.000Z") {
        http.stubJson(Fixture.eventsUrl(), JSONObject().put("reportedAt", reportedAt), status = 202)
    }

    fun publish(releases: List<Fixture.Published>, sequence: Long, revoked: List<String> = emptyList(), pausedAt: Long? = null, cappedAt: Long? = null, etag: String = "\"e1\"") {
        http.stubJson(Fixture.indexUrl(), Fixture.index(sequence, releases.map { it.release }, revoked, pausedAt, cappedAt).toJson(), headers = mapOf("ETag" to etag))
        for (entry in releases) {
            http.stubJson(entry.release.manifestUrl, entry.envelope.toJson())
            http.stub(entry.envelope.pack.url, body = entry.pack)
        }
    }
}
