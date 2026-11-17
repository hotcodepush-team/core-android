package com.hotcodepush.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID

private val RETRY_PAUSES_MILLIS = listOf(1_000L, 3_000L)

sealed class DownloadFailure(message: String) : Exception(message) {
    /** The full pack differs from what the manifest promised: a hash, the pack length, an entry size, the archive form or a listed file it lacks. */
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

/** What a download brought: `packKind` is null when no file was missing, so no pack was requested. */
data class DownloadOutcome(val manifest: BundleManifest, val downloadSizeBytes: Long, val packKind: PackKind?)

internal data class PackSource(val url: String, val sizeBytes: Long, val kind: PackKind)

// The kind is the one that finally arrived, since a delta may give way to the full pack.
internal data class PackOutcome(val bytes: Long, val kind: PackKind)

// A pack that failed, with the failure it ends the download with when no pack follows it.
internal class PackFailure(val downloadFailure: DownloadFailure) : Exception(downloadFailure.message)

/**
 * Downloads a release: the manifest and its signature, then the pack of the files the device lacks.
 * `scope` runs the request to the updates host, which no download waits for.
 */
class Downloader(
    private val configuration: Configuration,
    private val platform: String,
    private val files: FileStore,
    private val embedded: EmbeddedBundle,
    private val http: HttpClient,
    private val temporaryDirectory: File,
    private val scope: CoroutineScope,
) {
    private enum class UnpackFailureKind { CONTENT, DEVICE, MEMORY, STORAGE }

    suspend fun downloadRelease(target: IndexRelease, baseBundleId: String?, progress: (Long, Long) -> Unit): DownloadOutcome {
        val (envelope, manifest) = fetchBundleManifest(target)
        val missingFiles = resolveMissingFiles(manifest)
        val sources = resolvePackSources(envelope, baseBundleId, missingFiles.size)
        verifyFreeSpace(missingFiles.sumOf { it.sizeBytes } + (sources.maxOfOrNull { it.sizeBytes } ?: 0))
        resolveDeltaRouteUrl(envelope, baseBundleId, missingFiles.size)?.let { url -> scope.launch { fetchDeltaRoute(url) } }
        val outcome = if (sources.isEmpty()) null else downloadPack(sources, envelope.bundleId, missingFiles, progress)
        files.writeManifest(manifest, envelope.bundleId)
        return DownloadOutcome(manifest = manifest, downloadSizeBytes = outcome?.bytes ?: 0, packKind = outcome?.kind)
    }

    internal suspend fun fetchBundleManifest(target: IndexRelease): Pair<ManifestEnvelope, BundleManifest> {
        val url = resolvePinnedUrl(target.manifestUrl)
        val body = try {
            fetchWithRetries { fetchManifestEnvelope(url) }
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
        if (!manifest.isForDevice(appId = configuration.appId, platform = platform)) throw DownloadFailure.ManifestInvalid("The manifest is for another app or does not list $platform")
        verifyPackUrlsPinned(envelope)
        return envelope to manifest
    }

    // Every pack a download may turn to is checked before a byte is written.
    private fun verifyPackUrlsPinned(envelope: ManifestEnvelope) {
        for (url in listOf(envelope.pack.url) + envelope.deltas.map { it.url }) resolvePinnedUrl(url)
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

    // A device with a base tries the delta the envelope lists for that base first, even for one missing file, so the main bundle can arrive as a patch.
    internal fun resolvePackSources(envelope: ManifestEnvelope, baseBundleId: String?, missingFileCount: Int): List<PackSource> {
        if (missingFileCount == 0) return emptyList()
        val fullPackSource = resolveFullPackSource(envelope)
        val listedDelta = resolveListedDelta(envelope, baseBundleId) ?: return listOf(fullPackSource)
        return listOf(PackSource(url = listedDelta.url, sizeBytes = listedDelta.sizeBytes, kind = PackKind.DELTA), fullPackSource)
    }

    internal fun resolveDeltaRouteUrl(envelope: ManifestEnvelope, baseBundleId: String?, missingFileCount: Int): String? {
        if (missingFileCount == 0 || baseBundleId == null || resolveListedDelta(envelope, baseBundleId) != null) return null
        return "${configuration.updatesBaseUrl}/v1/apps/${configuration.appId}/bundles/${envelope.bundleId}/deltas/$baseBundleId"
    }

    private fun resolveListedDelta(envelope: ManifestEnvelope, baseBundleId: String?) = envelope.deltas.firstOrNull { it.baseBundleId == baseBundleId }

    private fun resolveFullPackSource(envelope: ManifestEnvelope) = PackSource(url = envelope.pack.url, sizeBytes = envelope.pack.sizeBytes, kind = PackKind.FULL)

    internal fun verifyFreeSpace(requiredBytes: Long) {
        val availableBytes = files.availableBytes()
        if (availableBytes < requiredBytes) throw DownloadFailure.DownloadFailed("The download needs $requiredBytes bytes and $availableBytes are free")
    }

    // No answer and no failure of this request changes the download.
    private suspend fun fetchDeltaRoute(url: String) {
        try {
            http.send(url)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Exception) {
        }
    }

    // A failure of the device itself, a write of its storage or memory that runs out while a file inflates, ends the download at once.
    // Three arguments and the trailing progress callback, as HttpClient.download takes.
    @Suppress("LongParameterList")
    internal suspend fun downloadPack(sources: List<PackSource>, bundleId: String, missingFiles: List<BundleManifest.File>, progress: (Long, Long) -> Unit): PackOutcome {
        // Each pack takes away what it stored, so a file the device loses meanwhile is never the pack's fault.
        val missingFilesByHash = missingFiles.associateByTo(mutableMapOf()) { it.sha256 }
        var lastFailure: PackFailure? = null
        for (source in sources) {
            try {
                return PackOutcome(downloadPackEntries(source, bundleId, missingFilesByHash, progress), source.kind)
            } catch (packFailure: PackFailure) {
                lastFailure = packFailure
                if (source.kind == PackKind.DELTA) resolvePackFile(bundleId = bundleId, url = source.url).delete()
            }
        }
        throw checkNotNull(lastFailure) { "A download needs a pack source" }.downloadFailure
    }

    // Three arguments and the trailing progress callback, as HttpClient.download takes.
    @Suppress("LongParameterList")
    internal suspend fun downloadPackEntries(source: PackSource, bundleId: String, missingFilesByHash: MutableMap<String, BundleManifest.File>, progress: (Long, Long) -> Unit): Long {
        val file = resolvePackFile(bundleId = bundleId, url = source.url)
        downloadPackFile(source, file, progress)
        try {
            // The path answers a length of zero for a file the system removed, as a cleared cache does.
            if (!file.isFile) throw resolveUnpackFailure(UnpackFailureKind.DEVICE, FileNotFoundException("the pack file is gone"))
            val packBytes = file.length()
            if (packBytes != source.sizeBytes) throw DownloadFailure.ContentMismatched("The pack holds $packBytes of its ${source.sizeBytes} bytes")
            writePackEntries(file, packBytes, missingFilesByHash)
            verifyNoFileMissing(missingFilesByHash)
            return packBytes
        } catch (mismatch: DownloadFailure.ContentMismatched) {
            throw PackFailure(mismatch)
        } finally {
            file.delete()
        }
    }

    // A name stable per URL lets a later attempt resume the pack.
    internal fun resolvePackFile(bundleId: String, url: String) = File(temporaryDirectory, "$bundleId-${Hashing.sha256Hex(url).take(16)}.pack")

    private fun verifyNoFileMissing(missingFilesByHash: Map<String, BundleManifest.File>) {
        val missingFile = missingFilesByHash.values.minByOrNull { it.path } ?: return
        throw DownloadFailure.ContentMismatched("The pack lacks the file ${missingFile.path}")
    }

    private suspend fun downloadPackFile(source: PackSource, file: File, progress: (Long, Long) -> Unit) {
        try {
            fetchWithRetries { http.download(source.url, file, source.sizeBytes, progress) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (deviceFailure: DownloadFailure.DownloadFailed) {
            throw deviceFailure
        } catch (mismatch: DownloadFailure.ContentMismatched) {
            throw PackFailure(mismatch)
        } catch (refusal: HttpStatusException) {
            throw PackFailure(DownloadFailure.DownloadFailed("HTTP ${refusal.status} for the pack"))
        } catch (unanswered: Exception) {
            throw PackFailure(DownloadFailure.DownloadFailed("The pack could not be downloaded: ${unanswered.message}"))
        }
    }

    private fun writePackEntries(file: File, packBytes: Long, missingFilesByHash: MutableMap<String, BundleManifest.File>) {
        try {
            PackFileInputStream(readFromStorage { file.inputStream() }).buffered().use { input ->
                PackReader.forEachEntry(input, packBytes) { entry, body -> writePackEntry(entry, body, missingFilesByHash) }
            }
        } catch (failure: Throwable) {
            throw resolveUnpackFailure(resolveUnpackFailureKind(failure), failure)
        }
    }

    private fun writePackEntry(entry: PackEntry, body: InputStream, missingFilesByHash: MutableMap<String, BundleManifest.File>) {
        when (entry) {
            is PackEntry.File -> {
                val missingFile = missingFilesByHash[entry.sha256] ?: return
                Gzip.decompress(body, missingFile.sizeBytes).use { content -> files.writeFile(content, entry.sha256) }
                missingFilesByHash.remove(entry.sha256)
            }

            is PackEntry.Patch -> {
                val missingFile = missingFilesByHash[entry.toSha256] ?: return
                if (applyPatch(entry, body, missingFile.sizeBytes)) missingFilesByHash.remove(entry.toSha256)
            }
        }
    }

    private fun resolveUnpackFailureKind(failure: Throwable) = when {
        failure is OutOfMemoryError -> UnpackFailureKind.MEMORY
        isContentFailure(failure) -> UnpackFailureKind.CONTENT
        failure is IOException -> UnpackFailureKind.STORAGE
        else -> UnpackFailureKind.DEVICE
    }

    private fun isContentFailure(failure: Throwable) = failure is PackFormatException || failure is GzipFormatException || failure is GzipSizeException || failure is HashMismatchException

    private fun resolveUnpackFailure(kind: UnpackFailureKind, cause: Throwable): DownloadFailure = when (kind) {
        UnpackFailureKind.CONTENT -> DownloadFailure.ContentMismatched("The pack did not verify: ${cause.message}")
        UnpackFailureKind.MEMORY -> DownloadFailure.DownloadFailed("The device ran out of memory while it unpacked the pack")
        UnpackFailureKind.STORAGE -> DownloadFailure.DownloadFailed("The pack could not be written to the device's storage: ${cause.message}")
        UnpackFailureKind.DEVICE -> DownloadFailure.DownloadFailed("The device failed while it unpacked the pack: ${cause.message}")
    }

    internal fun applyPatch(entry: PackEntry.Patch, body: InputStream, maximumBytes: Long): Boolean = try {
        writePatchedFile(entry, body, maximumBytes)
    } catch (failure: Throwable) {
        if (!isPatchFailure(failure)) throw failure
        false
    }

    // Every failure of the patch code means the patch does not apply, a native library missing for the ABI and memory that runs out included.
    private fun isPatchFailure(failure: Throwable) = failure is Exception || failure is LinkageError || failure is OutOfMemoryError

    private fun writePatchedFile(entry: PackEntry.Patch, body: InputStream, maximumBytes: Long): Boolean {
        val directory = File(temporaryDirectory, "${UUID.randomUUID()}.patching")
        directory.mkdirs()
        try {
            val base = preparePatchBase(entry.fromSha256, directory) ?: return false
            val patch = File(directory, "patch").apply { outputStream().use { body.copyTo(it) } }
            val patched = File(directory, "patched")
            Bspatch.apply(patch = patch, old = base, new = patched, maximumBytes = maximumBytes)
            patched.inputStream().use { content -> files.writeFile(content, entry.toSha256) }
            return true
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun preparePatchBase(sha256: String, directory: File): File? {
        if (files.hasFile(sha256)) return files.file(sha256)
        if (!embedded.has(sha256)) return null
        return File(directory, "base").also { embedded.copyFile(sha256, it) }
    }

    internal fun resolvePinnedUrl(url: String): String {
        if (!configuration.isUrlOnConfiguredHost(url)) throw DownloadFailure.ManifestInvalid("$url is not on a configured host")
        return url
    }

    // A refusal such as a 404 or 410 throws at once, since the object will not appear.
    private suspend fun <T> fetchWithRetries(fetch: suspend () -> T): T {
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

// A failed read of the pack file is the device's, so the streams above it never take it for bytes that do not inflate.
private class PackFileInputStream(private val input: InputStream) : InputStream() {
    override fun read(): Int = readFromStorage { input.read() }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = readFromStorage { input.read(buffer, offset, length) }

    override fun skip(count: Long): Long = readFromStorage { input.skip(count) }

    override fun close() = input.close()
}

private class PackFileReadException(cause: IOException) : Exception(cause.message, cause)

private inline fun <T> readFromStorage(read: () -> T): T = try {
    read()
} catch (exception: IOException) {
    throw PackFileReadException(exception)
}
