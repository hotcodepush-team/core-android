package com.hotcodepush.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class DownloaderTest {
    private val indexHtml = "<html>v2</html>".toByteArray()
    private val appJs = "console.log('v2')".toByteArray()

    @Test
    fun shouldRefuseAManifestUrlOffTheConfiguredHosts() {
        val harness = DownloaderHarness()
        val release =
            harness.publish(
                DownloaderHarness.bundle(mapOf("index.html" to indexHtml)).manifest,
                manifestUrl = "https://elsewhere.test/manifest.json",
            )
        assertEquals(FailedReason.MANIFEST_INVALID, harness.downloadFailure(release)?.reason)
        assertTrue(harness.http.requests.isEmpty())
    }

    @Test
    fun shouldRefuseAFullPackUrlOffTheConfiguredHostsBeforeTheDeltaIsRequested() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        val release = harness.publish(bundle.manifest, bundle.pack, packUrl = "https://elsewhere.test/pack", deltas = mapOf("b1" to bundle.pack))
        assertEquals(FailedReason.MANIFEST_INVALID, harness.downloadFailure(release, "b1")?.reason)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldRefuseAListedDeltaUrlOffTheConfiguredHostsBeforeAnyPackIsRequested() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        val release = harness.publish(bundle.manifest, bundle.pack)
        val offHostDelta = ManifestEnvelope.Delta(baseBundleId = "b1", url = "https://elsewhere.test/delta", sizeBytes = bundle.pack.size.toLong())
        val envelope =
            ManifestEnvelope(
                bundleId = DownloaderHarness.BUNDLE_ID,
                createdAt = Fixture.BUILT_AT,
                manifest = bundle.manifest.toJson().toString(),
                signature = null,
                pack = ManifestEnvelope.Pack(DownloaderHarness.PACK_URL, bundle.pack.size.toLong()),
                deltas = listOf(offHostDelta),
            )
        harness.http.stubJson(DownloaderHarness.MANIFEST_URL, envelope.toJson())
        assertEquals(FailedReason.MANIFEST_INVALID, harness.downloadFailure(release)?.reason)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldFailWithDownloadFailedWhenThePackFileIsGoneBeforeItIsRead() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        val release = harness.publish(bundle.manifest, bundle.pack)
        val packFile = harness.downloader.resolvePackFile(bundleId = DownloaderHarness.BUNDLE_ID, url = DownloaderHarness.PACK_URL)
        val failure = runBlocking { runCatching { harness.downloader.downloadRelease(release, baseBundleId = null) { _, _ -> packFile.delete() } }.exceptionOrNull() }
        assertEquals(FailedReason.DOWNLOAD_FAILED, (failure as? DownloadFailure)?.reason)
    }

    @Test
    fun shouldTakeTheFullPackWhenAFileTheDeviceHeldVanishesWhileThePackIsRead() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        val heldSha256 = Hashing.sha256Hex(appJs)
        harness.files.writeFile(appJs, heldSha256)
        val release = harness.publish(bundle.manifest, bundle.pack)
        val outcome = runBlocking { harness.downloader.downloadRelease(release, baseBundleId = null) { _, _ -> harness.files.file(heldSha256).delete() } }
        assertEquals(PackKind.FULL, outcome.packKind)
        assertTrue(harness.files.hasFile(Hashing.sha256Hex(indexHtml)))
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
        assertEquals(
            FailedReason.MANIFEST_INVALID,
            harness.downloadFailure(harness.publish(bundle.manifest, bundle.pack, bundleId = "b3"))?.reason,
        )
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldRefuseAManifestOfAnotherAppBeforeItFetchesItsPack() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        assertEquals(
            FailedReason.MANIFEST_INVALID,
            harness.downloadFailure(harness.publish(bundle.manifest.copy(appId = "another-app"), bundle.pack))?.reason,
        )
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldRefuseAManifestThatLeavesOutTheDevicesPlatform() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        assertEquals(
            FailedReason.MANIFEST_INVALID,
            harness.downloadFailure(harness.publish(bundle.manifest.copy(platforms = listOf("ios")), bundle.pack))?.reason,
        )
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
    fun shouldFailADownloadWhoseFullPackDoesNotFitInTheFreeSpaceWhenASmallerDeltaComesFirst() {
        val harness = DownloaderHarness()
        val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
        val release = harness.publish(bundle.manifest, bundle.pack, packSizeBytes = Long.MAX_VALUE / 2, deltas = mapOf("b1" to bundle.pack))
        assertEquals(FailedReason.DOWNLOAD_FAILED, harness.downloadFailure(release, "b1")?.reason)
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
        assertEquals(
            FailedReason.CONTENT_MISMATCHED,
            harness
                .downloadFailure(
                    harness.publish(
                        bundle.manifest,
                        bundle.pack,
                        packSizeBytes =
                        bundle.pack.size + 1L,
                    ),
                )?.reason,
        )
        assertEquals(emptyList<String>(), File(harness.root, "tmp").list()?.toList())
    }

    @Test
    fun shouldFailWithContentMismatchedWhenTheFullPackLacksAListedFile() {
        val harness = DownloaderHarness()
        val manifest = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs)).manifest
        val failure = harness.downloadFailure(harness.publish(manifest, DownloaderHarness.bundle(mapOf("app.js" to appJs)).pack))
        assertEquals(FailedReason.CONTENT_MISMATCHED, failure?.reason)
        assertEquals("The pack lacks the file index.html", failure?.message)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, DownloaderHarness.PACK_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldRefuseAPackEntryThatIsNotGzip() {
        val harness = DownloaderHarness()
        val pack = PackWriter.pack(listOf(indexHtml, appJs).map { PackedEntry.file(Hashing.sha256Hex(it), it) })
        val manifest = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs)).manifest
        assertEquals(FailedReason.CONTENT_MISMATCHED, harness.downloadFailure(harness.publish(manifest, pack))?.reason)
        assertFalse(harness.files.hasFile(Hashing.sha256Hex(indexHtml)))
    }
}

class DeltaTest {
    private val indexHtml = "<html>v3</html>".toByteArray()
    private val appJs = "console.log('v3')".toByteArray()
    private val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml, "app.js" to appJs))
    private val listedUrl = "${DownloaderHarness.PACK_URL}-from-b1"
    private val routeUrl = "${Fixture.UPDATES_BASE_URL}/v1/apps/${Fixture.APP_ID}/bundles/${DownloaderHarness.BUNDLE_ID}/deltas/b1"

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
    fun shouldAskTheUpdatesHostOnceAndTakeTheFullPackWhenTheEnvelopeListsNoDeltaForTheBase() {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b0" to bundle.pack))
        assertEquals(PackKind.FULL, harness.download(release, "b1").packKind)
        assertEquals(1, harness.http.requests.count { it.first == routeUrl })
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, DownloaderHarness.PACK_URL), harness.http.requests.map { it.first }.filter { it != routeUrl })
        assertTrue(harness.files.hasFile(Hashing.sha256Hex(indexHtml)))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun shouldAskTheUpdatesHostOnceAndTakeTheFullPackWhenThatRequestGetsNoAnswer() = runTest {
        val harness = DownloaderHarness(dispatcher = UnconfinedTestDispatcher(testScheduler))
        val release = harness.publish(bundle.manifest, bundle.pack)
        harness.http.failNextFetches(routeUrl, IOException("connection reset"), IOException("connection reset"), IOException("connection reset"))
        assertEquals(PackKind.FULL, harness.downloader.downloadRelease(release, "b1") { _, _ -> }.packKind)
        advanceUntilIdle()
        assertEquals(1, harness.http.requests.count { it.first == routeUrl })
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, DownloaderHarness.PACK_URL), harness.http.requests.map { it.first }.filter { it != routeUrl })
        assertTrue(harness.uncaught.isEmpty())
    }

    @Test
    fun shouldFinishTheDownloadWhileTheUpdatesHostHasNotAnswered() = runBlocking {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack)
        val routeAnswer = CompletableDeferred<Unit>()
        harness.http.sendGate = routeAnswer
        val download = async(Dispatchers.Unconfined) { harness.downloader.downloadRelease(release, "b1") { _, _ -> } }
        val isFinishedBeforeTheAnswer = download.isCompleted
        routeAnswer.complete(Unit)
        assertTrue(isFinishedBeforeTheAnswer)
        assertEquals(PackKind.FULL, download.await().packKind)
        assertEquals(1, harness.http.requests.count { it.first == routeUrl })
    }

    @Test
    fun shouldTakeTheFullPackWhenTheDeltaTheEnvelopeListsIsRefused() {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b1" to bundle.pack))
        harness.http.stubs.remove(listedUrl)
        assertEquals(PackKind.FULL, harness.download(release, "b1").packKind)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, listedUrl, DownloaderHarness.PACK_URL), harness.http.requests.map { it.first })
        assertTrue(harness.files.hasFile(Hashing.sha256Hex(indexHtml)))
    }

    @Test
    fun shouldTakeTheFullPackWhenAnEntryOfTheDeltaDoesNotVerify() {
        val harness = DownloaderHarness()
        val delta = PackWriter.pack(listOf(PackedEntry.file(Hashing.sha256Hex(appJs), Gzip.compress(appJs)), PackedEntry.file(Hashing.sha256Hex(indexHtml), indexHtml)))
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b1" to delta))
        assertEquals(PackKind.FULL, harness.download(release, "b1").packKind)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, listedUrl, DownloaderHarness.PACK_URL), harness.http.requests.map { it.first })
        assertTrue(harness.files.hasFile(Hashing.sha256Hex(indexHtml)))
    }

    @Test
    fun shouldTakeTheFullPackWhenTheNativePatchLibraryIsMissing() {
        val harness = DownloaderHarness()
        val base = "<html>v2</html>".toByteArray()
        harness.files.writeFile(base, Hashing.sha256Hex(base))
        val corruptPatch = PackedEntry.patch(fromSha256 = Hashing.sha256Hex(base), toSha256 = Hashing.sha256Hex(indexHtml), body = "BSDIFF40".toByteArray())
        val delta = PackWriter.pack(listOf(PackedEntry.file(Hashing.sha256Hex(appJs), Gzip.compress(appJs)), corruptPatch))
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b1" to delta))
        assertEquals(PackKind.FULL, harness.download(release, "b1").packKind)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, listedUrl, DownloaderHarness.PACK_URL), harness.http.requests.map { it.first })
        assertTrue(harness.files.hasFile(Hashing.sha256Hex(indexHtml)))
    }

    @Test
    fun shouldTakeTheFullPackWhenNoAttemptOfTheDeltaGetsAnAnswer() = runTest {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b1" to bundle.pack))
        harness.http.failNextFetches(listedUrl, IOException("connection reset"), IOException("connection reset"), IOException("connection reset"))
        assertEquals(PackKind.FULL, harness.downloader.downloadRelease(release, "b1") { _, _ -> }.packKind)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL) + List(3) { listedUrl } + DownloaderHarness.PACK_URL, harness.http.requests.map { it.first })
    }

    @Test
    fun shouldTakeTheFullPackWhenTheDeltaRequestFailsWithAnErrorThatIsNoIOException() {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b1" to bundle.pack))
        harness.http.failNextFetches(listedUrl, IllegalArgumentException("unexpected answer"))
        assertEquals(PackKind.FULL, harness.download(release, "b1").packKind)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, listedUrl, DownloaderHarness.PACK_URL), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldRemoveThePartialFileOfTheDeltaWhenTheDownloadMovesOnToTheFullPack() = runTest {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b1" to bundle.pack))
        val partialDelta = harness.downloader.resolvePackFile(bundleId = DownloaderHarness.BUNDLE_ID, url = listedUrl).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(512))
        }
        harness.http.failNextFetches(listedUrl, IOException("connection reset"), IOException("connection reset"), IOException("connection reset"))
        assertEquals(PackKind.FULL, harness.downloader.downloadRelease(release, "b1") { _, _ -> }.packKind)
        assertFalse(partialDelta.exists())
    }

    @Test
    fun shouldFailWithTheFullPacksOwnReasonWhenItGetsNoAnswerAfterADeltaLeftAFileMissing() = runTest {
        val harness = DownloaderHarness()
        val delta = PackWriter.pack(listOf(PackedEntry.file(Hashing.sha256Hex(indexHtml), Gzip.compress(indexHtml))))
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b1" to delta))
        harness.http.failNextFetches(DownloaderHarness.PACK_URL, IOException("connection reset"), IOException("connection reset"), IOException("connection reset"))
        val failure = runCatching { harness.downloader.downloadRelease(release, "b1") { _, _ -> } }.exceptionOrNull()
        assertEquals(FailedReason.DOWNLOAD_FAILED, (failure as? DownloadFailure)?.reason)
    }

    @Test
    fun shouldFailTheDownloadWithoutTheFullPackWhenTheDevicesStorageFails() {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b1" to bundle.pack))
        harness.files.rootDirectory.mkdirs()
        harness.files.filesDirectory.writeBytes(ByteArray(0))
        assertEquals(FailedReason.DOWNLOAD_FAILED, harness.downloadFailure(release, "b1")?.reason)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, listedUrl), harness.http.requests.map { it.first })
    }

    @Test
    fun shouldTakeTheFullPackAndCountItsBytesAloneWhenAFileIsStillMissingAfterADelta() {
        val harness = DownloaderHarness()
        val delta = PackWriter.pack(listOf(PackedEntry.file(Hashing.sha256Hex(indexHtml), Gzip.compress(indexHtml))))
        val release = harness.publish(bundle.manifest, bundle.pack, deltas = mapOf("b1" to delta))
        val outcome = harness.download(release, "b1")
        assertEquals(PackKind.FULL, outcome.packKind)
        assertEquals(bundle.pack.size.toLong(), outcome.downloadSizeBytes)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, listedUrl, DownloaderHarness.PACK_URL), harness.http.requests.map { it.first })
        assertTrue(harness.files.hasFile(Hashing.sha256Hex(appJs)))
    }

    @Test
    fun shouldTakeTheFullPackWhenTheDeviceHoldsNoBase() {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack)
        assertEquals(PackKind.FULL, harness.download(release, baseBundleId = null).packKind)
        assertEquals(listOf(DownloaderHarness.MANIFEST_URL, DownloaderHarness.PACK_URL), harness.http.requests.map { it.first })
    }
}

class FetchRetryTest {
    private val indexHtml = "<html>v2</html>".toByteArray()
    private val bundle = DownloaderHarness.bundle(mapOf("index.html" to indexHtml))

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun shouldDownloadWhenTheManifestFetchDropsTwiceAndTheThirdAttemptArrives() = runTest {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack)
        harness.http.failNextFetches(DownloaderHarness.MANIFEST_URL, IOException("connection reset"), IOException("connection reset"))
        assertEquals(PackKind.FULL, harness.downloader.downloadRelease(target = release, baseBundleId = null) { _, _ -> }.packKind)
        assertEquals(List(3) { DownloaderHarness.MANIFEST_URL } + DownloaderHarness.PACK_URL, harness.http.requests.map { it.first })
        assertEquals(4_000L, currentTime)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun shouldRethrowTheCancellationWhenTheDownloadIsCancelledDuringAPause() = runTest {
        val harness = DownloaderHarness()
        val release = harness.publish(bundle.manifest, bundle.pack)
        harness.http.failNextFetches(DownloaderHarness.PACK_URL, IOException("connection reset"))
        var thrown: Throwable? = null
        val download =
            launch {
                try {
                    harness.downloader.downloadRelease(target = release, baseBundleId = null) { _, _ -> }
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
