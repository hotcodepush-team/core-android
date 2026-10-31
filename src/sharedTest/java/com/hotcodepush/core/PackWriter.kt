package com.hotcodepush.core

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

data class PackedEntry(val entry: PackEntry, val body: ByteArray) {
    companion object {
        fun file(sha256: String, body: ByteArray) = PackedEntry(PackEntry.File(sha256), body)

        fun patch(fromSha256: String, toSha256: String, body: ByteArray) = PackedEntry(PackEntry.Patch(fromSha256, toSha256), body)
    }
}

// Writes packs as the CLI and the edge Worker do, with a patch entry's `patches/{from}` in the ustar prefix.
object PackWriter {
    fun pack(entries: List<PackedEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        for ((entry, body) in entries) {
            when (entry) {
                is PackEntry.File -> writeEntry(output, "", entry.sha256, body)
                is PackEntry.Patch -> writeEntry(output, "patches/${entry.fromSha256}", entry.toSha256, body)
            }
        }
        output.write(ByteArray(1024))
        return output.toByteArray()
    }

    fun writeEntry(output: ByteArrayOutputStream, prefix: String, name: String, body: ByteArray) {
        output.write(header(prefix, name, body.size.toLong()))
        output.write(body)
        output.write(ByteArray((512 - body.size % 512) % 512))
    }

    fun header(prefix: String, name: String, size: Long): ByteArray {
        val header = ByteArray(512)
        name.toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        // The writers set the ustar magic only beside a prefix.
        if (prefix.isNotEmpty()) {
            "ustar\u000000".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
            prefix.toByteArray(Charsets.US_ASCII).copyInto(header, 345)
        }
        "%011o".format(size).toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        for (index in 148 until 156) header[index] = 0x20
        val checksum = header.sumOf { it.toInt() and 0xff }
        ("%06o".format(checksum) + "\u0000").toByteArray(Charsets.US_ASCII).copyInto(header, 148)
        return header
    }
}

// Reads every body whole, which only a test's small packs afford.
fun PackReader.entries(pack: ByteArray): List<PackedEntry> = buildList { forEachEntry(pack.inputStream(), pack.size.toLong()) { entry, body -> add(PackedEntry(entry, body.readBytes())) } }

fun Gzip.compress(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()

fun Gzip.decompress(bytes: ByteArray, maximumBytes: Long): ByteArray = inflate(bytes.inputStream(), maximumBytes).use { it.readBytes() }

fun FileStore.writeFile(content: ByteArray, sha256: String) = writeFile(content.inputStream(), sha256)
