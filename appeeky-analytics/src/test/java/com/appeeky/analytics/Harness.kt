package com.appeeky.analytics

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.IOException

/** A sent batch, parsed back from JSON. */
internal class Sent(val url: String, val headers: Map<String, String>, val raw: String) {
    @Suppress("UNCHECKED_CAST")
    val body: Map<String, Any?> = Json.parse(raw) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val events: List<Map<String, Any?>> get() = body["events"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    val context: Map<String, Any?> get() = body["context"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val attribution: Map<String, Any?>? get() = body["attribution"] as Map<String, Any?>?
}

internal class MemoryStorage : KeyValueStorage {
    val map = HashMap<String, String>()
    override fun getItem(key: String): String? = map[key]
    override fun setItem(key: String, value: String) {
        map[key] = value
    }

    fun queueSize(): Int = QueuedEvent.listFromJson(map[QUEUE_KEY]).size
}

/**
 * Fake storage, network, clock and ids, like core.test.ts. Response statuses
 * are used in order (then 202); -1 throws like an offline device.
 */
internal class Harness(
    scheduler: TestCoroutineScheduler,
    statuses: List<Int> = emptyList(),
    private val referrer: (suspend () -> String?)? = null,
    private val initialUrl: (suspend () -> String?)? = { "myapp://open?aky_c=launch" },
    private val context: DeviceContext = DeviceContext(platform = "android", osVersion = "15", locale = "tr-TR"),
) {
    val store = MemoryStorage()
    val sent = mutableListOf<Sent>()
    var clock = 1_000_000L
    private var ids = 0
    private val responses = ArrayDeque(statuses)
    val dispatcher = StandardTestDispatcher(scheduler)

    val transport = Transport { url, headers, body ->
        sent.add(Sent(url, headers, body))
        val status = responses.removeFirstOrNull() ?: 202
        if (status == -1) throw IOException("offline")
        status
    }

    private val timers = object : Timers {
        override fun every(ms: Long, block: () -> Unit): () -> Unit = {}
        override fun after(ms: Long, block: () -> Unit) = block()
    }

    fun make(
        endpoint: String = "https://analytics.test/v1/ingest",
        flushAt: Int = 1_000,
        maxQueueSize: Int = 1_000,
        sessionTimeoutMs: Long = 30 * 60 * 1000L,
        disabled: Boolean = false,
    ) = AppeekyCore(
        CoreOptions(
            key = "apk_test",
            endpoint = endpoint,
            flushAt = flushAt,
            maxQueueSize = maxQueueSize,
            sessionTimeoutMs = sessionTimeoutMs,
            disabled = disabled,
        ),
        CoreDeps(
            storage = store,
            transport = transport,
            dispatcher = dispatcher,
            now = { clock },
            randomId = { "id${++ids}" },
            deviceContext = { context },
            installReferrer = referrer,
            initialUrl = initialUrl,
            timers = timers,
        ),
    )

    fun advance(ms: Long) {
        clock += ms
    }

    val allEvents: List<Map<String, Any?>> get() = sent.flatMap { it.events }
}
