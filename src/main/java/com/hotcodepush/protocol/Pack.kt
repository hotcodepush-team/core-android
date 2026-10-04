package com.hotcodepush.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * One entry of a pack: a file's stored object, gzip as the bucket serves it, named by the file's content hash, or a
 * BSDIFF40 patch that turns the file `fromSha256` into the file `toSha256`.
 */
sealed interface PackEntry {
    val body: ByteArray

    data class File(val sha256: String, override val body: ByteArray) : PackEntry

    data class Patch(val fromSha256: String, val toSha256: String, override val body: ByteArray) : PackEntry
}

class PackFormatException(message: String) : Exception(message)

class GzipSizeException(maximumBytes: Long) : Exception("The content inflates past its $maximumBytes bytes")

/**
 * Reads the pack format: an uncompressed ustar archive of file entries named by their content hash and patch entries named
 * `patches/{from}/{to}` through the ustar prefix, whose end is two zero blocks.
 */
object PackReader {
    private const val BLOCK_SIZE = 512
    private val checksumField = 148 until 156
    private val nameField = 0 until 100
    private val prefixField = 345 until 500
    private val sizeField = 124 until 136

    fun entries(bytes: ByteArray): List<PackEntry> = buildList { forEachEntry(ByteArrayInputStream(bytes), bytes.size.toLong()) { add(it) } }

    /**
     * Reads the `length` bytes of the input entry after entry up to the two end-of-archive blocks: a pack that ends before them is refused,
     * a header whose checksum does not add up is refused, an entry named neither by a content hash nor `patches/{hash}/{hash}` is skipped
     * with its body, so a later kind does not break this reader, anything after the end is ignored, and an entry the header claims larger
     * than what is left is refused before anything is allocated for it.
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
            val size = field(header, sizeField).toIntOrNull(8)?.takeIf { it >= 0 } ?: throw PackFormatException("Invalid size field")
            val padding = (BLOCK_SIZE - size % BLOCK_SIZE) % BLOCK_SIZE
            if (size > remaining) throw PackFormatException("Truncated pack")
            remaining -= size + padding
            val entry = resolveEntry(resolveName(header)) {
                ByteArray(size).also { content -> if (input.readFully(content) < size) throw PackFormatException("Truncated pack") }
            }
            if (entry == null) {
                if (input.skipFully(size) < size) throw PackFormatException("Truncated pack")
            } else {
                body(entry)
            }
            if (input.skipFully(padding) < padding) throw PackFormatException("Truncated pack")
        }
    }

    /** The entry's full name: `prefix/name` when the prefix field holds one. */
    private fun resolveName(header: ByteArray): String {
        val name = field(header, nameField)
        val prefix = field(header, prefixField)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    /** The kind a full name gives an entry, `null` for a name of neither kind; the body is read only for an entry kept. */
    private fun resolveEntry(name: String, readBody: () -> ByteArray): PackEntry? {
        if (WireRule.SHA256.accepts(name)) return PackEntry.File(name, readBody())
        val segments = name.split('/')
        if (segments.size != 3 || segments[0] != "patches" || !WireRule.SHA256.accepts(segments[1]) || !WireRule.SHA256.accepts(segments[2])) return null
        return PackEntry.Patch(segments[1], segments[2], readBody())
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
    private fun InputStream.skipFully(count: Int): Int {
        val buffer = ByteArray(minOf(count, 64 * 1024))
        var total = 0
        while (total < count) {
            val read = read(buffer, 0, minOf(buffer.size, count - total))
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
