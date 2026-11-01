package com.hotcodepush.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID

private val RETRY_PAUSES_MILLIS = listOf(1_000L, 3_000L)

sealed class DownloadFailure(message: String) : Exception(message) {
    /** A downloaded file or pack differs from what the manifest promised: a hash, the pack length, an entry size or the archive form. */
    class ContentMismatched(message: String) : DownloadFailure(message)

    class DownloadFailed(message: String) : DownloadFailure(message)

    /**
     * A manifest the device refuses before it writes a byte.
     * It breaks the wire rules, misses the index hash, names another bundle or app, leaves out the device platform, or a
     * manifest, pack or delta URL is off the configured hosts.
     */
    class ManifestInvalid(message: String) : DownloadFailure(message)

    class SignatureInvalid(message: String) : DownloadFailure(message)

    val reason: FailedReason
        get() = when (this) {
            is ContentMismatched -> FailedReason.CONTENT_MISMATCHED
            is DownloadFailed -> FailedReason.DOWNLOAD_FAILED
            is ManifestInvalid -> FailedReason.MANIFEST_INVALID
            is SignatureInvalid -> FailedReason.SIGNATURE_INVALID
        }
}

data class DownloadOutcome(val manifest: BundleManifest, val bytes: Long, val packKind: PackKind)

internal data class PackSource(val url: String, val sizeBytes: Long?, val packBytesLimit: Long, val kind: PackKind)

// The kind is the one that finally arrived, since a streamed pack may give way to the full pack.
internal data class PackOutcome(val bytes: Long, val kind: PackKind)

/** Downloads a release: the manifest and its signature, the pack and the missing files. */
class Downloader(
    private val configuration: Configuration,
    private val platform: String,
    private val files: FileStore,
    private val embedded: EmbeddedBundle,
    private val http: HttpClient,
    private val temporaryDirectory: File,
) {
    suspend fun downloadRelease(target: IndexRelease, baseBundleId: String?, progress: (Long, Long) -> Unit): DownloadOutcome {
        val (envelope, manifest) = fetchBundleManifest(target)
        val missing = resolveMissingFiles(manifest)
        val pack = resolvePackSource(envelope, baseBundleId, missing.size)
        verifyFreeSpace(missing.sumOf { it.sizeBytes } + (pack?.packBytesLimit ?: 0))
        var bytes = 0L
        var packKind = PackKind.FILES
        if (pack != null) {
            val outcome = downloadPack(pack, envelope, missing.associate { it.sha256 to it.sizeBytes }, progress)
            bytes += outcome.bytes
            packKind = outcome.kind
        }
        for (file in resolveMissingFiles(manifest)) bytes += downloadFile(file)
        files.writeManifest(manifest, envelope.bundleId)
        return DownloadOutcome(manifest, bytes, packKind)
    }

    internal suspend fun fetchBundleManifest(target: IndexRelease): Pair<ManifestEnvelope, BundleManifest> {
        val url = resolvePinnedUrl(target.manifestUrl)
        val body = try {
            retryTransientFailures { fetchManifestEnvelope(url) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (refusal: HttpStatusException) {
            throw DownloadFailure.DownloadFailed("HTTP ${refusal.status} for the manifest")
        } catch (exception: Exception) {
            throw DownloadFailure.DownloadFailed("The manifest could not be fetched: ${exception.message}")
        }
        val envelope = runCatching { ManifestEnvelope.fromJson(parseJsonObject(body)) }.getOrNull()
        val manifest = envelope?.let { runCatching { it.decodeManifest() }.getOrNull() } ?: throw DownloadFailure.ManifestInvalid("The manifest could not be parsed")
        verifyManifestSignature(envelope, target.manifestSha256)
        if (envelope.bundleId != target.bundleId) throw DownloadFailure.ManifestInvalid("The manifest names another bundle")
        if (!manifest.isForDevice(configuration.appId, platform)) throw DownloadFailure.ManifestInvalid("The manifest is for another app or does not list $platform")
        return envelope to manifest
    }

    private suspend fun fetchManifestEnvelope(url: String): ByteArray {
        val response = http.get(url, emptyMap())
        if (response.status != 200) throw HttpStatusException(response.status)
        return response.body
    }

    internal fun verifyManifestSignature(envelope: ManifestEnvelope, expectedSha256: String) {
        if (Hashing.sha256Hex(envelope.manifest) != expectedSha256) throw DownloadFailure.ManifestInvalid("The manifest's hash does not match the index")
        if (configuration.publicKeys.isEmpty()) return
        val refusal = SignatureVerifier.verifyManifestSignature(envelope.manifest, envelope.signature, configuration.publicKeys) ?: return
        throw DownloadFailure.SignatureInvalid(resolveSignatureRefusalMessage(refusal, keyId = envelope.signature?.keyId.orEmpty()))
    }

    private fun resolveSignatureRefusalMessage(refusal: SignatureRefusal, keyId: String): String = when (refusal) {
        SignatureRefusal.UNSIGNED -> "The manifest is unsigned and the app accepts only signed manifests"
        SignatureRefusal.UNKNOWN_SCHEME -> "The manifest's signature is not of the scheme ${SignatureVerifier.SCHEME}"
        SignatureRefusal.UNLISTED_KEY -> "The manifest is signed by a key the app does not list: $keyId"
        SignatureRefusal.UNIMPORTABLE_KEY -> "The app's configuration is wrong: the system cannot import the public key $keyId of the resource file as an RSA key"
        SignatureRefusal.WEAK_KEY -> "The public key $keyId is smaller than ${SignatureVerifier.MINIMUM_KEY_BITS} bits"
        SignatureRefusal.MISMATCH -> "The manifest's signature does not verify under the public key $keyId"
    }

    internal fun resolveMissingFiles(manifest: BundleManifest): List<BundleManifest.File> = manifest.files.filter { !files.hasFile(it.sha256) && !embedded.has(it.sha256) }

    // A device with a base takes a delta even for one missing file, so the main bundle arrives as a patch.
    // Only the deltas the envelope lists exist; for any other base the updates host streams one, never a guessed URL.
    internal fun resolvePackSource(envelope: ManifestEnvelope, baseBundleId: String?, missingFileCount: Int): PackSource? {
        if (missingFileCount == 0) return null
        if (baseBundleId == null) return resolveFullPackSource(envelope)
        val listedDelta = envelope.deltas.firstOrNull { it.baseBundleId == baseBundleId } ?: return resolveStreamedPackSource(envelope, baseBundleId)
        return PackSource(listedDelta.url, listedDelta.sizeBytes, listedDelta.sizeBytes, PackKind.DELTA)
    }

    // Only the streamed pack gives way to the full pack. A download follows no redirect, so the host's redirect lands here too.
    internal fun resolveFallbackPackSource(envelope: ManifestEnvelope, source: PackSource): PackSource? = when (source.kind) {
        PackKind.DELTA, PackKind.FILES, PackKind.FULL -> null
        PackKind.STREAMED -> resolveFullPackSource(envelope)
    }

    private fun resolveFullPackSource(envelope: ManifestEnvelope) = PackSource(envelope.pack.url, envelope.pack.sizeBytes, envelope.pack.sizeBytes, PackKind.FULL)

    // Its length is known only as it arrives, and it never exceeds the full pack, whose entries it shares.
    private fun resolveStreamedPackSource(envelope: ManifestEnvelope, baseBundleId: String) =
        PackSource("${configuration.updatesBaseUrl}/v1/apps/${configuration.appId}/bundles/${envelope.bundleId}/deltas/$baseBundleId", null, envelope.pack.sizeBytes, PackKind.STREAMED)

    internal fun verifyFreeSpace(requiredBytes: Long) {
        val availableBytes = files.availableBytes()
        if (availableBytes < requiredBytes) throw DownloadFailure.DownloadFailed("The download needs $requiredBytes bytes and $availableBytes are free")
    }

    // Three arguments and the trailing progress callback, as HttpClient.download takes.
    @Suppress("LongParameterList")
    internal suspend fun downloadPack(source: PackSource, envelope: ManifestEnvelope, wanted: Map<String, Long>, progress: (Long, Long) -> Unit): PackOutcome = try {
        PackOutcome(downloadPackEntries(source, envelope.bundleId, wanted, progress), source.kind)
    } catch (refusal: HttpStatusException) {
        val fallback = resolveFallbackPackSource(envelope, source) ?: throw DownloadFailure.DownloadFailed("HTTP ${refusal.status} for the pack")
        downloadPack(fallback, envelope, wanted, progress)
    }

    // Three arguments and the trailing progress callback, as HttpClient.download takes.
    @Suppress("LongParameterList")
    internal suspend fun downloadPackEntries(source: PackSource, bundleId: String, wanted: Map<String, Long>, progress: (Long, Long) -> Unit): Long {
        // A name stable per URL lets a later attempt resume the pack.
        val file = File(temporaryDirectory, "$bundleId-${Hashing.sha256Hex(source.url).take(16)}.pack")
        downloadPackFile(source, file, progress)
        try {
            if (source.sizeBytes != null && file.length() != source.sizeBytes) throw DownloadFailure.ContentMismatched("The pack holds ${file.length()} of its ${source.sizeBytes} bytes")
            writePackEntries(file, wanted)
            return file.length()
        } finally {
            file.delete()
        }
    }

    private suspend fun downloadPackFile(source: PackSource, file: File, progress: (Long, Long) -> Unit) {
        val pinnedUrl = resolvePinnedUrl(source.url)
        try {
            retryTransientFailures { http.download(pinnedUrl, file, source.packBytesLimit, progress) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: DownloadFailure) {
            throw failure
        } catch (refusal: HttpStatusException) {
            throw refusal
        } catch (exception: Exception) {
            throw DownloadFailure.DownloadFailed("The pack could not be downloaded: ${exception.message}")
        }
    }

    private fun writePackEntries(file: File, wanted: Map<String, Long>) {
        try {
            file.inputStream().buffered().use { input ->
                PackReader.forEachEntry(input, file.length()) { entry, body ->
                    when (entry) {
                        is PackEntry.File -> {
                            val sizeBytes = wanted[entry.sha256] ?: return@forEachEntry
                            Gzip.inflate(body, sizeBytes).use { content -> files.writeFile(content, entry.sha256) }
                        }
                        is PackEntry.Patch -> {
                            val sizeBytes = wanted[entry.toSha256] ?: return@forEachEntry
                            applyPatch(entry, body, sizeBytes)
                        }
                    }
                }
            }
        } catch (failure: DownloadFailure) {
            throw failure
        } catch (exception: Exception) {
            throw DownloadFailure.ContentMismatched("The pack did not verify: ${exception.message}")
        }
    }

    // A failed patch leaves its file to the single-file fetch after the pack, so an update never fails because of a patch.
    internal fun applyPatch(entry: PackEntry.Patch, body: InputStream, maximumBytes: Long) {
        try {
            writePatchedFile(entry, body, maximumBytes)
        } catch (failure: Throwable) {
            val isPatchFailure = failure is Exception || failure is LinkageError || failure is OutOfMemoryError
            if (!isPatchFailure) throw failure
        }
    }

    private fun writePatchedFile(entry: PackEntry.Patch, body: InputStream, maximumBytes: Long) {
        val directory = File(temporaryDirectory, "${UUID.randomUUID()}.patching")
        directory.mkdirs()
        try {
            val base = preparePatchBase(entry.fromSha256, directory)
            val patch = File(directory, "patch").apply { outputStream().use { body.copyTo(it) } }
            val patched = File(directory, "patched")
            Bspatch.apply(patch = patch, old = base, new = patched, maximumBytes = maximumBytes)
            patched.inputStream().use { content -> files.writeFile(content, entry.toSha256) }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun preparePatchBase(sha256: String, directory: File): File {
        if (files.hasFile(sha256)) return files.file(sha256)
        if (!embedded.has(sha256)) throw FileNotFoundException("The patch's base $sha256 is not held")
        return File(directory, "base").also { embedded.copyFile(sha256, it) }
    }

    internal fun resolvePinnedUrl(url: String): String {
        if (!configuration.isUrlOnConfiguredHost(url)) throw DownloadFailure.ManifestInvalid("$url is not on a configured host")
        return url
    }

    // The HTTP client already decodes the gzip the bucket serves, so the body is the file content.
    internal suspend fun downloadFile(file: BundleManifest.File): Long {
        val url = "${configuration.filesBaseUrl}/apps/${configuration.appId}/files/${file.sha256}"
        val temporary = File(temporaryDirectory, "${UUID.randomUUID()}.file")
        try {
            try {
                retryTransientFailures {
                    // Decoded bytes match no `Range` offset, so each attempt starts again.
                    temporary.delete()
                    http.download(url, temporary, file.sizeBytes) { _, _ -> }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: DownloadFailure) {
                throw failure
            } catch (exception: Exception) {
                throw DownloadFailure.DownloadFailed("The file ${file.path} could not be downloaded: ${exception.message}")
            }
            try {
                temporary.inputStream().use { content -> files.writeFile(content, file.sha256) }
            } catch (exception: HashMismatchException) {
                throw DownloadFailure.ContentMismatched("The file ${file.path} did not match its hash")
            }
            return temporary.length()
        } finally {
            temporary.delete()
        }
    }

    // A refusal such as a 404 or 410 throws at once, since the object will not appear.
    private suspend fun <T> retryTransientFailures(fetch: suspend () -> T): T {
        for (pauseMillis in RETRY_PAUSES_MILLIS) {
            try {
                return fetch()
            } catch (failure: Exception) {
                if (!isTransientFailure(failure)) throw failure
            }
            delay(pauseMillis)
        }
        return fetch()
    }

    private fun isTransientFailure(failure: Exception): Boolean = failure is IOException || (failure is HttpStatusException && failure.status in 500..599)
}
