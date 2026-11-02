package com.hotcodepush.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.file.Files
import java.util.Collections

class PackTest {
    private val sha256 = Hashing.sha256Hex("x")

    @Test
    fun shouldRoundTripEntriesThroughTheUstarFormat() {
        val content = "hello".toByteArray()
        val entries = listOf(PackedEntry.file(Hashing.sha256Hex(content), Gzip.compress(content)), PackedEntry.file(sha256, ByteArray(0)))
        val pack = PackWriter.pack(entries)
        assertEquals(0, pack.size % 512)
        val read = PackReader.entries(pack)
        assertEquals(entries.map { it.entry }, read.map { it.entry })
        assertEquals("hello", String(Gzip.decompress(read[0].body, content.size.toLong())))
    }

    @Test(expected = PackFormatException::class)
    fun shouldRejectATruncatedPack() {
        PackReader.entries(PackWriter.pack(listOf(PackedEntry.file(sha256, ByteArray(700)))).copyOf(600))
    }

    @Test
    fun shouldIgnoreBytesAfterTheEndOfArchiveBlocks() {
        val pack = PackWriter.pack(listOf(PackedEntry.file(sha256, "x".toByteArray()))) + "trailing".toByteArray()
        assertEquals(listOf(PackEntry.File(sha256)), PackReader.entries(pack).map { it.entry })
    }

    @Test(expected = PackFormatException::class)
    fun shouldRefuseAnEntryLargerThanWhatIsLeftOfThePackBeforeAllocatingIt() {
        val pack = PackWriter.pack(listOf(PackedEntry.file(sha256, ByteArray(700))))
        "%011o".format(1_500_000_000).toByteArray(Charsets.US_ASCII).copyInto(pack, 124)
        PackReader.entries(pack)
    }

    @Test(expected = PackFormatException::class)
    fun shouldRefuseAnEntryWithANegativeSize() {
        val pack = PackWriter.pack(listOf(PackedEntry.file(sha256, ByteArray(700))))
        "-0000001000".toByteArray(Charsets.US_ASCII).copyInto(pack, 124)
        PackReader.entries(pack)
    }

    @Test
    fun shouldStreamAnEntryOfAGibibyteAndReadTheEntryAfterIt() {
        val size = 1L shl 30
        val pack =
            SequenceInputStream(
                Collections.enumeration(
                    listOf(
                        PackWriter.header(prefix = "", name = sha256, size = size).inputStream(),
                        ZeroInputStream(size),
                        PackWriter.pack(listOf(PackedEntry.file(Hashing.sha256Hex("y"), "y".toByteArray()))).inputStream(),
                    ),
                ),
            )
        val read = mutableListOf<Pair<PackEntry, String>>()
        PackReader.forEachEntry(pack, 512 + size + 2048) { entry, body -> read += entry to String(body.readNBytes(1)) }
        assertEquals(listOf(PackEntry.File(sha256) to "\u0000", PackEntry.File(Hashing.sha256Hex("y")) to "y"), read)
    }

    @Test
    fun shouldRefuseAPackCutInsideAnEntryTheReaderReads() {
        val pack = PackWriter.pack(listOf(PackedEntry.file(sha256, ByteArray(700))))
        val cut = pack.copyOf(512 + 600)
        assertThrows(PackFormatException::class.java) {
            PackReader.forEachEntry(cut.inputStream(), pack.size.toLong()) { _, body -> body.readBytes() }
        }
    }

    private class ZeroInputStream(
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (remaining == 0L) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }

    @Test
    fun shouldRefuseToInflatePastTheMaximum() {
        val content = ByteArray(1_000_000)
        assertTrue(Gzip.decompress(Gzip.compress(content), content.size.toLong()).contentEquals(content))
        assertThrows(GzipSizeException::class.java) { Gzip.decompress(Gzip.compress(content), content.size - 1L) }
    }
}

class StateStoreTest {
    @Test
    fun shouldKeepTheIdentityKeysAndDropTheCacheOnAnUnknownStateVersion() {
        val store = InMemoryStore()
        store.putInt("hotcodepush.stateVersion", 9)
        store.putString(
            key = "hotcodepush.currentRelease",
            value = Release(id = "r1", number = 1, bundleId = "b1", bundleVersion = "1", isMandatory = false).toJson().toString(),
        )
        store.putString(key = "hotcodepush.deviceId", value = "device-1")
        val state = StateStore(store)
        assertEquals("device-1", state.deviceId)
        assertNull(state.currentRelease)
        assertEquals(StateStore.STATE_VERSION, store.getInt("hotcodepush.stateVersion"))
    }

    @Test
    fun shouldDropALastCheckStoredUnderTheFieldNamesOfThePreviousStateVersion() {
        val store = InMemoryStore()
        val result =
            JSONObject()
                .put(
                    "status",
                    "skipped",
                ).put("release", JSONObject.NULL)
                .put("reason", "device_incompatible")
                .put("condition", "os")
        store.putInt("hotcodepush.stateVersion", 5)
        store.putString(
            key = "hotcodepush.lastCheck",
            value =
            JSONObject()
                .put("checkedAt", "2026-10-30T10:00:00.000Z")
                .put("trigger", "manual")
                .put("result", result)
                .toString(),
        )
        store.putString(key = "hotcodepush.deviceId", value = "device-1")
        val state = StateStore(store)
        assertNull(state.lastCheck)
        assertEquals("device-1", state.deviceId)
    }

    @Test
    fun shouldDropTheCacheWhenAValueDoesNotParse() {
        val store = InMemoryStore()
        val state = StateStore(store)
        state.nextRelease =
            NextRelease(Release(id = "r1", number = 1, bundleId = "b1", bundleVersion = "1", isMandatory = false), ApplyStrategy.NEXT_START)
        store.putString(key = "hotcodepush.currentRelease", value = "not json")
        assertNull(state.currentRelease)
        assertNull(state.nextRelease)
        assertEquals(36, StateStore(store).deviceId.length)
    }

    @Test
    fun shouldDropTheCacheWhenAStoredReleaseIsMissingAField() {
        val store = InMemoryStore()
        val state = StateStore(store)
        state.fallbackRelease = Release(id = "r1", number = 1, bundleId = "b1", bundleVersion = "1", isMandatory = false)
        store.putString(key = "hotcodepush.currentRelease", value = JSONObject().put("id", "r2").put("number", 2).toString())
        assertNull(state.currentRelease)
        assertNull(state.fallbackRelease)
    }

    @Test
    fun shouldDropTheCacheWhenAStoredValueHoldsWhatThisSdkDoesNotKnow() {
        val notice =
            JSONObject()
                .put(
                    "fromRelease",
                    Release(id = "r1", number = 1, bundleId = "b1", bundleVersion = "1", isMandatory = false).toJson(),
                ).put("toRelease", JSONObject.NULL)
                .put("reason", "reason_of_a_later_sdk")
        for ((key, value) in listOf(
            "pendingRollbackEvent" to notice.toString(),
            "lastCheck" to JSONObject().put("checkedAt", "yesterday").toString(),
            "lastBuiltAt" to "yesterday",
        )) {
            val store = InMemoryStore()
            val state = StateStore(store)
            state.nextRelease =
                NextRelease(
                    Release(id = "r1", number = 1, bundleId = "b1", bundleVersion = "1", isMandatory = false),
                    ApplyStrategy.NEXT_START,
                )
            store.putString(key = "hotcodepush.$key", value = value)
            assertNull(key, state.pendingRollbackEvent ?: state.lastCheck ?: state.lastBuiltAt)
            assertNull(key, state.nextRelease)
        }
    }

    @Test
    fun shouldDropTheCacheWhenTheNextReleaseCarriesNoApplyMomentOrOneThisSdkDoesNotKnow() {
        val release = Release(id = "r1", number = 1, bundleId = "b1", bundleVersion = "1", isMandatory = false)
        for (stored in listOf(release.toJson(), JSONObject().put("release", release.toJson()).put("applyStrategy", "on-resume"))) {
            val store = InMemoryStore()
            val state = StateStore(store)
            state.currentRelease = release
            store.putString(key = "hotcodepush.nextRelease", value = stored.toString())
            assertNull(stored.toString(), state.nextRelease)
            assertNull(stored.toString(), state.currentRelease)
        }
    }

    @Test
    fun shouldDropAnIdentityKeyThatDoesNotParseAndKeepTheCache() {
        val store = InMemoryStore()
        val state = StateStore(store)
        state.nextRelease =
            NextRelease(Release(id = "r1", number = 1, bundleId = "b1", bundleVersion = "1", isMandatory = false), ApplyStrategy.NEXT_START)
        store.putString(key = "hotcodepush.channel", value = "not json")
        assertNull(state.channel)
        assertNull(store.getString("hotcodepush.channel"))
        assertEquals(
            NextRelease(
                Release(id = "r1", number = 1, bundleId = "b1", bundleVersion = "1", isMandatory = false),
                ApplyStrategy.NEXT_START,
            ),
            state.nextRelease,
        )
    }
}

class FileStoreTest {
    @Test(expected = HashMismatchException::class)
    fun shouldRefuseAFileWhoseHashDoesNotMatch() {
        FileStore(Files.createTempDirectory("fs").toFile()).writeFile("a".toByteArray(), "0")
    }

    @Test
    fun shouldCollectWhatNoKeptBundleLists() {
        val files = FileStore(Files.createTempDirectory("fs").toFile())
        val kept =
            BundleManifest(
                appId = "a",
                bundleVersion = "1",
                files = listOf(BundleManifest.File(path = "a", sha256 = Hashing.sha256Hex("a"), sizeBytes = 1)),
                platforms = listOf("android"),
            )
        val gone =
            BundleManifest(
                appId = "a",
                bundleVersion = "1",
                files = listOf(BundleManifest.File(path = "b", sha256 = Hashing.sha256Hex("b"), sizeBytes = 1)),
                platforms = listOf("android"),
            )
        files.writeManifest(kept, "kept")
        files.writeManifest(gone, "gone")
        files.writeFile("a".toByteArray(), Hashing.sha256Hex("a"))
        files.writeFile("b".toByteArray(), Hashing.sha256Hex("b"))
        files.deleteUnusedFiles(setOf("kept"))
        assertEquals(listOf("kept"), files.bundleIds())
        assertTrue(files.hasFile(Hashing.sha256Hex("a")))
        assertTrue(!files.hasFile(Hashing.sha256Hex("b")))
    }

    @Test
    fun shouldProjectABundleByPathFromTheStoreAndTheEmbeddedFiles() {
        val root = Files.createTempDirectory("fs").toFile()
        val files = FileStore(File(root, "store"))
        val embedded = InMemoryEmbeddedBundle().apply { this.files[Hashing.sha256Hex("embedded")] = "embedded".toByteArray() }
        files.writeFile("new".toByteArray(), Hashing.sha256Hex("new"))
        val manifest =
            BundleManifest(
                appId = "a",
                bundleVersion = "1",
                files =
                listOf(
                    BundleManifest.File(path = "index.html", sha256 = Hashing.sha256Hex("new"), sizeBytes = 3),
                    BundleManifest.File(path = "assets/logo.svg", sha256 = Hashing.sha256Hex("embedded"), sizeBytes = 8),
                ),
                platforms = listOf("android"),
            )
        val www = File(root, "www")
        BundleProjection.project(manifest, files, embedded, www)
        assertEquals("new", File(www, "index.html").readText())
        assertEquals("embedded", File(www, "assets/logo.svg").readText())
    }
}
