package com.hotcodepush.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** The three HTTP shapes the core needs: a small GET, a small POST and a large download that resumes. */
interface HttpClient {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse

    suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse

    /**
     * Downloads to the file, appending from its current size with a `Range` request when it exists; what arrived stays
     * when the connection drops, for the next attempt to resume. Past `maximumBytes` it stops and deletes the file; on a
     * status other than 200 or 206 — a redirect included, which it never follows — it deletes the file and throws `HttpStatusException`.
     */
    suspend fun download(url: String, file: File, maximumBytes: Long, progress: (Long, Long) -> Unit)
}

/** A download answered with a status other than 200 or 206; a redirect is one, since a download follows none. */
class HttpStatusException(val status: Int) : Exception("HTTP $status")

class OkHttpClientAdapter(private val client: OkHttpClient = sharedClient) : HttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = send(request(url, headers).build())

    override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse = send(request(url, headers).post(body.toRequestBody()).build())

    private fun send(request: Request): HttpResponse = client.newCall(request).execute().use { response ->
        val responseHeaders = response.headers.names().associateWith { response.headers[it] ?: "" }
        HttpResponse(response.code, responseHeaders, response.body.bytes())
    }

    private fun request(url: String, headers: Map<String, String>) = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }

    override suspend fun download(url: String, file: File, maximumBytes: Long, progress: (Long, Long) -> Unit) {
        val existing = if (file.isFile) file.length() else 0L
        val request = Request.Builder().url(url).apply { if (existing > 0) header("Range", "bytes=$existing-") }.build()
        downloadClient.newCall(request).execute().use { response ->
            if (response.code != 200 && response.code != 206) {
                file.delete()
                throw HttpStatusException(response.code)
            }
            file.parentFile?.mkdirs()
            val append = response.code == 206 && existing > 0
            var written = if (append) existing else 0L
            FileOutputStream(file, append).use { output ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > maximumBytes) {
                            file.delete()
                            throw DownloadFailure.DownloadFailed("${url.substringAfterLast('/')} is larger than its $maximumBytes bytes")
                        }
                        output.write(buffer, 0, read)
                        progress(written, maximumBytes)
                    }
                }
            }
        }
    }

    /** The same pools and interceptors, following no redirect: a download stays on the URL the core pinned. */
    private val downloadClient: OkHttpClient by lazy { client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    companion object {
        /** One client per process, as OkHttp asks: its pools and threads outlive every activity. */
        private val sharedClient: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
    }
}
