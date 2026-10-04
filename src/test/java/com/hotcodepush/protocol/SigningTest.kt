package com.hotcodepush.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** The protocol's signature fixtures, each with the keys an Android resource file carries, and the refusals behind the ones that do not verify. */
class SigningTest {
    private val fixture = SignatureFixtures()
    private val manifest = Fixture.release(1, "b2", "<html>v2</html>".toByteArray()).envelope.manifest
    private val resourceFiles: List<JSONObject> = JSONObject(File("node_modules/@hotcodepush/protocol/fixtures/resource-files.json").readText()).getJSONArray("cases").map { it }

    @Test
    fun shouldMatchEverySignatureFixture() {
        assertTrue(fixture.manifests.size > 5)
        var verified = 0
        for (case in fixture.manifests) {
            val isValid = fixture.verify(case) == null
            assertEquals(case.getString("name"), case.getBoolean("isValid"), isValid)
            if (isValid) verified++
        }
        assertTrue(verified >= 3)
    }

    @Test
    fun shouldNameWhyAFixtureDoesNotVerify() {
        val expected = mapOf(
            "should refuse a manifest changed after signing" to SignatureRefusal.INVALID,
            "should refuse a signature changed after signing" to SignatureRefusal.INVALID,
            "should refuse an unsigned manifest" to SignatureRefusal.UNSIGNED,
            "should refuse a signature by a key not in the list" to SignatureRefusal.UNKNOWN_KEY,
            "should refuse a signature checked against the key its keyId names when another listed key made it" to SignatureRefusal.INVALID,
            "should refuse a scheme outside the allow-list, ed25519" to SignatureRefusal.UNKNOWN_SCHEME,
            "should refuse a signature by a key under 2048 bits" to SignatureRefusal.UNUSABLE_KEY,
            "should refuse every signature when no key is listed" to SignatureRefusal.UNKNOWN_KEY,
        )
        for (case in fixture.manifests.filter { !it.getBoolean("isValid") }) {
            val name = case.getString("name")
            assertEquals(name, expected[name] ?: throw AssertionError("A refused fixture this test does not know: $name"), fixture.verify(case))
        }
    }

    /** The key id is the fingerprint over the SPKI bytes, the very bytes an Android resource file carries. */
    @Test
    fun shouldCarryEveryAndroidKeyAsItsSpkiBytesBesideTheirFingerprint() {
        val keys = fixture.manifests.flatMap(fixture::devicePublicKeys).distinct()
        assertTrue(keys.size >= 4)
        for (key in keys) assertEquals(key.keyId, "sha256:" + Hashing.sha256Hex(Base64.getDecoder().decode(key.der)))
        for (key in fixture.keys) assertEquals(key.getString("fingerprint"), fixture.devicePublicKey(key.getString("name")).keyId)
    }

    @Test
    fun shouldVerifyASignatureMadeWithAFixtureKeyOnThisRuntime() {
        for (name in listOf("rsa-4096-a", "rsa-2048")) {
            assertNull(name, SignatureVerifier.verifyManifestSignature(manifest, fixture.sign(manifest, name), listOf(fixture.devicePublicKey(name))))
        }
    }

    @Test
    fun shouldVerifyUnderTheKeysOfTheAndroidResourceFileFixture() {
        val androidBuild = resourceFiles.single { it.getString("name").contains("Android build") }
        val publicKeys = Configuration.fromJson(androidBuild.getJSONObject("resourceFile")).publicKeys
        assertEquals(listOf("rsa-4096-a", "rsa-4096-b").map { fixture.devicePublicKey(it) }, publicKeys)
        assertNull(SignatureVerifier.verifyManifestSignature(manifest, fixture.sign(manifest, "rsa-4096-b"), publicKeys))
    }

    @Test
    fun shouldRefuseAResourceFileWhoseKeysAreNotDerBesideAKeyId() {
        val resourceFile = resourceFiles.first().getJSONObject("resourceFile")
        for (publicKeys in listOf("""["rsa-v1_5-sha256:AAAA"]""", """[{"der":"not base64","keyId":"k"}]""", """[{"der":"AAAA"}]""", """[{"der":"AAAA","keyId":""}]""")) {
            assertThrows(publicKeys, org.json.JSONException::class.java) { Configuration.fromJson(JSONObject(resourceFile.toString()).put("publicKeys", org.json.JSONArray(publicKeys))) }
        }
    }

    @Test
    fun shouldRefuseASignatureUnderAnotherPrefixEvenWhenItsBytesWouldVerify() {
        val signed = fixture.sign(manifest, "rsa-4096-a")
        for (prefix in listOf("ed25519", "rsa-pss-sha256", "RSA-V1_5-SHA256")) {
            val renamed = Signature(signed.keyId, "$prefix:${signed.value.substringAfter(':')}")
            assertEquals(prefix, SignatureRefusal.UNKNOWN_SCHEME, SignatureVerifier.verifyManifestSignature(manifest, renamed, listOf(fixture.devicePublicKey("rsa-4096-a"))))
        }
    }

    @Test
    fun shouldRefuseASignatureWhoseBase64IsNotCanonical() {
        val signed = fixture.sign(manifest, "rsa-4096-a")
        val unpadded = Signature(signed.keyId, signed.value.trimEnd('='))
        assertEquals(SignatureRefusal.UNKNOWN_SCHEME, SignatureVerifier.verifyManifestSignature(manifest, unpadded, listOf(fixture.devicePublicKey("rsa-4096-a"))))
    }

    @Test
    fun shouldRefuseASignatureUnderAListedKeyThePlatformDoesNotImportAsRsa() {
        val signed = fixture.sign(manifest, "rsa-4096-a")
        val notAKey = DevicePublicKey(Base64.getEncoder().encodeToString(ByteArray(32)), signed.keyId)
        assertEquals(SignatureRefusal.UNUSABLE_KEY, SignatureVerifier.verifyManifestSignature(manifest, signed, listOf(notAKey)))
    }
}

/** The test keys of `fixtures/signatures.json`, the keys of each case as an Android resource file carries them, and a signer over them through the JVM's providers. */
class SignatureFixtures {
    private val document = JSONObject(File("node_modules/@hotcodepush/protocol/fixtures/signatures.json").readText())
    val keys: List<JSONObject> = document.getJSONArray("keys").map { it }
    val manifests: List<JSONObject> = document.getJSONArray("manifests").map { it }

    /** A fixture key as an Android resource file carries it: the SPKI DER beside the fingerprint. */
    fun devicePublicKey(name: String): DevicePublicKey = key(name).let { DevicePublicKey(it.getString("publicKey").substringAfter(':'), it.getString("fingerprint")) }

    fun devicePublicKeys(case: JSONObject): List<DevicePublicKey> = case.getJSONObject("devicePublicKeys").getJSONArray("android").map(DevicePublicKey::fromJson)

    /** A `manifests` case through the verifier: the signed half of its envelope against its Android keys. */
    fun verify(case: JSONObject): SignatureRefusal? {
        val envelope = case.getJSONObject("envelope")
        val signature = envelope.getNullableObject("signature")?.let(Signature::fromJson)
        return SignatureVerifier.verifyManifestSignature(envelope.getString("manifest"), signature, devicePublicKeys(case))
    }

    /** The signature over the manifest string under the named key, as the CLI makes it. */
    fun sign(manifest: String, name: String): Signature {
        val key = key(name)
        val signer = java.security.Signature.getInstance("SHA256withRSA")
        signer.initSign(KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.getString("privateKey")))))
        signer.update(manifest.toByteArray(Charsets.UTF_8))
        return Signature(key.getString("fingerprint"), "${SignatureVerifier.SCHEME}:${Base64.getEncoder().encodeToString(signer.sign())}")
    }

    private fun key(name: String) = keys.first { it.getString("name") == name }
}
