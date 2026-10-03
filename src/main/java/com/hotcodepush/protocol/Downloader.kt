package com.hotcodepush.protocol

import java.io.File

sealed class DownloadFailure(message: String) : Exception(message) {
    class InvalidSignature(message: String) : DownloadFailure(message)
    class VerificationFailed(message: String) : DownloadFailure(message)
    class DownloadFailed(message: String) : DownloadFailure(message)

    val reason: FailedReason
        get() = when (this) {
            is InvalidSignature -> FailedReason.INVALID_SIGNATURE
            is VerificationFailed -> FailedReason.VERIFICATION_FAILED
            is DownloadFailed -> FailedReason.DOWNLOAD_FAILED
        }
}

data class DownloadOutcome(val manifest: BundleManifest, val bytes: Long, val packKind: PackKind)

/** Manifest, signature, missing files, pack, verification, files to disk — each step one function. */
class Downloader(
    private val configuration: Configuration,
    private val files: FileStore,
    private val embedded: EmbeddedBundle,
    private val http: HttpClient,
    private val temporaryDirectory: File,
) {
    suspend fun downloadRelease(target: IndexRelease, currentBundleId: String?, progress: (Long, Long) -> Unit): DownloadOutcome {
        val manifest = fetchBundleManifest(target)
        val missing = resolveMissingFiles(manifest)
        val pack = if (missing.isEmpty()) null else resolvePack(manifest, currentBundleId, missing)
        verifyFreeSpace(missing.sumOf { it.sizeBytes } + (pack?.first?.sizeBytes ?: 0))
        var bytes = 0L
        var packKind = PackKind.FILES
        if (pack != null) {
            val (source, kind) = pack
            bytes += downloadPack(source, manifest.bundleId, missing.associate { it.sha256 to it.sizeBytes }, progress)
            packKind = kind
        }
        for (file in resolveMissingFiles(manifest)) bytes += downloadFile(file)
        files.writeManifest(manifest)
        return DownloadOutcome(manifest, bytes, packKind)
    }

    internal suspend fun fetchBundleManifest(target: IndexRelease): BundleManifest {
        val url = resolvePinnedUrl(target.manifestUrl)
        val response = try {
            http.get(url, emptyMap())
        } catch (exception: Exception) {
            throw DownloadFailure.DownloadFailed("The manifest could not be fetched: ${exception.message}")
        }
        if (response.status != 200) throw DownloadFailure.DownloadFailed("HTTP ${response.status} for the manifest")
        val envelope = runCatching { ManifestEnvelope.fromJson(org.json.JSONObject(String(response.body, Charsets.UTF_8))) }.getOrNull()
        val manifest = envelope?.let { runCatching { it.decodeManifest() }.getOrNull() } ?: throw DownloadFailure.VerificationFailed("The manifest could not be parsed")
        verifyManifestSignature(envelope, target.manifestSha256)
        if (manifest.bundleId != target.bundleId) throw DownloadFailure.VerificationFailed("The manifest names another bundle")
        return manifest
    }

    internal fun verifyManifestSignature(envelope: ManifestEnvelope, expectedSha256: String) {
        if (Hashing.sha256Hex(envelope.manifest) != expectedSha256) throw DownloadFailure.VerificationFailed("The manifest's hash does not match the index")
        if (configuration.publicKeys.isNotEmpty()) {
            // TODO(milestone 3, code signing): verify the ed25519 signature over the manifest bytes against `publicKeys`.
            throw DownloadFailure.InvalidSignature("Signature verification is not available in this SDK version")
        }
    }

    internal fun resolveMissingFiles(manifest: BundleManifest): List<BundleManifest.File> = manifest.files.filter { !files.hasFile(it.sha256) && !embedded.has(it.sha256) }

    /** The delta pack against the running bundle where one exists, the full pack otherwise; nothing when the pack would cost more than the files. */
    internal fun resolvePack(manifest: BundleManifest, currentBundleId: String?, missing: List<BundleManifest.File>): Pair<BundleManifest.Pack, PackKind>? {
        manifest.deltas.firstOrNull { it.baseBundleId == currentBundleId }?.let { return BundleManifest.Pack(it.url, it.sizeBytes) to PackKind.DELTA }
        val pack = manifest.pack
        if (pack != null && missing.size > 1) return pack to PackKind.FULL
        return null
    }

    /** The download needs its bytes on disk at its peak: every missing file and the pack they arrive in. */
    internal fun verifyFreeSpace(requiredBytes: Long) {
        val availableBytes = files.availableBytes()
        if (availableBytes < requiredBytes) throw DownloadFailure.DownloadFailed("The download needs $requiredBytes bytes and $availableBytes are free")
    }

    /**
     * Streams the pack to disk, resuming what an earlier attempt left and never past its size in the manifest, then inflates
     * each wanted entry up to its file's size: an entry is always the gzip bytes the bucket serves.
     */
    internal suspend fun downloadPack(source: BundleManifest.Pack, bundleId: String, wanted: Map<String, Long>, progress: (Long, Long) -> Unit): Long {
        val pinnedUrl = resolvePinnedUrl(source.url)
        val file = File(temporaryDirectory, "$bundleId-${Hashing.sha256Hex(source.url).take(16)}.pack")
        try {
            http.download(pinnedUrl, file, source.sizeBytes, progress)
        } catch (failure: DownloadFailure) {
            throw failure
        } catch (exception: Exception) {
            throw DownloadFailure.DownloadFailed("The pack could not be downloaded: ${exception.message}")
        }
        try {
            if (file.length() != source.sizeBytes) throw DownloadFailure.VerificationFailed("The pack holds ${file.length()} of its ${source.sizeBytes} bytes")
            file.inputStream().buffered().use { input ->
                PackReader.forEachEntry(input, file.length()) { entry ->
                    val sizeBytes = wanted[entry.sha256] ?: return@forEachEntry
                    files.writeFile(Gzip.decompress(entry.body, sizeBytes), entry.sha256)
                }
            }
            return file.length()
        } catch (failure: DownloadFailure) {
            throw failure
        } catch (exception: Exception) {
            throw DownloadFailure.VerificationFailed("The pack did not verify: ${exception.message}")
        } finally {
            file.delete()
        }
    }

    /** The URL of a manifest, pack or delta only when it is on a configured host: the SDK fetches from our hosts and nowhere else. */
    internal fun resolvePinnedUrl(url: String): String {
        val isOnConfiguredHost = listOf(configuration.filesBaseUrl, configuration.updatesBaseUrl).any { url.startsWith("$it/") }
        if (!isOnConfiguredHost) throw DownloadFailure.VerificationFailed("$url is not on a configured host")
        return url
    }

    /**
     * One file through the same bounded stream as the pack, never past its size in the manifest; the HTTP client already
     * decoded the gzip the bucket serves, so the body is the content, whatever bytes it starts with.
     */
    internal suspend fun downloadFile(file: BundleManifest.File): Long {
        val url = "${configuration.filesBaseUrl}/apps/${configuration.appId}/files/${file.sha256}"
        val temporary = File(temporaryDirectory, "${file.sha256}.file")
        temporary.delete()
        try {
            try {
                http.download(url, temporary, file.sizeBytes) { _, _ -> }
            } catch (failure: DownloadFailure) {
                throw failure
            } catch (exception: Exception) {
                throw DownloadFailure.DownloadFailed("The file ${file.path} could not be downloaded: ${exception.message}")
            }
            val content = temporary.readBytes()
            try {
                files.writeFile(content, file.sha256)
            } catch (exception: HashMismatchException) {
                throw DownloadFailure.VerificationFailed("The file ${file.path} did not match its hash")
            }
            return content.size.toLong()
        } finally {
            temporary.delete()
        }
    }
}
