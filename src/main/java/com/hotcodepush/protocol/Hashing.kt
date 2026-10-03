package com.hotcodepush.protocol

import java.security.MessageDigest

object Hashing {
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /** `sha256(key + '\0' + value)`, the form an attribute condition carries. */
    fun attributeHash(key: String, value: String): String = sha256Hex(key + "\u0000" + value)

    /** The hash a `device` condition lists for one device id. */
    fun deviceIdHash(deviceId: String): String = sha256Hex(deviceId)

    /** The rollout bucket: FNV-1a 32-bit over the UTF-8 bytes of the device id followed by the release id, modulo 100. */
    fun rolloutBucket(deviceId: String, releaseId: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (byte in (deviceId + releaseId).toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xff)
            hash *= 0x01000193
        }
        return ((hash.toLong() and 0xffffffffL) % 100).toInt()
    }
}
