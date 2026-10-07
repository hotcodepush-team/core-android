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
        val pack = SequenceInputStream(
            Collections.enumeration(
                listOf(
                    PackWriter.header("", sha256, size).inputStream(),
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
        assertThrows(PackFormatException::class.java) { PackReader.forEachEntry(cut.inputStream(), pack.size.toLong()) { _, body -> body.readBytes() } }
    }

    /** `size` zero bytes, made as they are read. */
    private class ZeroInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
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
        store.putString("hotcodepush.currentRelease", Release("r1", 1, "b1", "1", false).toJson().toString())
        store.putString("hotcodepush.deviceId", "device-1")
        val state = StateStore(store)
        assertEquals("device-1", state.deviceId)
        assertNull(state.currentRelease)
        assertEquals(StateStore.STATE_VERSION, store.getInt("hotcodepush.stateVersion"))
    }

    @Test
    fun shouldDropARollbackNoticeStoredUnderAReasonNameOfVersionTwo() {
        val store = InMemoryStore()
        val notice = JSONObject().put("from", Release("r1", 1, "b1", "1", false).toJson()).put("to", JSONObject.NULL).put("reason", "CRASHED")
        store.putInt("hotcodepush.stateVersion", 2)
        store.putString("hotcodepush.lastRollback", notice.toString())
        store.putString("hotcodepush.pendingRollbackEvent", notice.toString())
        val state = StateStore(store)
        assertNull(state.lastRollback)
        assertNull(state.pendingRollbackEvent)
    }

    @Test
    fun shouldDropTheCacheWhenAValueDoesNotParse() {
        val store = InMemoryStore()
        val state = StateStore(store)
        state.nextRelease = Release("r1", 1, "b1", "1", false)
        store.putString("hotcodepush.currentRelease", "not json")
        assertNull(state.currentRelease)
        assertNull(state.nextRelease)
        assertEquals(36, StateStore(store).deviceId.length)
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
        val kept = BundleManifest(appId = "a", bundleVersion = "1", files = listOf(BundleManifest.File("a", Hashing.sha256Hex("a"), 1)), platforms = listOf("android"))
        val gone = BundleManifest(appId = "a", bundleVersion = "1", files = listOf(BundleManifest.File("b", Hashing.sha256Hex("b"), 1)), platforms = listOf("android"))
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
        val manifest = BundleManifest(appId = "a", bundleVersion = "1", files = listOf(BundleManifest.File("index.html", Hashing.sha256Hex("new"), 3), BundleManifest.File("assets/logo.svg", Hashing.sha256Hex("embedded"), 8)), platforms = listOf("android"))
        val www = File(root, "www")
        BundleProjection.project(manifest, files, embedded, www)
        assertEquals("new", File(www, "index.html").readText())
        assertEquals("embedded", File(www, "assets/logo.svg").readText())
    }
}
