package com.hotcodepush.protocol

import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.security.Signature as PlatformSignature

/**
 * A public key as the resource file of an Android build carries it: the base64 of the key's SubjectPublicKeyInfo DER,
 * which `X509EncodedKeySpec` takes as it is, beside the key id, the fingerprint a signature names its key by.
 */
data class DevicePublicKey(val der: String, val keyId: String) {
    companion object {
        fun fromJson(json: JSONObject) = DevicePublicKey(json.getWireString("der", WireRule.BASE64), json.getWireString("keyId", WireRule.NON_EMPTY))
    }
}

/** Why a signature did not verify; the downloader phrases it. */
enum class SignatureRefusal {
    /** The envelope carries no signature while the app accepts only signed manifests. */
    UNSIGNED,

    /** The signature's prefix names a scheme outside the allow-list, `ed25519` included, or its bytes are not canonical base64. */
    UNKNOWN_SCHEME,

    /** No configured key carries the key id the signature names. */
    UNKNOWN_KEY,

    /** The configured key the signature names is no RSA key the platform imports, or is under the minimum size. */
    UNUSABLE_KEY,

    /** The signature does not verify over the manifest's bytes under the key it names. */
    INVALID,
}

/**
 * Verifies an envelope's signature over the UTF-8 bytes of its `manifest` string, as received and never re-serialized,
 * under the configured key whose id the signature names, with `java.security` alone: RSASSA-PKCS1-v1_5 with SHA-256,
 * which every Android version verifies.
 */
object SignatureVerifier {
    /** The pinned allow-list has one scheme; a value under any other prefix names an unknown one. */
    const val SCHEME = "rsa-v1_5-sha256"

    /** A key under this size verifies nothing; the size is read from the key the platform imported, never from its bytes. */
    const val KEY_BITS_MINIMUM = 2048

    /** `null` when the signature covers the manifest's bytes under a configured key, else why it does not; an unsigned manifest verifies against no key. */
    fun verifyManifestSignature(manifest: String, signature: Signature?, publicKeys: List<DevicePublicKey>): SignatureRefusal? {
        if (signature == null) return SignatureRefusal.UNSIGNED
        val signatureBytes = resolveSignatureBytes(signature.value) ?: return SignatureRefusal.UNKNOWN_SCHEME
        val publicKey = publicKeys.firstOrNull { it.keyId == signature.keyId } ?: return SignatureRefusal.UNKNOWN_KEY
        val importedKey = resolveImportedKey(publicKey) ?: return SignatureRefusal.UNUSABLE_KEY
        return try {
            val verifier = PlatformSignature.getInstance("SHA256withRSA")
            verifier.initVerify(importedKey)
            verifier.update(manifest.toByteArray(Charsets.UTF_8))
            if (verifier.verify(signatureBytes)) null else SignatureRefusal.INVALID
        } catch (exception: GeneralSecurityException) {
            SignatureRefusal.INVALID
        }
    }

    /** The bytes of `rsa-v1_5-sha256:<base64>`; `null` under another prefix. */
    private fun resolveSignatureBytes(value: String): ByteArray? = if (value.startsWith("$SCHEME:")) decodeCanonicalBase64(value.substring(SCHEME.length + 1)) else null

    /** The RSA key the platform imports from the SPKI bytes, of the minimum size or more. */
    private fun resolveImportedKey(publicKey: DevicePublicKey): RSAPublicKey? {
        val der = decodeCanonicalBase64(publicKey.der) ?: return null
        val importedKey = try {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as? RSAPublicKey
        } catch (exception: GeneralSecurityException) {
            null
        }
        return importedKey?.takeIf { it.modulus.bitLength() >= KEY_BITS_MINIMUM }
    }

    /** Base64 in its one spelling: padded, no unused bits set, so one value has exactly one form. */
    private fun decodeCanonicalBase64(text: String): ByteArray? = text.decodeBase64()?.takeIf { it.base64() == text }?.toByteArray()
}
