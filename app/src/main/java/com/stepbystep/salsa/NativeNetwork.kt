package com.stepbystep.salsa

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

/** Native HTTPS transport: appassets is not an allowed browser Origin on the existing cloud API. */
class NativeNetwork(
    private val activity: Activity,
    private val media: NativeMedia,
    private val script: (String) -> Unit,
) {
    private val pool = Executors.newFixedThreadPool(3)
    private val directory =
        File(activity.cacheDir, "network-responses").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }

    private data class Job(
        @Volatile var canceled: Boolean = false,
        @Volatile var connection: HttpURLConnection? = null,
    )

    private val jobs = ConcurrentHashMap<String, Job>()
    private val responses = ConcurrentHashMap<String, File>()

    @JavascriptInterface
    fun start(
        id: String,
        address: String,
        method: String,
        headers: String,
        body: String,
        bodyToken: String,
    ) {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val job = Job()
        require(jobs.putIfAbsent(id, job) == null)
        pool.execute {
            var response: File? = null
            try {
                val url = URL(address)
                require(
                    url.protocol == "https" &&
                        url.host == "tzaprobvauikssnpiucl.supabase.co" &&
                        (url.port == -1 || url.port == 443) &&
                        url.userInfo == null
                )
                require(
                    url.path.startsWith("/auth/v1/") ||
                        url.path == "/functions/v1/cloud" ||
                        url.path.startsWith("/storage/v1/")
                )
                require(method in listOf("GET", "POST", "PUT", "DELETE", "HEAD"))
                require(body.length <= 1024 * 1024 && headers.length <= 65536)
                if (job.canceled) error("Abgebrochen")
                val connection = url.openConnection() as HttpURLConnection
                job.connection = connection
                connection.requestMethod = method
                connection.connectTimeout = 30000
                connection.readTimeout = 120000
                connection.instanceFollowRedirects = false
                val h = JSONObject(headers)
                h.keys().forEach { key ->
                    require(
                        key.lowercase() in
                            listOf("apikey", "authorization", "content-type", "accept", "x-upsert")
                    )
                    val value = h.getString(key)
                    require(!value.contains('\r') && !value.contains('\n'))
                    connection.setRequestProperty(key, value)
                }
                if (bodyToken.isNotEmpty()) {
                    val file = media.file(bodyToken)
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(file.length())
                    connection.outputStream.use { out ->
                        file.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                if (job.canceled) error("Abgebrochen")
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                } else if (body.isNotEmpty()) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val code = connection.responseCode
                val file = File(directory, id)
                response = file
                val input = if (code >= 400) connection.errorStream else connection.inputStream
                file.outputStream().use { out ->
                    input?.use { stream ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            if (job.canceled) error("Abgebrochen")
                            val n = stream.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                    }
                }
                if (job.canceled) error("Abgebrochen")
                val contentType = connection.contentType ?: "application/octet-stream"
                responses[id] = file
                val result =
                    JSONObject()
                        .put("status", code)
                        .put("type", contentType)
                        .put("url", "https://appassets.androidplatform.net/network/$id")
                script("window.AndroidNetworkResult(${JSONObject.quote(id)},$result)")
            } catch (e: Exception) {
                response?.delete()
                responses.remove(id)
                if (!job.canceled)
                    script(
                        "window.AndroidNetworkResult(${JSONObject.quote(id)},${JSONObject().put("error","Cloud-Verbindung fehlgeschlagen. Bitte Internetverbindung prüfen und erneut versuchen.")})"
                    )
            } finally {
                job.connection?.disconnect()
                jobs.remove(id)
                if (bodyToken.isNotEmpty()) media.abort(bodyToken)
            }
        }
    }

    @JavascriptInterface
    fun cancel(id: String) {
        jobs[id]?.let {
            it.canceled = true
            it.connection?.disconnect()
        }
        responses.remove(id)?.delete()
    }

    fun response(id: String): WebResourceResponse {
        val file =
            responses.remove(id)
                ?: return WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    404,
                    "Not Found",
                    emptyMap(),
                    java.io.ByteArrayInputStream(ByteArray(0)),
                )
        val stream =
            object : FilterInputStream(FileInputStream(file)) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        file.delete()
                    }
                }
            }
        return WebResourceResponse("application/octet-stream", null, stream)
    }

    fun close() {
        jobs.keys.toList().forEach(::cancel)
        pool.shutdownNow()
        responses.values.forEach { it.delete() }
        responses.clear()
    }
}
