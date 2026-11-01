package com.hotcodepush.core

import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.security.Signature as PlatformSignature

/**
 * A public key from the resource file: the base64 SubjectPublicKeyInfo DER, which `X509EncodedKeySpec` takes as it is,
 * and the key id a signature names.
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
    UNLISTED_KEY,

    /** The platform refused to import the configured key as an RSA key: the resource file is wrong, not the manifest. */
    UNIMPORTABLE_KEY,

    /** The configured key is smaller than the minimum. */
    WEAK_KEY,

    /** The signature does not verify over the manifest's bytes under the key it names. */
    MISMATCH,
}

/**
 * Verifies an envelope signature over the UTF-8 bytes of its `manifest` string, as received and never re-serialized.
 * It uses `java.security` alone: RSASSA-PKCS1-v1_5 with SHA-256, which every Android version verifies.
 */
object SignatureVerifier {
    /** The pinned allow-list has one scheme; a value under any other prefix names an unknown one. */
    const val SCHEME = "rsa-v1_5-sha256"

    /** A key under this size verifies nothing; the size is read from the key the platform imported, never from its bytes. */
    const val MINIMUM_KEY_BITS = 2048

    /** Returns `null` when the signature covers the manifest under the configured key it names, else the reason it does not. */
    fun verifyManifestSignature(manifest: String, signature: Signature?, publicKeys: List<DevicePublicKey>): SignatureRefusal? {
        if (signature == null) return SignatureRefusal.UNSIGNED
        val signatureBytes = resolveSignatureBytes(signature.value) ?: return SignatureRefusal.UNKNOWN_SCHEME
        val publicKey = publicKeys.firstOrNull { it.keyId == signature.keyId } ?: return SignatureRefusal.UNLISTED_KEY
        val importedKey = resolveImportedKey(publicKey) ?: return SignatureRefusal.UNIMPORTABLE_KEY
        if (importedKey.modulus.bitLength() < MINIMUM_KEY_BITS) return SignatureRefusal.WEAK_KEY
        return try {
            val verifier = PlatformSignature.getInstance("SHA256withRSA")
            verifier.initVerify(importedKey)
            verifier.update(manifest.toByteArray(Charsets.UTF_8))
            if (verifier.verify(signatureBytes)) null else SignatureRefusal.MISMATCH
        } catch (exception: GeneralSecurityException) {
            SignatureRefusal.MISMATCH
        }
    }

    private fun resolveSignatureBytes(value: String): ByteArray? = if (value.startsWith("$SCHEME:")) decodeCanonicalBase64(value.substring(SCHEME.length + 1)) else null

    private fun resolveImportedKey(publicKey: DevicePublicKey): RSAPublicKey? {
        val der = decodeCanonicalBase64(publicKey.der) ?: return null
        return try {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der)) as? RSAPublicKey
        } catch (exception: GeneralSecurityException) {
            null
        }
    }

    // Only the padded spelling with no unused bits set passes, so one value has exactly one form.
    internal fun decodeCanonicalBase64(text: String): ByteArray? = text.decodeBase64()?.takeIf { it.base64() == text }?.toByteArray()
}
