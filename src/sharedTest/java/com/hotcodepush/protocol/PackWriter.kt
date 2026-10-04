package com.hotcodepush.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Writes the pack format the SDK reads, as the CLI and the edge Worker do: a patch entry's `patches/{from}` in the ustar prefix. */
object PackWriter {
    fun pack(entries: List<PackEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        for (entry in entries) {
            when (entry) {
                is PackEntry.File -> writeEntry(output, "", entry.sha256, entry.body)
                is PackEntry.Patch -> writeEntry(output, "patches/${entry.fromSha256}", entry.toSha256, entry.body)
            }
        }
        output.write(ByteArray(1024))
        return output.toByteArray()
    }

    /** One entry under any name, the ustar magic only beside a prefix as the writers set it. */
    fun writeEntry(output: ByteArrayOutputStream, prefix: String, name: String, body: ByteArray) {
        val header = ByteArray(512)
        name.toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        if (prefix.isNotEmpty()) {
            "ustar\u000000".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
            prefix.toByteArray(Charsets.US_ASCII).copyInto(header, 345)
        }
        "%011o".format(body.size).toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        for (index in 148 until 156) header[index] = 0x20
        val checksum = header.sumOf { it.toInt() and 0xff }
        ("%06o".format(checksum) + "\u0000").toByteArray(Charsets.US_ASCII).copyInto(header, 148)
        output.write(header)
        output.write(body)
        output.write(ByteArray((512 - body.size % 512) % 512))
    }
}

/** Gzip as the CLI writes every file it uploads. */
fun Gzip.compress(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()
