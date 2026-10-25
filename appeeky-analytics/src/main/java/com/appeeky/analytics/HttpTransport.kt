package com.appeeky.analytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HttpURLConnection client: no extra dependency, available on every API level. */
internal class HttpTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 15_000,
) : Transport {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): Int =
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as? HttpURLConnection
                ?: throw IOException("not an HTTP URL: $url")
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.doOutput = true
                connection.useCaches = false
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                for ((name, value) in headers) connection.setRequestProperty(name, value)
                connection.outputStream.use { it.write(bytes) }
                val status = connection.responseCode
                // Drain the body so the connection can be reused.
                runCatching {
                    (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() }
                }
                status
            } finally {
                connection.disconnect()
            }
        }
}
