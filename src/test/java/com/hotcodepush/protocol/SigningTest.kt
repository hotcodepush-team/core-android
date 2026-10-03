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

/** The protocol's signature fixtures within this core's allow-list, and the refusals behind the ones that do not verify here. */
class SigningTest {
    private val fixture = SignatureFixtures()

    /** The suite's verdicts, case for case, within the allow-list: a signature of the Expo bridge's RSA scheme is outside it and verifies nothing here, whatever the suite says of a verifier that holds both schemes. */
    @Test
    fun shouldMatchEverySignatureFixtureWithinTheAllowList() {
        assertTrue(fixture.manifests.size > 5)
        var verified = 0
        for (case in fixture.manifests) {
            val isValid = fixture.verify(case) == null
            assertEquals(case.getString("name"), case.getBoolean("isValid") && fixture.isWithinAllowList(case), isValid)
            if (isValid) verified++
        }
        assertTrue(verified >= 2)
    }

    @Test
    fun shouldFingerprintEveryKeyOfTheAllowListAndParseNoOther() {
        assertTrue(fixture.keys.size >= 3)
        for (key in fixture.keys) {
            val parsed = SigningKeys.parse(key.getString("publicKey"))
            if (SigningScheme.fromWire(key.getString("scheme")) == null) {
                assertNull(key.getString("name"), parsed)
            } else {
                assertEquals(key.getString("fingerprint"), parsed?.let(SigningKeys::fingerprint))
            }
        }
    }

    @Test
    fun shouldNameWhyAFixtureDoesNotVerify() {
        val expected = mapOf(
            "should accept a manifest signed with rsa-v1_5-sha256 by a listed key" to SignatureRefusal.UNKNOWN_SCHEME,
            "should refuse a manifest changed after signing" to SignatureRefusal.INVALID,
            "should refuse a signature changed after signing" to SignatureRefusal.INVALID,
            "should refuse an unsigned manifest" to SignatureRefusal.UNSIGNED,
            "should refuse a signature by a key not in the list" to SignatureRefusal.UNKNOWN_KEY,
            "should refuse a signature checked against the key its keyId names when another listed key made it" to SignatureRefusal.INVALID,
            "should refuse a scheme outside the allow-list" to SignatureRefusal.UNKNOWN_SCHEME,
            "should refuse a prefix naming another scheme than the key's" to SignatureRefusal.UNKNOWN_SCHEME,
            "should refuse every signature when no key is listed" to SignatureRefusal.UNKNOWN_KEY,
        )
        for (case in fixture.manifests.filter { !it.getBoolean("isValid") || !fixture.isWithinAllowList(it) }) {
            val name = case.getString("name")
            assertEquals(name, expected[name] ?: throw AssertionError("A refused fixture this test does not know: $name"), fixture.verify(case))
        }
    }

    @Test
    fun shouldVerifyASignatureMadeWithAnEd25519FixtureKeyOnThisRuntime() {
        val manifest = Fixture.release(1, "b2", "<html>v2</html>".toByteArray()).envelope.manifest
        assertNull(SignatureVerifier.verifyManifestSignature(manifest, fixture.sign(manifest, "ed25519-a"), listOf(fixture.publicKey("ed25519-a"))))
    }

    @Test
    fun shouldRefuseTheExpoBridgesRsaSchemeEvenWhenItsKeyIsListed() {
        val manifest = Fixture.release(1, "b2", "<html>v2</html>".toByteArray()).envelope.manifest
        val publicKeys = listOf(fixture.publicKey("rsa-v1_5-sha256-a"), fixture.publicKey("ed25519-a"))
        assertEquals(SignatureRefusal.UNKNOWN_SCHEME, SignatureVerifier.verifyManifestSignature(manifest, fixture.sign(manifest, "rsa-v1_5-sha256-a"), publicKeys))
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

    /** Whether the case's signature is of a scheme this core verifies at all. */
    fun isWithinAllowList(case: JSONObject): Boolean =
        case.getJSONObject("envelope").getNullableObject("signature")?.let { SigningKeys.parse(it.getString("value")) != null } ?: false

    /** The signature over the manifest string under the named key, as the CLI would make it, in either scheme of the suite. */
    fun sign(manifest: String, name: String): Signature {
        val key = key(name)
        val scheme = key.getString("scheme")
        val algorithm = if (scheme == "ed25519") "Ed25519" else "RSA"
        val privateKeyBytes = Base64.getDecoder().decode(key.getString("privateKey").substringAfter(':'))
        val signer = java.security.Signature.getInstance(if (scheme == "ed25519") "Ed25519" else "SHA256withRSA")
        signer.initSign(KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes)))
        signer.update(manifest.toByteArray(Charsets.UTF_8))
        return Signature(key.getString("fingerprint"), "$scheme:${Base64.getEncoder().encodeToString(signer.sign())}")
    }

    private fun key(name: String) = keys.first { it.getString("name") == name }
}
