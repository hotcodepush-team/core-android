package com.hotcodepush.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

class FakeHttpClient : HttpClient {
    data class Stub(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    val stubs = mutableMapOf<String, Stub>()
    val requests = mutableListOf<Pair<String, Map<String, String>>>()
    val posts = mutableListOf<Triple<String, Map<String, String>, ByteArray>>()
    var isOffline = false

    fun stub(url: String, status: Int = 200, headers: Map<String, String> = emptyMap(), body: ByteArray) {
        stubs[url] = Stub(status, headers, body)
    }

    fun stubJson(url: String, json: JSONObject, status: Int = 200, headers: Map<String, String> = emptyMap()) = stub(url, status, headers, json.toString().toByteArray())

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        requests += url to headers
        if (isOffline) throw java.io.IOException("offline")
        val stub = stubs[url] ?: return HttpResponse(404, emptyMap(), ByteArray(0))
        return HttpResponse(stub.status, stub.headers, stub.body)
    }

    override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
        posts += Triple(url, headers, body)
        if (isOffline) throw java.io.IOException("offline")
        val stub = stubs[url] ?: return HttpResponse(404, emptyMap(), ByteArray(0))
        return HttpResponse(stub.status, stub.headers, stub.body)
    }

    override suspend fun download(url: String, file: File, maximumBytes: Long, progress: (Long, Long) -> Unit) {
        requests += url to emptyMap()
        if (isOffline) throw java.io.IOException("offline")
        val stub = stubs[url]?.takeIf { it.status == 200 } ?: throw DownloadFailure.DownloadFailed("HTTP 404")
        if (stub.body.size > maximumBytes) throw DownloadFailure.DownloadFailed("${url.substringAfterLast('/')} is larger than its $maximumBytes bytes")
        file.parentFile?.mkdirs()
        file.writeBytes(stub.body)
        progress(stub.body.size.toLong(), stub.body.size.toLong())
    }
}

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

    override fun projectionDirectory(bundleId: String): File = File(File(root, "www"), bundleId)

    override fun deleteProjection(bundleId: String) {
        projectionDirectory(bundleId).deleteRecursively()
    }

    override fun persistServedBundle(bundleId: String?) {
        persisted = bundleId
        hasPersisted = true
    }

    override fun loadServedBundle(bundleId: String?) {
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

    override fun updateAvailable(event: UpdateAvailableEvent) { available += event }
    override fun updateDownloaded(event: UpdateDownloadedEvent) { downloaded += event }
    override fun updateFailed(event: UpdateFailedEvent) { failed += event }
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

class InMemoryEmbeddedBundle : EmbeddedBundle {
    val files = mutableMapOf<String, ByteArray>()

    override fun has(sha256: String): Boolean = files.containsKey(sha256)

    override fun copyFile(sha256: String, destination: File) {
        destination.writeBytes(files.getValue(sha256))
    }
}

/** One app, one channel, the fixtures every test starts from. */
object Fixture {
    const val APP_ID = "a0000000-0000-4000-8000-000000000001"
    const val CHANNEL_ID = "c0000000-0000-4000-8000-000000000001"
    const val FILES_BASE_URL = "https://files.test"
    const val UPDATES_BASE_URL = "https://updates.test"
    const val BUILT_AT = 1_700_000_000_000L
    val embeddedIndexHtml = "<html>v1</html>".toByteArray()

    data class Published(val release: IndexRelease, val manifest: BundleManifest, val envelope: ManifestEnvelope, val pack: ByteArray)

    fun embeddedManifest() = BundleManifest("embedded", APP_ID, "1.0.0", BUILT_AT, listOf(BundleManifest.File("index.html", Hashing.sha256Hex(embeddedIndexHtml), embeddedIndexHtml.size.toLong())), null, emptyList())

    fun configuration(installStrategy: InstallStrategy = InstallStrategy.NEXT_START, mandatoryInstallStrategy: MandatoryInstallStrategy = MandatoryInstallStrategy.IMMEDIATE, downloadStrategy: DownloadStrategy = DownloadStrategy.AUTO, autoCheck: Boolean = false, readySignal: ReadySignal = ReadySignal.RENDER, publicKeys: List<String> = emptyList(), fingerprint: String? = "fp1:abc", builtAt: Long = BUILT_AT, enabledInDebugBuilds: Boolean = true): Configuration {
        val json = JSONObject()
            .put("appId", APP_ID)
            .put("channelId", CHANNEL_ID)
            .put("autoCheck", autoCheck)
            .put("checkInterval", 900)
            .put("downloadStrategy", downloadStrategy.wire)
            .put("installStrategy", installStrategy.wire)
            .put("mandatoryInstallStrategy", mandatoryInstallStrategy.wire)
            .put("installOnResumeAfter", 300)
            .put("readySignal", readySignal.wire)
            .put("readyTimeout", 10)
            .put("enabledInDebugBuilds", enabledInDebugBuilds)
            .put("publicKeys", org.json.JSONArray(publicKeys))
            .put("builtAt", Iso8601.format(builtAt))
            .put("fingerprint", fingerprint ?: JSONObject.NULL)
            .put("embeddedBundleManifest", embeddedManifest().toJson())
            .put("embeddedBundleId", "embedded")
            .put("filesBaseUrl", FILES_BASE_URL)
            .put("updatesBaseUrl", UPDATES_BASE_URL)
        return Configuration.decode(json.toString())
    }

    fun indexUrl() = "$FILES_BASE_URL/apps/$APP_ID/channels/$CHANNEL_ID/android/v1/index.json"

    fun eventsUrl() = "$UPDATES_BASE_URL/v1/apps/$APP_ID/events"

    fun release(number: Int, bundleId: String, content: ByteArray, createdAt: Long = BUILT_AT + 60_000, rollout: Int = 100, conditions: List<Condition> = emptyList(), isMandatory: Boolean = false): Published {
        val sha256 = Hashing.sha256Hex(content)
        val js = "js-$bundleId".toByteArray()
        val pack = PackWriter.pack(listOf(PackEntry(sha256, Gzip.compress(content)), PackEntry(Hashing.sha256Hex(js), Gzip.compress(js))))
        val manifest = BundleManifest(bundleId, APP_ID, "1.$number.0", createdAt, listOf(BundleManifest.File("index.html", sha256, content.size.toLong()), BundleManifest.File("assets/app.js", Hashing.sha256Hex(js), js.size.toLong())), BundleManifest.Pack("$FILES_BASE_URL/apps/$APP_ID/bundles/$bundleId/pack", pack.size.toLong()), emptyList())
        val manifestJson = manifest.toJson().toString()
        val envelope = ManifestEnvelope(manifestJson, null)
        val release = IndexRelease("r$number", number, createdAt, isMandatory, "notes $number", rollout, conditions, bundleId, manifest.version, "$FILES_BASE_URL/apps/$APP_ID/bundles/$bundleId/manifest.json", Hashing.sha256Hex(manifestJson), content.size.toLong())
        return Published(release, manifest, envelope, pack)
    }

    fun index(sequence: Int, releases: List<IndexRelease>, revoked: List<String> = emptyList(), isPaused: Boolean = false, cappedAt: Long? = null, rollBackToEmbedded: RollBackToEmbedded? = null) =
        ChannelIndex(ChannelIndex.SCHEMA, sequence, APP_ID, CHANNEL_ID, "android", isPaused, cappedAt, revoked, rollBackToEmbedded, releases)
}

/** A core over fakes, in a fresh temporary directory. */
class Harness(configuration: Configuration = Fixture.configuration(), isDebugBuild: Boolean = false) {
    val root: File = Files.createTempDirectory("hotcodepush-tests").toFile()
    val store = InMemoryStore()
    val http = FakeHttpClient()
    val loader = FakeLoader(root)
    val listener = FakeListener()
    val scheduler = ManualScheduler()
    val embedded = InMemoryEmbeddedBundle().apply { files[Hashing.sha256Hex(Fixture.embeddedIndexHtml)] = Fixture.embeddedIndexHtml }
    val clock = FixedClock(Fixture.BUILT_AT + 3_600_000)
    val files = FileStore(File(root, "store"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val device = DeviceFacts("android", "2.4.1", "57", "14", "0.0.0", isDebugBuild)
    var core: Core = build(configuration)

    private fun build(configuration: Configuration) = Core(configuration, device, store, files, embedded, http, loader, listener, scheduler, clock, scope, File(root, "tmp"))

    /** A second core over the same store and files: the next start of the app. */
    fun restart(configuration: Configuration = Fixture.configuration()) {
        core = build(configuration)
    }

    /** The events endpoint answering every batch with the same server time. */
    fun acknowledgeEvents(reportedAt: String = "2023-11-14T23:00:00.000Z") {
        http.stubJson(Fixture.eventsUrl(), JSONObject().put("reportedAt", reportedAt), status = 202)
    }

    fun publish(releases: List<Fixture.Published>, sequence: Int, revoked: List<String> = emptyList(), isPaused: Boolean = false, cappedAt: Long? = null, rollBackToEmbedded: RollBackToEmbedded? = null, etag: String = "\"e1\"") {
        http.stubJson(Fixture.indexUrl(), Fixture.index(sequence, releases.map { it.release }, revoked, isPaused, cappedAt, rollBackToEmbedded).toJson(), headers = mapOf("ETag" to etag))
        for (entry in releases) {
            http.stubJson(entry.release.manifestUrl, entry.envelope.toJson())
            http.stub(entry.manifest.pack!!.url, body = entry.pack)
        }
    }
}
