package com.hotcodepush.core

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * FreeBSD's bspatch on the patches `src/androidTest/make-bspatch-fixtures.sh` writes, through the native library of the
 * device's ABI: a patch arrives unsigned, so a hostile one must end in an error or in bytes the hash check refuses, never
 * in a read or write outside a buffer.
 */
@RunWith(AndroidJUnit4::class)
class BspatchTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val directory = File(context.cacheDir, "bspatch-${System.nanoTime()}").apply { mkdirs() }
    private val old = File(directory, "old").apply { writeBytes(fixture("old.bin")) }
    private val new = File(directory, "new")

    @After
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun shouldApplyAPatchMadeByBsdiff() {
        apply(fixture("valid.patch"))
        assertArrayEquals(fixture("new.bin"), new.readBytes())
    }

    @Test
    fun shouldFailWhenTheHeaderCarriesANewSizeNoMemoryHolds() {
        val patch = settingOffset(Long.MAX_VALUE / 4, NEW_SIZE_OFFSET, fixture("valid.patch"))
        // A 32-bit ABI's off_t cannot hold the size, which makes it a corrupt patch there before any allocation.
        val expected = if (Process.is64Bit()) Bspatch.Failure.OUT_OF_MEMORY else Bspatch.Failure.CORRUPT_PATCH
        assertRefused(patch, expected, maximumBytes = Long.MAX_VALUE)
    }

    @Test
    fun shouldFailWhenTheOldFileIsMissing() {
        old.delete()
        assertRefused(fixture("valid.patch"), Bspatch.Failure.IO_ERROR)
    }

    @Test
    fun shouldReadNothingWhenAControlTripleSeeksBeforeTheOldFile() {
        apply(fixture("seek-before-old-file.patch"))
        assertArrayEquals(ByteArray(32), new.readBytes())
    }

    @Test
    fun shouldReadNothingWhenAControlTripleSeeksPastTheOldFile() {
        apply(fixture("seek-past-old-file.patch"))
        assertArrayEquals(ByteArray(32), new.readBytes())
    }

    @Test
    fun shouldRefuseThePatchAndLeaveNoFileWhenItIsCutInsideABlock() {
        val patch = fixture("valid.patch")
        assertRefused(patch.copyOf(patch.size - 40), Bspatch.Failure.CORRUPT_PATCH)
        assertFalse(new.exists())
    }

    @Test
    fun shouldRefuseThePatchWhenAControlTripleCarriesALengthPast32Bits() {
        assertRefused(fixture("length-past-32-bits.patch"), Bspatch.Failure.CORRUPT_PATCH)
    }

    @Test
    fun shouldRefuseThePatchWhenAControlTripleWritesTheDiffPastTheNewFile() {
        assertRefused(fixture("diff-past-new-file.patch"), Bspatch.Failure.CORRUPT_PATCH)
    }

    @Test
    fun shouldRefuseThePatchWhenAControlTripleWritesTheExtraPastTheNewFile() {
        assertRefused(fixture("extra-past-new-file.patch"), Bspatch.Failure.CORRUPT_PATCH)
    }

    @Test
    fun shouldRefuseThePatchWhenItIsCutInsideTheHeader() {
        assertRefused(fixture("valid.patch").copyOf(20), Bspatch.Failure.CORRUPT_PATCH)
    }

    @Test
    fun shouldRefuseThePatchWhenTheHeaderCarriesAHugeDiffLength() {
        assertRefused(settingOffset(Long.MAX_VALUE, DIFF_LENGTH_OFFSET, fixture("valid.patch")), Bspatch.Failure.CORRUPT_PATCH)
    }

    @Test
    fun shouldRefuseThePatchWhenTheHeaderCarriesANegativeControlLength() {
        assertRefused(settingOffset(-1, CONTROL_LENGTH_OFFSET, fixture("valid.patch")), Bspatch.Failure.CORRUPT_PATCH)
    }

    @Test
    fun shouldRefuseThePatchWhenTheHeaderCarriesANegativeNewSize() {
        assertRefused(settingOffset(-1, NEW_SIZE_OFFSET, fixture("valid.patch")), Bspatch.Failure.CORRUPT_PATCH)
    }

    @Test
    fun shouldRefuseThePatchWhenTheHeaderCarriesANewSizeAboveTheBound() {
        assertRefused(fixture("valid.patch"), Bspatch.Failure.CORRUPT_PATCH, maximumBytes = fixture("new.bin").size - 1L)
    }

    @Test
    fun shouldRefuseThePatchWhenTheMagicIsWrong() {
        val patch = fixture("valid.patch")
        "BSDIFF41".toByteArray().copyInto(patch)
        assertRefused(patch, Bspatch.Failure.CORRUPT_PATCH)
    }

    private fun fixture(name: String): ByteArray = context.assets.open("bspatch/$name").use { it.readBytes() }

    private fun apply(patch: ByteArray, maximumBytes: Long = 1_000_000) {
        val patchFile = File(directory, "patch").apply { writeBytes(patch) }
        Bspatch.apply(patchFile, old, new, maximumBytes)
    }

    private fun assertRefused(patch: ByteArray, expected: Bspatch.Failure, maximumBytes: Long = 1_000_000) {
        assertEquals(expected, assertThrows(BspatchException::class.java) { apply(patch, maximumBytes) }.failure)
    }

    /** The patch with one header field set the way bsdiff writes an offset: eight bytes little-endian, the sign in the top bit. */
    private fun settingOffset(value: Long, offset: Int, patch: ByteArray): ByteArray {
        val magnitude = if (value < 0) -value else value
        for (index in 0 until 8) patch[offset + index] = (magnitude ushr (8 * index)).toByte()
        if (value < 0) patch[offset + 7] = (patch[offset + 7].toInt() or 0x80).toByte()
        return patch
    }

    private companion object {
        const val CONTROL_LENGTH_OFFSET = 8
        const val DIFF_LENGTH_OFFSET = 16
        const val NEW_SIZE_OFFSET = 24
    }
}
