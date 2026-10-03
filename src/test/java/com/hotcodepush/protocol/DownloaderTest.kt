package com.hotcodepush.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloaderTest {
    private val indexHtml = "<html>v2</html>".toByteArray()
    private val appJs = "console.log('v2')".toByteArray()

    @Test
    fun shouldRefuseAManifestUrlOffTheConfiguredHosts() {
        val harness = DownloaderHarness()
        val release = harness.publish(DownloaderHarness.bundle(mapOf("index.html" to indexHtml)).manifest, manifestUrl = "https://elsewhere.test/manifest.json")
        assertEquals(FailedReason.VERIFICATION_FAILED, harness.downloadFailure(release)?.reason)
        assertTrue(harness.http.requests.isEmpty())
    }

    @Test
    fun shouldRefuseAPackUrlOffTheConfiguredHosts() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        assertEquals(FailedReason.VERIFICATION_FAILED, harness.downloadFailure(harness.publish(bundle.manifest, bundle.pack, packUrl = "https://elsewhere.test/pack"))?.reason)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldRefuseAManifestPathThatClimbsOutOfTheServedTree() {
        val harness = DownloaderHarness()
        val failure = harness.downloadFailure(harness.publish(DownloaderHarness.bundle(mapOf("../../escape.html" to indexHtml)).manifest))
        assertEquals(FailedReason.VERIFICATION_FAILED, failure?.reason)
        assertTrue(harness.files.bundleIds().isEmpty())
        assertFalse(File(harness.root, "escape.html").exists())
    }

    @Test
    fun shouldRefuseAnEnvelopeNamingAnotherBundle() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        assertEquals(FailedReason.VERIFICATION_FAILED, harness.downloadFailure(harness.publish(bundle.manifest, bundle.pack, bundleId = "b3"))?.reason)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldFailADownloadThatDoesNotFitInTheFreeSpace() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        val manifest = bundle.manifest.copy(files = bundle.manifest.files.map { it.copy(sizeBytes = Long.MAX_VALUE / 4) })
        assertEquals(FailedReason.DOWNLOAD_FAILED, harness.downloadFailure(harness.publish(manifest, bundle.pack))?.reason)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldRefuseAPackEntryThatInflatesPastItsFileSize() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        val manifest = bundle.manifest.copy(files = bundle.manifest.files.map { it.copy(sizeBytes = it.sizeBytes - 1) })
        assertEquals(FailedReason.VERIFICATION_FAILED, harness.downloadFailure(harness.publish(manifest, bundle.pack))?.reason)
        assertFalse(harness.files.hasFile(Hashing.sha256Hex(indexHtml)))
    }

    @Test
    fun shouldRefuseAPackWhoseLengthDiffersFromTheEnvelope() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        assertEquals(FailedReason.VERIFICATION_FAILED, harness.downloadFailure(harness.publish(bundle.manifest, bundle.pack, packSizeBytes = bundle.pack.size + 1L))?.reason)
        assertEquals(emptyList<String>(), File(harness.root, "tmp").list()?.toList())
    }

    @Test
    fun shouldStoreASingleFileThatIsItselfGzipAsItArrives() {
        val harness = DownloaderHarness()
        val archive = Gzip.compress("console.log('precompressed')".toByteArray())
        val sha256 = Hashing.sha256Hex(archive)
        val manifest = DownloaderHarness.manifest(listOf(BundleManifest.File("assets/app.js.gz", sha256, archive.size.toLong())))
        harness.http.stub("${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/files/$sha256", body = archive)
        assertNull(harness.downloadFailure(harness.publish(manifest)))
        assertArrayEquals(archive, harness.files.file(sha256).readBytes())
    }

    @Test
    fun shouldRefuseAPackEntryThatIsNotGzip() {
        val harness = DownloaderHarness()
        val pack = PackWriter.pack(listOf(indexHtml, appJs).map { PackEntry(Hashing.sha256Hex(it), it) })
        val manifest = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs)).manifest
        assertEquals(FailedReason.VERIFICATION_FAILED, harness.downloadFailure(harness.publish(manifest, pack))?.reason)
        assertFalse(harness.files.hasFile(Hashing.sha256Hex(indexHtml)))
    }

    @Test
    fun shouldRefuseASingleFileLargerThanItsSize() {
        val harness = DownloaderHarness()
        val sha256 = Hashing.sha256Hex(indexHtml)
        val manifest = DownloaderHarness.manifest(listOf(BundleManifest.File("index.html", sha256, indexHtml.size - 1L)))
        harness.http.stub("${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/files/$sha256", body = indexHtml)
        assertEquals(FailedReason.DOWNLOAD_FAILED, harness.downloadFailure(harness.publish(manifest))?.reason)
        assertFalse(harness.files.hasFile(sha256))
    }
}

/** A downloader over fakes, in a fresh temporary directory. */
class DownloaderHarness {
    data class Bundle(val manifest: BundleManifest, val pack: ByteArray)

    val root: File = Files.createTempDirectory("hotcodepush-tests").toFile()
    val http = FakeHttpClient()
    val files = FileStore(File(root, "store"))
    val downloader = Downloader(Fixture.configuration(), files, InMemoryEmbeddedBundle(), http, File(root, "tmp"))

    /** Serves the envelope, and its pack when given, where the index entry says they are and returns that entry. */
    fun publish(manifest: BundleManifest, pack: ByteArray? = null, packUrl: String = PACK_URL, packSizeBytes: Long? = null, bundleId: String = BUNDLE_ID, manifestUrl: String = MANIFEST_URL): IndexRelease {
        val json = manifest.toJson().toString()
        val envelope = ManifestEnvelope(bundleId, Fixture.BUILT_AT, json, null, ManifestEnvelope.Pack(packUrl, packSizeBytes ?: pack?.size?.toLong() ?: 0), emptyList(), emptyList())
        http.stubJson(manifestUrl, envelope.toJson())
        if (pack != null) http.stub(packUrl, body = pack)
        return IndexRelease("r2", 2, Fixture.BUILT_AT, false, null, 100, emptyList(), BUNDLE_ID, manifest.bundleVersion, manifestUrl, Hashing.sha256Hex(json), 0)
    }

    fun downloadFailure(release: IndexRelease): DownloadFailure? = runBlocking {
        try {
            downloader.downloadRelease(release, null) { _, _ -> }
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
            val pack = PackWriter.pack(sorted.values.map { PackEntry(Hashing.sha256Hex(it), Gzip.compress(it)) })
            val entries = sorted.map { (path, content) -> BundleManifest.File(path, Hashing.sha256Hex(content), content.size.toLong()) }
            return Bundle(manifest(entries), pack)
        }

        fun manifest(files: List<BundleManifest.File>) = BundleManifest(appId = Fixture.APP_ID, bundleVersion = "1.2.0", files = files, platforms = listOf("android"))
    }
}
