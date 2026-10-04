package com.hotcodepush.protocol

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/** The files host and the updates host as a test stubs them; the fakes of this file are shared by the JVM tests and the on-device tests. */
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
        val stub = stubs[url] ?: throw HttpStatusException(404)
        if (stub.status != 200) throw HttpStatusException(stub.status)
        if (stub.body.size > maximumBytes) throw DownloadFailure.DownloadFailed("${url.substringAfterLast('/')} is larger than its $maximumBytes bytes")
        file.parentFile?.mkdirs()
        file.writeBytes(stub.body)
        progress(stub.body.size.toLong(), stub.body.size.toLong())
    }
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

    fun embeddedManifest() = EmbeddedBundleManifest(appId = APP_ID, bundleVersion = "1.0.0", files = listOf(BundleManifest.File("index.html", Hashing.sha256Hex(embeddedIndexHtml), embeddedIndexHtml.size.toLong())), platforms = listOf("android"))

    fun configuration(installStrategy: InstallStrategy = InstallStrategy.NEXT_START, mandatoryInstallStrategy: MandatoryInstallStrategy = MandatoryInstallStrategy.IMMEDIATE, downloadStrategy: DownloadStrategy = DownloadStrategy.AUTO, autoCheck: Boolean = false, readySignal: ReadySignal = ReadySignal.RENDER, publicKeys: List<DevicePublicKey> = emptyList(), fingerprint: String? = "fp1:abc", builtAt: Long = BUILT_AT, enabledInDebugBuilds: Boolean = true, channelId: String? = CHANNEL_ID): Configuration {
        val json = JSONObject()
            .put("appId", APP_ID)
            .put("channelId", channelId ?: JSONObject.NULL)
            .put("autoCheck", autoCheck)
            .put("checkInterval", 900)
            .put("downloadStrategy", downloadStrategy.wire)
            .put("installStrategy", installStrategy.wire)
            .put("mandatoryInstallStrategy", mandatoryInstallStrategy.wire)
            .put("installOnResumeAfter", 300)
            .put("readySignal", readySignal.wire)
            .put("readyTimeout", 10)
            .put("enabledInDebugBuilds", enabledInDebugBuilds)
            .put("publicKeys", org.json.JSONArray(publicKeys.map { JSONObject().put("der", it.der).put("keyId", it.keyId) }))
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
        val pack = PackWriter.pack(listOf(PackEntry.File(sha256, Gzip.compress(content)), PackEntry.File(Hashing.sha256Hex(js), Gzip.compress(js))))
        val manifest = BundleManifest(appId = APP_ID, bundleVersion = "1.$number.0", files = listOf(BundleManifest.File("index.html", sha256, content.size.toLong()), BundleManifest.File("assets/app.js", Hashing.sha256Hex(js), js.size.toLong())), platforms = listOf("android"))
        val manifestJson = manifest.toJson().toString()
        val envelope = ManifestEnvelope(bundleId, createdAt, manifestJson, null, ManifestEnvelope.Pack("$FILES_BASE_URL/apps/$APP_ID/bundles/$bundleId/pack", pack.size.toLong()), emptyList())
        val release = IndexRelease("r$number", number, createdAt, isMandatory, "notes $number", rollout, conditions, bundleId, manifest.bundleVersion, "$FILES_BASE_URL/apps/$APP_ID/bundles/$bundleId/manifest.json", Hashing.sha256Hex(manifestJson), content.size.toLong())
        return Published(release, manifest, envelope, pack)
    }

    fun index(sequence: Int, releases: List<IndexRelease>, revoked: List<String> = emptyList(), isPaused: Boolean = false, cappedAt: Long? = null) =
        ChannelIndex(ChannelIndex.SCHEMA, sequence, APP_ID, CHANNEL_ID, "android", isPaused, cappedAt, revoked, releases)
}

/** A downloader over fakes, in a fresh temporary directory. */
class DownloaderHarness {
    data class Bundle(val manifest: BundleManifest, val pack: ByteArray)

    val root: File = File.createTempFile("hotcodepush-tests", "").apply { delete(); mkdirs() }
    val http = FakeHttpClient()
    val files = FileStore(File(root, "store"))
    val embedded = InMemoryEmbeddedBundle()
    val downloader = Downloader(Fixture.configuration(), files, embedded, http, File(root, "tmp"))

    /** Serves the envelope, its pack when given and its delta packs by base, where the index entry says they are and returns that entry. */
    fun publish(manifest: BundleManifest, pack: ByteArray? = null, packUrl: String = PACK_URL, packSizeBytes: Long? = null, bundleId: String = BUNDLE_ID, manifestUrl: String = MANIFEST_URL, deltas: Map<String, ByteArray> = emptyMap()): IndexRelease {
        val json = manifest.toJson().toString()
        val deltaEntries = deltas.map { (baseBundleId, delta) -> ManifestEnvelope.Delta(baseBundleId, "$PACK_URL-from-$baseBundleId", delta.size.toLong()) }
        val envelope = ManifestEnvelope(bundleId, Fixture.BUILT_AT, json, null, ManifestEnvelope.Pack(packUrl, packSizeBytes ?: pack?.size?.toLong() ?: 0), deltaEntries)
        http.stubJson(manifestUrl, envelope.toJson())
        if (pack != null) http.stub(packUrl, body = pack)
        for (entry in deltaEntries) http.stub(entry.url, body = deltas.getValue(entry.baseBundleId))
        return IndexRelease("r2", 2, Fixture.BUILT_AT, false, null, 100, emptyList(), BUNDLE_ID, manifest.bundleVersion, manifestUrl, Hashing.sha256Hex(json), 0)
    }

    fun download(release: IndexRelease, currentBundleId: String?): DownloadOutcome = runBlocking { downloader.downloadRelease(release, currentBundleId) { _, _ -> } }

    fun downloadFailure(release: IndexRelease, currentBundleId: String? = null): DownloadFailure? = runBlocking {
        try {
            downloader.downloadRelease(release, currentBundleId) { _, _ -> }
            null
        } catch (failure: DownloadFailure) {
            failure
        }
    }

    companion object {
        const val BUNDLE_ID = "b2"
        const val MANIFEST_URL = "${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/bundles/$BUNDLE_ID/manifest.json"
        const val PACK_URL = "${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/bundles/$BUNDLE_ID/pack"

        /** The manifest of these files and the pack that carries them, each entry the gzip bytes the bucket serves. */
        fun bundle(files: Map<String, ByteArray>): Bundle {
            val sorted = files.toSortedMap()
            val pack = PackWriter.pack(sorted.values.map { PackEntry.File(Hashing.sha256Hex(it), Gzip.compress(it)) })
            val entries = sorted.map { (path, content) -> BundleManifest.File(path, Hashing.sha256Hex(content), content.size.toLong()) }
            return Bundle(manifest(entries), pack)
        }

        fun fileUrl(sha256: String) = "${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/files/$sha256"

        fun manifest(files: List<BundleManifest.File>) = BundleManifest(appId = Fixture.APP_ID, bundleVersion = "1.2.0", files = files, platforms = listOf("android"))
    }
}
