package com.hotcodepush.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** The protocol's signature fixtures and the refusals behind the ones that do not verify. */
class SigningTest {
    private val fixture = SignatureFixtures()

    @Test
    fun shouldMatchEverySignatureFixture() {
        assertTrue(fixture.manifests.size > 5)
        for (case in fixture.manifests) {
            assertEquals(case.getString("name"), case.getBoolean("isValid"), fixture.verify(case) == null)
        }
    }

    @Test
    fun shouldFingerprintEveryKeyFixture() {
        assertTrue(fixture.keys.size >= 3)
        for (key in fixture.keys) {
            val parsed = SigningKeys.parse(key.getString("publicKey")) ?: throw AssertionError(key.getString("name"))
            assertEquals(key.getString("scheme"), parsed.scheme.wire)
            assertEquals(key.getString("fingerprint"), SigningKeys.fingerprint(parsed))
        }
    }

    @Test
    fun shouldNameWhyAFixtureDoesNotVerify() {
        val expected = mapOf(
            "should refuse a manifest changed after signing" to SignatureRefusal.INVALID,
            "should refuse a signature changed after signing" to SignatureRefusal.INVALID,
            "should refuse an unsigned manifest" to SignatureRefusal.UNSIGNED,
            "should refuse a signature by a key not in the list" to SignatureRefusal.UNKNOWN_KEY,
            "should refuse a signature checked against the key its keyId names when another listed key made it" to SignatureRefusal.INVALID,
            "should refuse a scheme outside the allow-list" to SignatureRefusal.UNKNOWN_SCHEME,
            "should refuse a prefix naming another scheme than the key's" to SignatureRefusal.SCHEME_MISMATCH,
            "should refuse every signature when no key is listed" to SignatureRefusal.UNKNOWN_KEY,
        )
        for (case in fixture.manifests.filter { !it.getBoolean("isValid") }) {
            val name = case.getString("name")
            assertEquals(name, expected[name] ?: throw AssertionError("A refused fixture this test does not know: $name"), fixture.verify(case))
        }
    }

    @Test
    fun shouldVerifyASignatureMadeWithAFixtureKeyOnThisRuntime() {
        val manifest = Fixture.release(1, "b2", "<html>v2</html>".toByteArray()).envelope.manifest
        for (keyName in listOf("ed25519-a", "rsa-v1_5-sha256-a")) {
            assertNull(keyName, SignatureVerifier.verifyManifestSignature(manifest, fixture.sign(manifest, keyName), listOf(fixture.publicKey(keyName))))
        }
    }

    @Test
    fun shouldHaveEd25519OnTheTestRuntime() {
        assertTrue(SignatureVerifier.isEd25519Available)
    }
}

/** The test keys of `fixtures/signatures.json`, and a signer over them through the JVM's providers. */
class SignatureFixtures {
    private val document = JSONObject(File("node_modules/@hotcodepush/protocol/fixtures/signatures.json").readText())
    val keys: List<JSONObject> = document.getJSONArray("keys").let { array -> List(array.length()) { array.getJSONObject(it) } }
    val manifests: List<JSONObject> = document.getJSONArray("manifests").let { array -> List(array.length()) { array.getJSONObject(it) } }

    fun publicKey(name: String): String = key(name).getString("publicKey")

    /** A `manifests` case through the verifier: the signed half of its envelope against its keys. */
    fun verify(case: JSONObject): SignatureRefusal? {
        val envelope = case.getJSONObject("envelope")
        val signature = envelope.getNullableObject("signature")?.let(Signature::fromJson)
        return SignatureVerifier.verifyManifestSignature(envelope.getString("manifest"), signature, case.getJSONArray("publicKeys").toStringList())
    }

    /** The signature over the manifest string under the named key, as the CLI would make it. */
    fun sign(manifest: String, name: String): Signature {
        val key = key(name)
        val privateKey = SigningKeys.parse(key.getString("privateKey")) ?: throw AssertionError(name)
        val (keyAlgorithm, signatureAlgorithm) = when (privateKey.scheme) {
            SigningScheme.ED25519 -> "Ed25519" to "Ed25519"
            SigningScheme.RSA_V1_5_SHA256 -> "RSA" to "SHA256withRSA"
        }
        val signer = java.security.Signature.getInstance(signatureAlgorithm)
        signer.initSign(KeyFactory.getInstance(keyAlgorithm).generatePrivate(PKCS8EncodedKeySpec(privateKey.bytes)))
        signer.update(manifest.toByteArray(Charsets.UTF_8))
        return Signature(key.getString("fingerprint"), "${privateKey.scheme.wire}:${Base64.getEncoder().encodeToString(signer.sign())}")
    }

    private fun key(name: String) = keys.first { it.getString("name") == name }
}
