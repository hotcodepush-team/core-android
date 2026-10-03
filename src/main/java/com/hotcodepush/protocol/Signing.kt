package com.hotcodepush.protocol

import okio.ByteString.Companion.decodeBase64
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.NoSuchAlgorithmException
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** The signature schemes the verifier accepts, pinned: a prefix outside the list verifies nothing. */
enum class SigningScheme(val wire: String) {
    ED25519("ed25519"), RSA_V1_5_SHA256("rsa-v1_5-sha256");

    companion object {
        fun fromWire(value: String): SigningScheme? = entries.firstOrNull { it.wire == value }
    }
}

/** A key or a signature as it travels, `<scheme>:<base64>` decoded: raw bytes for ed25519, SPKI DER for RSA. */
class SelfDescribingBytes(val scheme: SigningScheme, val bytes: ByteArray)

object SigningKeys {
    /** The scheme and the bytes of a self-describing value; `null` for a scheme outside the allow-list or bytes that are not base64. */
    fun parse(value: String): SelfDescribingBytes? {
        val separator = value.indexOf(':')
        if (separator <= 0) return null
        val scheme = SigningScheme.fromWire(value.substring(0, separator)) ?: return null
        val bytes = value.substring(separator + 1).decodeBase64()?.toByteArray() ?: return null
        return SelfDescribingBytes(scheme, bytes)
    }

    /** `sha256:` and the hex SHA-256 of the public key's bytes, the id a signature names its key by. */
    fun fingerprint(publicKey: SelfDescribingBytes): String = "sha256:" + Hashing.sha256Hex(publicKey.bytes)
}

/** Why a signature did not verify; the downloader phrases it. */
enum class SignatureRefusal {
    /** The envelope carries no signature while the app accepts only signed manifests. */
    UNSIGNED,

    /** The signature's prefix names a scheme outside the allow-list, or its bytes are not base64. */
    UNKNOWN_SCHEME,

    /** No configured key has the fingerprint the signature names. */
    UNKNOWN_KEY,

    /** The signature's scheme is not the named key's. */
    SCHEME_MISMATCH,

    /** This runtime has no verifier for the scheme: Ed25519 below Android 13. */
    SCHEME_UNAVAILABLE,

    /** The signature does not verify over the manifest's bytes under the key it names. */
    INVALID,
}

/**
 * Verifies an envelope's signature over the UTF-8 bytes of its `manifest` string, as received and never re-serialized,
 * under the configured key the signature's `keyId` names, with `java.security`'s verifiers: RSA on every Android,
 * Ed25519 where the platform has it, Android 13 and the JVM from 15.
 */
object SignatureVerifier {
    /** The SubjectPublicKeyInfo header of an Ed25519 key, RFC 8410: the raw 32 bytes follow it. */
    private val ED25519_SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    /** The JDK names the algorithm `Ed25519`; a provider may register the family name alone. */
    private val ED25519_ALGORITHM_NAMES = listOf("Ed25519", "EdDSA")

    /** `null` when the signature covers the manifest's bytes under a configured key, else why it does not; an unsigned manifest verifies against no key. */
    fun verifyManifestSignature(manifest: String, signature: com.hotcodepush.protocol.Signature?, publicKeys: List<String>): SignatureRefusal? {
        if (signature == null) return SignatureRefusal.UNSIGNED
        val value = SigningKeys.parse(signature.value) ?: return SignatureRefusal.UNKNOWN_SCHEME
        val publicKey = resolvePublicKey(publicKeys, signature.keyId) ?: return SignatureRefusal.UNKNOWN_KEY
        if (publicKey.scheme != value.scheme) return SignatureRefusal.SCHEME_MISMATCH
        val message = manifest.toByteArray(Charsets.UTF_8)
        return when (publicKey.scheme) {
            SigningScheme.ED25519 -> verifyEd25519(message, value.bytes, publicKey.bytes)
            SigningScheme.RSA_V1_5_SHA256 -> verify("RSA", "SHA256withRSA", publicKey.bytes, message, value.bytes)
        }
    }

    /** Whether this runtime verifies Ed25519 at all, for the debug screen and the message a refusal carries. */
    val isEd25519Available: Boolean
        get() = ED25519_ALGORITHM_NAMES.any { runCatching { Signature.getInstance(it) }.isSuccess }

    private fun resolvePublicKey(publicKeys: List<String>, keyId: String): SelfDescribingBytes? =
        publicKeys.asSequence().mapNotNull(SigningKeys::parse).firstOrNull { SigningKeys.fingerprint(it) == keyId }

    private fun verifyEd25519(message: ByteArray, signatureBytes: ByteArray, rawPublicKey: ByteArray): SignatureRefusal? {
        val encodedKey = ED25519_SPKI_PREFIX + rawPublicKey
        var refusal: SignatureRefusal = SignatureRefusal.SCHEME_UNAVAILABLE
        for (algorithm in ED25519_ALGORITHM_NAMES) {
            refusal = verify(algorithm, algorithm, encodedKey, message, signatureBytes) ?: return null
            if (refusal != SignatureRefusal.SCHEME_UNAVAILABLE) return refusal
        }
        return refusal
    }

    private fun verify(keyAlgorithm: String, signatureAlgorithm: String, encodedKey: ByteArray, message: ByteArray, signatureBytes: ByteArray): SignatureRefusal? = try {
        val publicKey = KeyFactory.getInstance(keyAlgorithm).generatePublic(X509EncodedKeySpec(encodedKey))
        val verifier = Signature.getInstance(signatureAlgorithm)
        verifier.initVerify(publicKey)
        verifier.update(message)
        if (verifier.verify(signatureBytes)) null else SignatureRefusal.INVALID
    } catch (exception: NoSuchAlgorithmException) {
        SignatureRefusal.SCHEME_UNAVAILABLE
    } catch (exception: GeneralSecurityException) {
        SignatureRefusal.INVALID
    } catch (exception: IllegalArgumentException) {
        SignatureRefusal.INVALID
    }
}
