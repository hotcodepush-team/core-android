package com.hotcodepush.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Writes the pack format the SDK reads, as the CLI and the edge Worker do. */
object PackWriter {
    fun pack(entries: List<PackEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        for (entry in entries) {
            val header = ByteArray(512)
            entry.sha256.toByteArray(Charsets.US_ASCII).copyInto(header, 0)
            "%011o".format(entry.body.size).toByteArray(Charsets.US_ASCII).copyInto(header, 124)
            for (index in 148 until 156) header[index] = 0x20
            val checksum = header.sumOf { it.toInt() and 0xff }
            ("%06o".format(checksum) + "\u0000").toByteArray(Charsets.US_ASCII).copyInto(header, 148)
            output.write(header)
            output.write(entry.body)
            output.write(ByteArray((512 - entry.body.size % 512) % 512))
        }
        output.write(ByteArray(1024))
        return output.toByteArray()
    }
}

/** Gzip as the CLI writes every file it uploads. */
fun Gzip.compress(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()
