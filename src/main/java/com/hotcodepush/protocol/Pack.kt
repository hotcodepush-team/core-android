package com.hotcodepush.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** One entry of a pack: the file's hash and its stored bytes, gzip as the bucket serves them. */
data class PackEntry(val sha256: String, val body: ByteArray)

class PackFormatException(message: String) : Exception(message)

class GzipSizeException(maximumBytes: Long) : Exception("The content inflates past its $maximumBytes bytes")

/** Reads the pack format: an uncompressed ustar archive whose entries are named by their content hash and whose end is two zero blocks. */
object PackReader {
    private const val BLOCK_SIZE = 512
    private val checksumField = 148 until 156

    fun entries(bytes: ByteArray): List<PackEntry> = buildList { forEachEntry(ByteArrayInputStream(bytes), bytes.size.toLong()) { add(it) } }

    /**
     * Reads the `length` bytes of the input entry after entry up to the two end-of-archive blocks: a pack that ends before them is refused,
     * a header whose checksum does not add up is refused, anything after the end is ignored, and an entry the header claims larger than
     * what is left is refused before anything is allocated for it.
     */
    fun forEachEntry(input: InputStream, length: Long, body: (PackEntry) -> Unit) {
        val header = ByteArray(BLOCK_SIZE)
        var remaining = length
        while (true) {
            if (input.readFully(header) < BLOCK_SIZE) throw PackFormatException("The pack ends before its end-of-archive blocks")
            remaining -= BLOCK_SIZE
            if (header.isZero()) {
                if (input.readFully(header) < BLOCK_SIZE || !header.isZero()) throw PackFormatException("A zero block is not followed by the second end-of-archive block")
                return
            }
            verifyChecksum(header)
            val name = field(header, 0, 100)
            val size = field(header, 124, 12).toIntOrNull(8)?.takeIf { it >= 0 } ?: throw PackFormatException("Invalid size field")
            val padding = (BLOCK_SIZE - size % BLOCK_SIZE) % BLOCK_SIZE
            if (size > remaining) throw PackFormatException("Truncated pack")
            remaining -= size + padding
            val content = ByteArray(size)
            if (input.readFully(content) < size) throw PackFormatException("Truncated pack")
            body(PackEntry(name, content))
            if (padding > 0 && input.readFully(ByteArray(padding)) < padding) throw PackFormatException("Truncated pack")
        }
    }

    /** The ustar checksum: the sum of the header's bytes with the checksum field read as spaces, stored in octal. */
    private fun verifyChecksum(header: ByteArray) {
        val expected = field(header, checksumField.first, checksumField.count()).toIntOrNull(8) ?: throw PackFormatException("Invalid checksum field")
        val actual = header.indices.sumOf { index -> if (index in checksumField) 0x20 else header[index].toInt() and 0xff }
        if (actual != expected) throw PackFormatException("The header's checksum does not add up")
    }

    private fun field(header: ByteArray, start: Int, length: Int): String {
        val end = (start until start + length).firstOrNull { header[it] == 0.toByte() } ?: (start + length)
        return String(header, start, end - start, Charsets.US_ASCII).trim()
    }

    private fun ByteArray.isZero(): Boolean = all { it == 0.toByte() }

    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}

/** Decodes a pack entry, the gzip bytes the bucket serves, into the file's content. */
object Gzip {
    /** Inflates at most `maximumBytes`, the file's size: a few bytes that would inflate to gigabytes are refused on the way. */
    fun decompress(bytes: ByteArray, maximumBytes: Long): ByteArray {
        if (bytes.isEmpty()) return bytes
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return output.toByteArray()
                if (output.size() + read > maximumBytes) throw GzipSizeException(maximumBytes)
                output.write(buffer, 0, read)
            }
        }
    }
}
