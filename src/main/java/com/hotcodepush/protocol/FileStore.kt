package com.hotcodepush.protocol

import android.os.Build
import java.io.File
import java.nio.file.Files

class HashMismatchException(val expected: String, val actual: String) : Exception("Expected $expected, got $actual")

/** The SDK's directory in the app's private storage: content-addressed files and one manifest per bundle. */
class FileStore(val rootDirectory: File) {
    val filesDirectory: File get() = File(rootDirectory, "files")
    val bundlesDirectory: File get() = File(rootDirectory, "bundles")

    fun file(sha256: String): File = File(filesDirectory, sha256)

    fun hasFile(sha256: String): Boolean = file(sha256).isFile

    /** Verifies the content against its hash, then writes it atomically; a file that exists is the file. */
    fun writeFile(content: ByteArray, sha256: String) {
        val actual = Hashing.sha256Hex(content)
        if (actual != sha256) throw HashMismatchException(sha256, actual)
        filesDirectory.mkdirs()
        val temporary = File(filesDirectory, "$sha256.part")
        temporary.writeBytes(content)
        if (!temporary.renameTo(file(sha256))) {
            file(sha256).writeBytes(content)
            temporary.delete()
        }
    }

    fun manifestFile(bundleId: String): File = File(File(bundlesDirectory, bundleId), "manifest.json")

    fun readManifest(bundleId: String): BundleManifest? = manifestFile(bundleId).takeIf { it.isFile }?.let { file ->
        runCatching { BundleManifest.fromJson(org.json.JSONObject(file.readText())) }.getOrNull()
    }

    fun writeManifest(manifest: BundleManifest, bundleId: String) {
        val file = manifestFile(bundleId)
        file.parentFile?.mkdirs()
        file.writeText(manifest.toJson().toString())
    }

    fun bundleIds(): List<String> = (bundlesDirectory.list() ?: emptyArray()).filter { readManifest(it) != null }.sorted()

    fun deleteBundle(bundleId: String) {
        File(bundlesDirectory, bundleId).deleteRecursively()
    }

    /** Everything no kept bundle lists: the cleanup after a start, no setting. */
    fun deleteUnusedFiles(keptBundleIds: Set<String>) {
        bundleIds().filter { it !in keptBundleIds }.forEach(::deleteBundle)
        val referenced = keptBundleIds.mapNotNull(::readManifest).flatMap { manifest -> manifest.files.map { it.sha256 } }.toSet()
        (filesDirectory.list() ?: emptyArray()).filter { it !in referenced }.forEach { file(it).delete() }
    }

    fun deleteEverything() {
        rootDirectory.deleteRecursively()
    }

    /** The bytes free on the store's volume, measured on its nearest existing directory. */
    fun availableBytes(): Long = generateSequence(rootDirectory) { it.parentFile }.first { it.exists() }.usableSpace

    /** Whether every file the manifest lists is on disk; existence is the check, not a re-hash. */
    fun isComplete(manifest: BundleManifest, embedded: EmbeddedBundle): Boolean = manifest.files.all { hasFile(it.sha256) || embedded.has(it.sha256) }
}

/** The files compiled into the binary, counted as present by hash; where they are is the platform's. */
interface EmbeddedBundle {
    fun has(sha256: String): Boolean

    /** Copies the embedded file with that hash to the destination. */
    fun copyFile(sha256: String, destination: File)
}

/** Lays a bundle out by path for the WebView, linking to the content-addressed files where the platform allows. */
object BundleProjection {
    fun project(manifest: BundleManifest, files: FileStore, embedded: EmbeddedBundle, directory: File) {
        directory.deleteRecursively()
        directory.mkdirs()
        for (file in manifest.files) {
            val destination = File(directory, file.path)
            destination.parentFile?.mkdirs()
            if (files.hasFile(file.sha256)) {
                val source = files.file(file.sha256)
                if (!link(source, destination)) source.copyTo(destination, overwrite = true)
            } else {
                embedded.copyFile(file.sha256, destination)
            }
        }
    }

    /** `Files.createLink` arrived with API 26; below it, and wherever a link fails, the tree is a copy. */
    private fun link(source: File, destination: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            Files.createLink(destination.toPath(), source.toPath())
            true
        } catch (exception: Exception) {
            false
        }
    }
}
