package com.hotcodepush.core

import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.UUID

sealed class DownloadFailure(message: String) : Exception(message) {
    /** A downloaded file or pack off what the manifest promised: a hash, the pack's length, an entry's size, the archive's form. */
    class ContentMismatched(message: String) : DownloadFailure(message)

    class DownloadFailed(message: String) : DownloadFailure(message)

    /**
     * A manifest the device does not take, refused before a byte is written: one that breaks the wire rules, whose bytes are
     * off the index's hash, whose envelope names another bundle, that names another app or leaves out the device's platform,
     * or a manifest, pack or delta URL off the configured hosts.
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

/** Where a pack comes from: its URL, its size where the envelope states one, the most bytes it may hold and how the bytes arrive. */
internal data class PackSource(val url: String, val sizeBytes: Long?, val maximumBytes: Long, val kind: PackKind)

/** The bytes a pack cost and the kind that finally arrived, since a delta pack may give way to the streamed delta and that to the full pack. */
internal data class PackOutcome(val bytes: Long, val kind: PackKind)

/** Manifest, signature, missing files, pack, verification, files to disk — each step one function. */
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
        verifyFreeSpace(missing.sumOf { it.sizeBytes } + (pack?.maximumBytes ?: 0))
        var bytes = 0L
        var packKind = PackKind.FILES
        if (pack != null) {
            val outcome = downloadPack(pack, envelope, baseBundleId, missing.associate { it.sha256 to it.sizeBytes }, progress)
            bytes += outcome.bytes
            packKind = outcome.kind
        }
        for (file in resolveMissingFiles(manifest)) bytes += downloadFile(file)
        files.writeManifest(manifest, envelope.bundleId)
        return DownloadOutcome(manifest, bytes, packKind)
    }

    /** The envelope with its manifest decoded, once the manifest's bytes match the index, the envelope names the release's bundle and the manifest the device's app and platform. */
    internal suspend fun fetchBundleManifest(target: IndexRelease): Pair<ManifestEnvelope, BundleManifest> {
        val url = resolvePinnedUrl(target.manifestUrl)
        val response = try {
            http.get(url, emptyMap())
        } catch (exception: Exception) {
            throw DownloadFailure.DownloadFailed("The manifest could not be fetched: ${exception.message}")
        }
        if (response.status != 200) throw DownloadFailure.DownloadFailed("HTTP ${response.status} for the manifest")
        val envelope = runCatching { ManifestEnvelope.fromJson(org.json.JSONObject(String(response.body, Charsets.UTF_8))) }.getOrNull()
        val manifest = envelope?.let { runCatching { it.decodeManifest() }.getOrNull() } ?: throw DownloadFailure.ManifestInvalid("The manifest could not be parsed")
        verifyManifestSignature(envelope, target.manifestSha256)
        if (envelope.bundleId != target.bundleId) throw DownloadFailure.ManifestInvalid("The manifest names another bundle")
        if (!manifest.isForDevice(configuration.appId, platform)) throw DownloadFailure.ManifestInvalid("The manifest is for another app or does not list $platform")
        return envelope to manifest
    }

    /** The manifest's bytes against the index, and, once the app holds signing keys, its signature under the key it names. */
    internal fun verifyManifestSignature(envelope: ManifestEnvelope, expectedSha256: String) {
        if (Hashing.sha256Hex(envelope.manifest) != expectedSha256) throw DownloadFailure.ManifestInvalid("The manifest's hash does not match the index")
        if (configuration.publicKeys.isEmpty()) return
        val refusal = SignatureVerifier.verifyManifestSignature(envelope.manifest, envelope.signature, configuration.publicKeys) ?: return
        throw DownloadFailure.SignatureInvalid(resolveSignatureRefusalMessage(refusal, envelope.signature))
    }

    private fun resolveSignatureRefusalMessage(refusal: SignatureRefusal, signature: Signature?): String = when (refusal) {
        SignatureRefusal.UNSIGNED -> "The manifest is unsigned and the app accepts only signed manifests"
        SignatureRefusal.UNKNOWN_SCHEME -> "The signature's scheme is not ${SignatureVerifier.SCHEME}, the one this SDK accepts"
        SignatureRefusal.UNKNOWN_KEY -> "The signature names the key ${signature?.keyId}, which the app does not hold"
        SignatureRefusal.UNUSABLE_KEY -> "The key ${signature?.keyId} the app holds is not an RSA key of ${SignatureVerifier.KEY_BITS_MINIMUM} bits or more"
        SignatureRefusal.INVALID -> "The manifest's signature does not verify under the key it names"
    }

    internal fun resolveMissingFiles(manifest: BundleManifest): List<BundleManifest.File> = manifest.files.filter { !files.hasFile(it.sha256) && !embedded.has(it.sha256) }

    /**
     * The pack a download requests first, `null` when no file is missing. A device with a base takes a delta pack for one
     * missing file as for ten, so the one file a patch exists for, the main bundle, arrives as a patch: the delta the envelope
     * lists for its base, else the delta pack at its URL on the files host, never larger than the full pack whose entries it
     * shares. A device without a base takes the full pack.
     */
    internal fun resolvePackSource(envelope: ManifestEnvelope, baseBundleId: String?, missingFileCount: Int): PackSource? {
        if (missingFileCount == 0) return null
        if (baseBundleId == null) return resolveFullPackSource(envelope)
        envelope.deltas.firstOrNull { it.baseBundleId == baseBundleId }?.let { return PackSource(it.url, it.sizeBytes, it.sizeBytes, PackKind.DELTA) }
        return PackSource("${configuration.filesBaseUrl}/${resolveDeltaPackPath(envelope.bundleId, baseBundleId)}", null, envelope.pack.sizeBytes, PackKind.DELTA)
    }

    /**
     * The pack a download requests after `source` answered `status` instead of a 200 or a 206, `null` when the download fails
     * and waits for the next cycle. A delta pack answering 404 is not built yet, and the updates host assembles it; whatever
     * else the updates host answers, its redirect to the full pack above twenty objects included, reads as the full pack,
     * since a download follows no redirect.
     */
    internal fun resolveFallbackPackSource(envelope: ManifestEnvelope, baseBundleId: String?, source: PackSource, status: Int): PackSource? = when (source.kind) {
        PackKind.DELTA -> if (status == NOT_FOUND_STATUS && baseBundleId != null) resolveStreamedPackSource(envelope, baseBundleId) else null
        PackKind.STREAMED -> resolveFullPackSource(envelope)
        PackKind.FULL, PackKind.FILES -> null
    }

    /** The delta pack's path below a host, the bucket's key and the updates route alike. */
    private fun resolveDeltaPackPath(bundleId: String, baseBundleId: String) = "apps/${configuration.appId}/bundles/$bundleId/deltas/$baseBundleId"

    private fun resolveFullPackSource(envelope: ManifestEnvelope) = PackSource(envelope.pack.url, envelope.pack.sizeBytes, envelope.pack.sizeBytes, PackKind.FULL)

    /** The delta pack the updates host assembles on request, of a length known only as it arrives. */
    private fun resolveStreamedPackSource(envelope: ManifestEnvelope, baseBundleId: String) =
        PackSource("${configuration.updatesBaseUrl}/v1/${resolveDeltaPackPath(envelope.bundleId, baseBundleId)}", null, envelope.pack.sizeBytes, PackKind.STREAMED)

    /** The download needs its bytes on disk at its peak: every missing file and the pack they arrive in. */
    internal fun verifyFreeSpace(requiredBytes: Long) {
        val availableBytes = files.availableBytes()
        if (availableBytes < requiredBytes) throw DownloadFailure.DownloadFailed("The download needs $requiredBytes bytes and $availableBytes are free")
    }

    /**
     * The pack's wanted entries in the store and how they arrived. A delta pack not built yet gives way to the streamed delta,
     * and a streamed delta the updates host does not serve — its redirect to the full pack above twenty objects or for a base
     * the bucket no longer knows, a limit, an error — to the full pack: slower, never failed. Any other refusal fails the download.
     */
    internal suspend fun downloadPack(source: PackSource, envelope: ManifestEnvelope, baseBundleId: String?, wanted: Map<String, Long>, progress: (Long, Long) -> Unit): PackOutcome = try {
        PackOutcome(downloadPackEntries(source, envelope.bundleId, wanted, progress), source.kind)
    } catch (refusal: HttpStatusException) {
        val fallback = resolveFallbackPackSource(envelope, baseBundleId, source, refusal.status) ?: throw DownloadFailure.DownloadFailed("HTTP ${refusal.status} for the pack")
        downloadPack(fallback, envelope, baseBundleId, wanted, progress)
    }

    /**
     * Streams the pack to disk, resuming what an earlier attempt left and never past its bound, then inflates each wanted
     * file entry up to its file's size, an entry always the gzip bytes the bucket serves, and applies each patch entry to a
     * wanted file, every entry streamed from the pack to the store. A pack whose size the envelope states holds exactly that
     * many bytes; a streamed one has no size to hold it to.
     */
    internal suspend fun downloadPackEntries(source: PackSource, bundleId: String, wanted: Map<String, Long>, progress: (Long, Long) -> Unit): Long {
        val pinnedUrl = resolvePinnedUrl(source.url)
        val file = File(temporaryDirectory, "$bundleId-${Hashing.sha256Hex(source.url).take(16)}.pack")
        try {
            http.download(pinnedUrl, file, source.maximumBytes, progress)
        } catch (failure: DownloadFailure) {
            throw failure
        } catch (refusal: HttpStatusException) {
            throw refusal
        } catch (exception: Exception) {
            throw DownloadFailure.DownloadFailed("The pack could not be downloaded: ${exception.message}")
        }
        try {
            if (source.sizeBytes != null && file.length() != source.sizeBytes) throw DownloadFailure.ContentMismatched("The pack holds ${file.length()} of its ${source.sizeBytes} bytes")
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
            return file.length()
        } catch (failure: DownloadFailure) {
            throw failure
        } catch (exception: Exception) {
            throw DownloadFailure.ContentMismatched("The pack did not verify: ${exception.message}")
        } finally {
            file.delete()
        }
    }

    /**
     * Writes the file `toSha256` from the patch and the held file `fromSha256`, never past the manifest's size of it.
     * Whatever stops it — no base, a malformed patch, another hash, no native library for the ABI, no memory — leaves the
     * file missing, fetched whole after the pack: an update never fails because of a patch.
     */
    internal fun applyPatch(entry: PackEntry.Patch, body: InputStream, maximumBytes: Long) {
        try {
            writePatchedFile(entry, body, maximumBytes)
        } catch (failure: Throwable) {
            val isPatchFailure = failure is Exception || failure is LinkageError || failure is OutOfMemoryError
            if (!isPatchFailure) throw failure
        }
    }

    /** The patched bytes into the store, which refuses them unless they hash to `toSha256`. */
    private fun writePatchedFile(entry: PackEntry.Patch, body: InputStream, maximumBytes: Long) {
        val directory = File(temporaryDirectory, "${UUID.randomUUID()}.patching")
        directory.mkdirs()
        try {
            val base = preparePatchBase(entry.fromSha256, directory)
            val patch = File(directory, "patch").apply { outputStream().use { body.copyTo(it) } }
            val patched = File(directory, "patched")
            Bspatch.apply(patch, base, patched, maximumBytes)
            patched.inputStream().use { content -> files.writeFile(content, entry.toSha256) }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** The held file a patch starts from: the store's in place, the embedded bundle's copied beside the patch. */
    private fun preparePatchBase(sha256: String, directory: File): File {
        if (files.hasFile(sha256)) return files.file(sha256)
        if (!embedded.has(sha256)) throw FileNotFoundException("The patch's base $sha256 is not held")
        return File(directory, "base").also { embedded.copyFile(sha256, it) }
    }

    /** The URL of a manifest, pack or delta only when it is on a configured host: the SDK fetches from our hosts and nowhere else. */
    internal fun resolvePinnedUrl(url: String): String {
        if (!configuration.isUrlOnConfiguredHost(url)) throw DownloadFailure.ManifestInvalid("$url is not on a configured host")
        return url
    }

    /**
     * One file through the same bounded stream as the pack, never past its size in the manifest; the HTTP client already
     * decoded the gzip the bucket serves, so the body is the content, whatever bytes it starts with.
     */
    internal suspend fun downloadFile(file: BundleManifest.File): Long {
        val url = "${configuration.filesBaseUrl}/apps/${configuration.appId}/files/${file.sha256}"
        val temporary = File(temporaryDirectory, "${UUID.randomUUID()}.file")
        try {
            try {
                http.download(url, temporary, file.sizeBytes) { _, _ -> }
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

    companion object {
        /** A delta pack's answer while it is not built yet. */
        private const val NOT_FOUND_STATUS = 404
    }
}
