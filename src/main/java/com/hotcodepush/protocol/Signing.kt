package com.hotcodepush.protocol

import okio.ByteString.Companion.decodeBase64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * The signature schemes this core verifies: a pinned allow-list the value's prefix selects within and never extends.
 * Ed25519 is the platform's default; `rsa-v1_5-sha256` belongs to the Expo bridge, whose clients verify the Expo-format
 * manifest themselves, so a key or a signature of that scheme verifies nothing here.
 */
enum class SigningScheme(val wire: String) {
    ED25519("ed25519");

    companion object {
        fun fromWire(value: String): SigningScheme? = entries.firstOrNull { it.wire == value }
    }
}

/** A key or a signature as it travels, `<scheme>:<base64>` decoded: the raw bytes of an Ed25519 key or signature. */
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

    /** The signature does not verify over the manifest's bytes under the key it names. */
    INVALID,
}

/**
 * Verifies an envelope's signature over the UTF-8 bytes of its `manifest` string, as received and never re-serialized,
 * under the configured key the signature's `keyId` names. One code path on every Android version: BouncyCastle's Ed25519
 * through its lightweight API, with no provider registered and the platform's algorithm lookup never asked, since the
 * platform's own Ed25519 takes a key from outside the keystore on the newest releases alone.
 */
object SignatureVerifier {
    /** `null` when the signature covers the manifest's bytes under a configured key, else why it does not; an unsigned manifest verifies against no key. */
    fun verifyManifestSignature(manifest: String, signature: Signature?, publicKeys: List<String>): SignatureRefusal? {
        if (signature == null) return SignatureRefusal.UNSIGNED
        val value = SigningKeys.parse(signature.value) ?: return SignatureRefusal.UNKNOWN_SCHEME
        val publicKey = resolvePublicKey(publicKeys, signature.keyId) ?: return SignatureRefusal.UNKNOWN_KEY
        return when (publicKey.scheme) {
            SigningScheme.ED25519 -> verifyEd25519(manifest.toByteArray(Charsets.UTF_8), value.bytes, publicKey.bytes)
        }
    }

    private fun resolvePublicKey(publicKeys: List<String>, keyId: String): SelfDescribingBytes? =
        publicKeys.asSequence().mapNotNull(SigningKeys::parse).firstOrNull { SigningKeys.fingerprint(it) == keyId }

    /** A key that is not thirty-two bytes or not a point of the curve verifies nothing. */
    private fun verifyEd25519(message: ByteArray, signatureBytes: ByteArray, rawPublicKey: ByteArray): SignatureRefusal? = try {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(rawPublicKey))
        verifier.update(message, 0, message.size)
        if (verifier.verifySignature(signatureBytes)) null else SignatureRefusal.INVALID
    } catch (exception: IllegalArgumentException) {
        SignatureRefusal.INVALID
    }
}
