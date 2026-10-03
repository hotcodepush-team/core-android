package com.hotcodepush.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PackTest {
    @Test
    fun shouldRoundTripEntriesThroughTheUstarFormat() {
        val content = "hello".toByteArray()
        val entries = listOf(PackEntry(Hashing.sha256Hex(content), Gzip.compress(content)), PackEntry(Hashing.sha256Hex("x"), ByteArray(0)))
        val pack = PackWriter.pack(entries)
        assertEquals(0, pack.size % 512)
        val read = PackReader.entries(pack)
        assertEquals(entries.map { it.sha256 }, read.map { it.sha256 })
        assertEquals("hello", String(Gzip.decompress(read[0].body, content.size.toLong())))
    }

    @Test(expected = PackFormatException::class)
    fun shouldRejectATruncatedPack() {
        PackReader.entries(PackWriter.pack(listOf(PackEntry("abc", ByteArray(700)))).copyOf(600))
    }

    @Test
    fun shouldIgnoreBytesAfterTheEndOfArchiveBlocks() {
        val pack = PackWriter.pack(listOf(PackEntry("abc", "x".toByteArray()))) + "trailing".toByteArray()
        assertEquals(listOf("abc"), PackReader.entries(pack).map { it.sha256 })
    }

    @Test(expected = PackFormatException::class)
    fun shouldRefuseAnEntryLargerThanWhatIsLeftOfThePackBeforeAllocatingIt() {
        val pack = PackWriter.pack(listOf(PackEntry("abc", ByteArray(700))))
        "%011o".format(1_500_000_000).toByteArray(Charsets.US_ASCII).copyInto(pack, 124)
        PackReader.entries(pack)
    }

    @Test(expected = PackFormatException::class)
    fun shouldRefuseAnEntryWithANegativeSize() {
        val pack = PackWriter.pack(listOf(PackEntry("abc", ByteArray(700))))
        "-0000001000".toByteArray(Charsets.US_ASCII).copyInto(pack, 124)
        PackReader.entries(pack)
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
        assertEquals(2, store.getInt("hotcodepush.stateVersion"))
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
        val kept = BundleManifest("kept", "a", "1", 0, listOf(BundleManifest.File("a", Hashing.sha256Hex("a"), 1)), null, emptyList())
        val gone = BundleManifest("gone", "a", "1", 0, listOf(BundleManifest.File("b", Hashing.sha256Hex("b"), 1)), null, emptyList())
        files.writeManifest(kept)
        files.writeManifest(gone)
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
        val manifest = BundleManifest("b", "a", "1", 0, listOf(BundleManifest.File("index.html", Hashing.sha256Hex("new"), 3), BundleManifest.File("assets/logo.svg", Hashing.sha256Hex("embedded"), 8)), null, emptyList())
        val www = File(root, "www")
        BundleProjection.project(manifest, files, embedded, www)
        assertEquals("new", File(www, "index.html").readText())
        assertEquals("embedded", File(www, "assets/logo.svg").readText())
    }
}
