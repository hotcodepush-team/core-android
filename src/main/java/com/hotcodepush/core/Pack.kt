package com.hotcodepush.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/**
 * One entry of a pack, by its name: a file's stored object, gzip as the bucket serves it, named by the file's content hash,
 * or a BSDIFF40 patch that turns the file `fromSha256` into the file `toSha256`. Its body streams through `PackReader`.
 */
sealed interface PackEntry {
    data class File(val sha256: String) : PackEntry

    data class Patch(val fromSha256: String, val toSha256: String) : PackEntry
}

class PackFormatException(message: String) : Exception(message)

class GzipSizeException(maximumBytes: Long) : Exception("The content inflates past its $maximumBytes bytes")

/**
 * Reads the pack format: an uncompressed ustar archive of file entries named by their content hash and patch entries named
 * `patches/{from}/{to}` through the ustar prefix, whose end is two zero blocks.
 */
object PackReader {
    private const val BLOCK_SIZE = 512
    private const val CHUNK_SIZE = 64 * 1024
    private val checksumField = 148 until 156
    private val nameField = 0 until 100
    private val prefixField = 345 until 500
    private val sizeField = 124 until 136

    /**
     * Reads the `length` bytes of the input entry after entry up to the two end-of-archive blocks: a pack that ends before them is refused,
     * a header whose checksum does not add up is refused, an entry named neither by a content hash nor `patches/{hash}/{hash}` is skipped
     * with its body, so a later kind does not break this reader, anything after the end is ignored, and an entry the header claims larger
     * than what is left is refused before any of it is read.
     *
     * `read` receives each entry with its body as a stream of exactly the entry's size, which refuses a pack cut inside it; what `read`
     * leaves unread is skipped. A read holds one header and one chunk in memory, however large the pack and its entries are.
     */
    fun forEachEntry(input: InputStream, length: Long, read: (PackEntry, InputStream) -> Unit) {
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
            val size = field(header, sizeField).toLongOrNull(8)?.takeIf { it >= 0 } ?: throw PackFormatException("Invalid size field")
            val padding = (BLOCK_SIZE - size % BLOCK_SIZE) % BLOCK_SIZE
            if (size > remaining) throw PackFormatException("Truncated pack")
            remaining -= size + padding
            val body = EntryBodyInputStream(input, size)
            resolveEntry(resolveName(header))?.let { entry -> read(entry, body) }
            body.skipRemaining()
            if (input.skipFully(padding) < padding) throw PackFormatException("Truncated pack")
        }
    }

    /** The entry's full name: `prefix/name` when the prefix field holds one. */
    private fun resolveName(header: ByteArray): String {
        val name = field(header, nameField)
        val prefix = field(header, prefixField)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    /** The kind a full name gives an entry, `null` for a name of neither kind. */
    private fun resolveEntry(name: String): PackEntry? {
        if (WireRule.SHA256.accepts(name)) return PackEntry.File(name)
        val segments = name.split('/')
        if (segments.size != 3 || segments[0] != "patches" || !WireRule.SHA256.accepts(segments[1]) || !WireRule.SHA256.accepts(segments[2])) return null
        return PackEntry.Patch(segments[1], segments[2])
    }

    /** The ustar checksum: the sum of the header's bytes with the checksum field read as spaces, stored in octal. */
    private fun verifyChecksum(header: ByteArray) {
        val expected = field(header, checksumField).toIntOrNull(8) ?: throw PackFormatException("Invalid checksum field")
        val actual = header.indices.sumOf { index -> if (index in checksumField) 0x20 else header[index].toInt() and 0xff }
        if (actual != expected) throw PackFormatException("The header's checksum does not add up")
    }

    private fun field(header: ByteArray, range: IntRange): String {
        val end = range.firstOrNull { header[it] == 0.toByte() } ?: (range.last + 1)
        return String(header, range.first, end - range.first, Charsets.US_ASCII).trim()
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

    /** Reads past `count` bytes a chunk at a time, never holding more than one chunk, and returns how many there were. */
    private fun InputStream.skipFully(count: Long): Long {
        val buffer = ByteArray(minOf(count, CHUNK_SIZE.toLong()).toInt())
        var total = 0L
        while (total < count) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), count - total).toInt())
            if (read < 0) break
            total += read
        }
        return total
    }

    /** An entry's body: exactly `size` bytes of the pack, a cut inside them a format error, closing it leaving the pack open. */
    private class EntryBodyInputStream(private val pack: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            if (length == 0) return 0
            val read = pack.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read < 0) throw PackFormatException("Truncated pack")
            remaining -= read
            return read
        }

        fun skipRemaining() {
            if (pack.skipFully(remaining) < remaining) throw PackFormatException("Truncated pack")
            remaining = 0
        }

        override fun close() = Unit
    }
}

/** Decodes a pack entry, the gzip bytes the bucket serves, into the file's content. */
object Gzip {
    /**
     * The content the gzip body inflates to, as a stream that stops past `maximumBytes`, the file's size: a few bytes that
     * would inflate to gigabytes are refused on the way. An empty body is an empty file.
     */
    fun inflate(body: InputStream, maximumBytes: Long): InputStream {
        val input = PushbackInputStream(body, 1)
        val first = input.read()
        if (first < 0) return ByteArrayInputStream(ByteArray(0))
        input.unread(first)
        return SizeCappedInputStream(GZIPInputStream(input), maximumBytes)
    }

    private class SizeCappedInputStream(private val input: InputStream, private val maximumBytes: Long) : InputStream() {
        private var count = 0L

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = input.read(buffer, offset, length)
            if (read > 0) {
                count += read
                if (count > maximumBytes) throw GzipSizeException(maximumBytes)
            }
            return read
        }

        override fun close() = input.close()
    }
}
