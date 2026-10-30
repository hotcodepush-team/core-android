package com.hotcodepush.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class DownloaderTest {
    private val indexHtml = "<html>v2</html>".toByteArray()
    private val appJs = "console.log('v2')".toByteArray()

    /** A pack that carries none of the files, so each arrives through the single-file fetch after it. */
    private val emptyPack = PackWriter.pack(emptyList())

    @Test
    fun shouldRefuseAManifestUrlOffTheConfiguredHosts() {
        val harness = DownloaderHarness()
        val release = harness.publish(DownloaderHarness.bundle(mapOf("index.html" to indexHtml)).manifest, manifestUrl = "https://elsewhere.test/manifest.json")
        assertEquals(FailedReason.MANIFEST_INVALID, harness.downloadFailure(release)?.reason)
        assertTrue(harness.http.requests.isEmpty())
    }

    @Test
    fun shouldRefuseAPackUrlOffTheConfiguredHosts() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        assertEquals(FailedReason.MANIFEST_INVALID, harness.downloadFailure(harness.publish(bundle.manifest, bundle.pack, packUrl = "https://elsewhere.test/pack"))?.reason)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldRefuseAManifestPathThatClimbsOutOfTheServedTree() {
        val harness = DownloaderHarness()
        val failure = harness.downloadFailure(harness.publish(DownloaderHarness.bundle(mapOf("../../escape.html" to indexHtml)).manifest))
        assertEquals(FailedReason.MANIFEST_INVALID, failure?.reason)
        assertTrue(harness.files.bundleIds().isEmpty())
        assertFalse(File(harness.root, "escape.html").exists())
    }

    @Test
    fun shouldRefuseAnEnvelopeNamingAnotherBundle() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        assertEquals(FailedReason.MANIFEST_INVALID, harness.downloadFailure(harness.publish(bundle.manifest, bundle.pack, bundleId = "b3"))?.reason)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldRefuseAManifestOfAnotherAppBeforeAByteOfThePackArrives() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        assertEquals(FailedReason.MANIFEST_INVALID, harness.downloadFailure(harness.publish(bundle.manifest.copy(appId = "another-app"), bundle.pack))?.reason)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldRefuseAManifestThatLeavesOutTheDevicesPlatformBeforeAByteOfThePackArrives() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        assertEquals(FailedReason.MANIFEST_INVALID, harness.downloadFailure(harness.publish(bundle.manifest.copy(platforms = listOf("ios")), bundle.pack))?.reason)
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
        assertEquals(FailedReason.CONTENT_MISMATCHED, harness.downloadFailure(harness.publish(manifest, bundle.pack))?.reason)
        assertFalse(harness.files.hasFile(Hashing.sha256Hex(indexHtml)))
    }

    @Test
    fun shouldRefuseAPackWhoseLengthDiffersFromTheEnvelope() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        assertEquals(FailedReason.CONTENT_MISMATCHED, harness.downloadFailure(harness.publish(bundle.manifest, bundle.pack, packSizeBytes = bundle.pack.size + 1L))?.reason)
        assertEquals(emptyList<String>(), File(harness.root, "tmp").list()?.toList())
    }

    @Test
    fun shouldStoreASingleFileThatIsItselfGzipAsItArrives() {
        val harness = DownloaderHarness()
        val archive = Gzip.compress("console.log('precompressed')".toByteArray())
        val sha256 = Hashing.sha256Hex(archive)
        val manifest = DownloaderHarness.manifest(listOf(BundleManifest.File("assets/app.js.gz", sha256, archive.size.toLong())))
        harness.http.stub("${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/files/$sha256", body = archive)
        assertNull(harness.downloadFailure(harness.publish(manifest, emptyPack)))
        assertArrayEquals(archive, harness.files.file(sha256).readBytes())
    }

    @Test
    fun shouldRefuseAPackEntryThatIsNotGzip() {
        val harness = DownloaderHarness()
        val pack = PackWriter.pack(listOf(indexHtml, appJs).map { PackedEntry.file(Hashing.sha256Hex(it), it) })
        val manifest = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs)).manifest
        assertEquals(FailedReason.CONTENT_MISMATCHED, harness.downloadFailure(harness.publish(manifest, pack))?.reason)
        assertFalse(harness.files.hasFile(Hashing.sha256Hex(indexHtml)))
    }

    @Test
    fun shouldRefuseASingleFileLargerThanItsSize() {
        val harness = DownloaderHarness()
        val sha256 = Hashing.sha256Hex(indexHtml)
        val manifest = DownloaderHarness.manifest(listOf(BundleManifest.File("index.html", sha256, indexHtml.size - 1L)))
        harness.http.stub("${Fixture.FILES_BASE_URL}/apps/${Fixture.APP_ID}/files/$sha256", body = indexHtml)
        assertEquals(FailedReason.DOWNLOAD_FAILED, harness.downloadFailure(harness.publish(manifest, emptyPack))?.reason)
        assertFalse(harness.files.hasFile(sha256))
    }
}

class DeltaTest {
    private val indexHtml = "<html>v3</html>".toByteArray()
    private val appJs = "console.log('v3')".toByteArray()
    private val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
    private val listedUrl = "${DownloaderHarness.PACK_URL}-from-b1"
    private val streamedUrl = "${Fixture.UPDATES_BASE_URL}/v1/apps/${Fixture.APP_ID}/bundles/${DownloaderHarness.BUNDLE_ID}/deltas/b1"

    @Test
    fun shouldTakeTheDeltaTheEnvelopeListsWhenTheDeviceHoldingABaseLacksASingleFile() {
        val harness = DownloaderHarness()
        val single = DownloaderHarness.bundle(mapOf("index.html" to indexHtml))
        val release = harness.publish(single.manifest, single.pack, deltas = mapOf("b1" to single.pack))
        assertEquals(PackKind.DELTA, harness.download(release, "b1").packKind)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, listedUrl), harness.http.requests.map { it.first })
        assertTrue(harness.files.hasFile(Hashing.sha256Hex(indexHtml)))
    }

    @Test
    fun shouldAskTheUpdatesHostDirectlyWhenTheEnvelopeListsNoDeltaForTheBase() {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b0" to bundle.pack))
        harness.http.stub(streamedUrl, body = bundle.pack)
        assertEquals(PackKind.STREAMED, harness.download(release, "b1").packKind)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, streamedUrl), harness.http.requests.map { it.first })
        assertTrue(harness.files.hasFile(Hashing.sha256Hex(indexHtml)))
    }

    @Test
    fun shouldFailTheDownloadWhenTheDeltaTheEnvelopeListsAnswers404() {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b1" to bundle.pack))
        harness.http.stubs.remove(listedUrl)
        harness.http.stub(streamedUrl, body = bundle.pack)
        assertEquals(FailedReason.DOWNLOAD_FAILED, harness.downloadFailure(release, "b1")?.reason)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, listedUrl), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldTakeTheFullPackWhenTheStreamedDeltaRedirects() {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack)
        harness.http.stub(streamedUrl, status = 302, headers = mapOf("Location" to "https://elsewhere.test/pack"), body = ByteArray(0))
        assertEquals(PackKind.FULL, harness.download(release, "b1").packKind)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, streamedUrl, DownloaderHarness.PACK_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldTakeTheFullPackAfterThreeAttemptsWhenTheUpdatesHostAnswersTheStreamedDelta503EachTime() = runTest {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack)
        harness.http.stub(streamedUrl, status = 503, body = ByteArray(0))
        assertEquals(PackKind.FULL, harness.downloader.downloadRelease(release, "b1") { _, _ -> }.packKind)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL) + List(3) { streamedUrl } + DownloaderHarness.PACK_URL, harness.http.requests.map { it.first })
    }

    @Test
    fun shouldFailTheDownloadWhenTheFullPackTheStreamedDeltaGaveWayToAnswersAnError() {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest)
        assertEquals(FailedReason.DOWNLOAD_FAILED, harness.downloadFailure(release, "b1")?.reason)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, streamedUrl, DownloaderHarness.PACK_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldFailTheDownloadWhenTheDeltaTheEnvelopeListsRedirects() {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b1" to bundle.pack))
        harness.http.stub(listedUrl, status = 302, headers = mapOf("Location" to DownloaderHarness.PACK_URL), body = ByteArray(0))
        assertEquals(FailedReason.DOWNLOAD_FAILED, harness.downloadFailure(release, "b1")?.reason)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, listedUrl), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldFetchTheFilesADeltaDidNotCarryOneByOne() {
        val harness = DownloaderHarness()
        val delta = PackWriter.pack(listOf(PackedEntry.file(Hashing.sha256Hex(indexHtml), Gzip.compress(indexHtml))))
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b1" to delta))
        val fileUrl = DownloaderHarness.fileUrl(Hashing.sha256Hex(appJs))
        harness.http.stub(fileUrl, body = appJs)
        assertEquals(PackKind.DELTA, harness.download(release, "b1").packKind)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, listedUrl, fileUrl), harness.http.requests.map { it.first })
        assertTrue(harness.files.hasFile(Hashing.sha256Hex(appJs)))
    }

    @Test
    fun shouldRefuseADeltaLargerThanTheSizeTheEnvelopeLists() {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b1" to bundle.pack))
        harness.http.stub(listedUrl, body = bundle.pack + ByteArray(1))
        assertEquals(FailedReason.DOWNLOAD_FAILED, harness.downloadFailure(release, "b1")?.reason)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, listedUrl), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldRefuseAStreamedDeltaLargerThanTheFullPack() {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack)
        harness.http.stub(streamedUrl, body = bundle.pack + ByteArray(1))
        assertEquals(FailedReason.DOWNLOAD_FAILED, harness.downloadFailure(release, "b1")?.reason)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, streamedUrl), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldTakeTheFullPackOnAFreshInstallWithoutABase() {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack)
        assertEquals(PackKind.FULL, harness.download(release, null).packKind)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, DownloaderHarness.PACK_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldTakeTheFullPackWithoutABaseWhenASingleFileIsMissing() {
        val harness = DownloaderHarness()
        val single = DownloaderHarness.bundle(mapOf("index.html" to indexHtml))
        assertEquals(PackKind.FULL, harness.download(harness.publish(single.manifest, single.pack), null).packKind)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, DownloaderHarness.PACK_URL), harness.http.requests.map { it.first })
    }
}

class FetchRetryTest {
    private val indexHtml = "<html>v2</html>".toByteArray()
    private val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml))

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun shouldProceedWithTheDownloadWhenTheManifestDropsTwiceAndArrivesOnTheThirdAttempt() = runTest {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack)
        harness.http.failNextFetches(DownloaderHarness.MANIFEST_URL, IOException("connection reset"), IOException("connection reset"))
        assertEquals(PackKind.FULL, harness.downloader.downloadRelease(release, null) { _, _ -> }.packKind)
        assertEquals(List(3) { DownloaderHarness.MANIFEST_URL } + DownloaderHarness.PACK_URL, harness.http.requests.map { it.first })
        assertEquals(4_000L, currentTime)
    }

    @Test
    fun shouldStoreAFileThatDropsTwiceAndArrivesOnTheThirdAttempt() = runTest {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, PackWriter.pack(emptyList()))
        val sha256 = Hashing.sha256Hex(indexHtml)
        val fileUrl = DownloaderHarness.fileUrl(sha256)
        harness.http.stub(fileUrl, body = indexHtml)
        harness.http.failNextFetches(fileUrl, IOException("connection reset"), IOException("connection reset"))
        harness.downloader.downloadRelease(release, null) { _, _ -> }
        assertEquals(3, harness.http.requests.count { it.first == fileUrl })
        assertArrayEquals(indexHtml, harness.files.file(sha256).readBytes())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun shouldRethrowTheCancellationWhenTheDownloadIsCancelledDuringAPause() = runTest {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack)
        harness.http.failNextFetches(DownloaderHarness.PACK_URL, IOException("connection reset"))
        var thrown: Throwable? = null
        val download = launch {
            try {
                harness.downloader.downloadRelease(release, null) { _, _ -> }
            } catch (failure: Throwable) {
                thrown = failure
                throw failure
            }
        }
        runCurrent()
        download.cancel()
        runCurrent()
        assertTrue("$thrown", thrown is CancellationException)
        assertEquals(1, harness.http.requests.count { it.first == DownloaderHarness.PACK_URL })
    }
}
