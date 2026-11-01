package com.hotcodepush.core

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import java.io.File
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec

// The device passes the document from its assets, the JVM reads it from node_modules.
class SignatureFixtures(document: JSONObject = JSONObject(File("node_modules/@hotcodepush/protocol/fixtures/signatures.json").readText())) {
    private val keys: List<JSONObject> = document.getJSONArray("keys").map { it }
    private val manifests: List<JSONObject> = document.getJSONArray("manifests").map { it }

    fun assertTheRefusalOfEveryManifest() {
        for (case in manifests) {
            val name = case.getString("name")
            val expected = if (case.getBoolean("isValid")) null else refusals[name] ?: throw AssertionError("A refused fixture this test does not know: $name")
            assertEquals(name, expected, verify(case))
        }
    }

    fun devicePublicKey(name: String): DevicePublicKey = key(name).let { DevicePublicKey(it.getString("publicKey").substringAfter(':'), it.getString("keyId")) }

    fun sign(manifest: String, name: String): Signature {
        val key = key(name)
        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(key.getString("privateKey").decodeBase64()!!.toByteArray())))
        signer.update(manifest.toByteArray(Charsets.UTF_8))
        return Signature(key.getString("keyId"), "${SignatureVerifier.SCHEME}:${signer.sign().toByteString().base64()}")
    }

    private fun key(name: String) = keys.first { it.getString("name") == name }

    private fun verify(case: JSONObject): SignatureRefusal? {
        val envelope = case.getJSONObject("envelope")
        val signature = envelope.getNullableObject("signature")?.let(Signature::fromJson)
        val publicKeys = case.getJSONObject("devicePublicKeys").getJSONArray("android").map(DevicePublicKey::fromJson)
        return SignatureVerifier.verifyManifestSignature(envelope.getString("manifest"), signature, publicKeys)
    }

    private companion object {
        val refusals = mapOf(
            "should refuse a manifest changed after signing" to SignatureRefusal.MISMATCH,
            "should refuse a signature changed after signing" to SignatureRefusal.MISMATCH,
            "should refuse an unsigned manifest" to SignatureRefusal.UNSIGNED,
            "should refuse a signature by a key not in the list" to SignatureRefusal.UNLISTED_KEY,
            "should refuse a signature checked against the key its keyId names when another listed key made it" to SignatureRefusal.MISMATCH,
            "should refuse a scheme outside the allow-list, ed25519" to SignatureRefusal.UNKNOWN_SCHEME,
            "should refuse a signature by a key under 2048 bits" to SignatureRefusal.WEAK_KEY,
            "should refuse every signature when no key is listed" to SignatureRefusal.UNLISTED_KEY,
        )
    }
}
