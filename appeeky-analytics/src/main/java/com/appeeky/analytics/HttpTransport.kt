package com.appeeky.analytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A larger answer is not one of ours; its body is ignored. */
private const val MAX_BODY_BYTES = 64 * 1024

/** HttpURLConnection client: no extra dependency, available on every API level. */
internal class HttpTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 15_000,
) : Transport {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): TransportResponse =
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
                // Read the body (it may carry warnings); this also drains it so
                // the connection can be reused.
                val answer = runCatching {
                    (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() }
                }.getOrNull()
                TransportResponse(status, answer?.takeIf { it.size <= MAX_BODY_BYTES }?.toString(Charsets.UTF_8))
            } finally {
                connection.disconnect()
            }
        }
}
