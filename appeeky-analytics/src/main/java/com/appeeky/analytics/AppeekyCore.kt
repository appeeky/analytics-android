package com.appeeky.analytics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Platform-independent client: install id, sessions, offline queue, batching
 * and retries. Appeeky.kt injects Android storage, the device context, the
 * Play Install Referrer and the app lifecycle; tests inject fakes.
 *
 * Every piece of mutable state is touched only on `deps.dispatcher`, which
 * must run one task at a time. Like a JavaScript event loop, other work can
 * interleave only at suspension points (the network call, the referrer).
 */

internal const val SDK_NAME = "android"
internal const val STATE_KEY = "@appeeky/analytics:state"
internal const val QUEUE_KEY = "@appeeky/analytics:queue"
internal const val MAX_BATCH = 100
private const val MAX_BACKOFF_MS = 5 * 60 * 1000L
internal const val ATTRIBUTION_TIMEOUT_MS = 3_000L
/** The hosted service answers this once the account's monthly event quota is used up. */
private const val QUOTA_STATUS = 402
/** Wait before sending ordinary events again after a quota answer; doubles up to the maximum. */
private const val QUOTA_RETRY_MS = 15 * 60 * 1000L
private const val MAX_QUOTA_RETRY_MS = 6 * 60 * 60 * 1000L
/**
 * Events the server stores even past the quota, and the queue never evicts
 * before ordinary ones. Keep in step with CRITICAL_EVENT_NAMES in
 * packages/core/src/service.ts.
 */
private val CRITICAL_EVENTS = setOf("first_open", "ad_revenue")
/** A batch rejected this many times with the attribution in it: give the attribution up. */
private const val MAX_ATTRIBUTION_REJECTS = 3
/** Server limit (packages/core/src/ingest-payload.ts); longer values would reject the batch. */
private const val MAX_URL_LENGTH = 2_048
/** A single foreground stretch longer than this is clamped (clock jumps, stuck timers). */
private const val MAX_FOREGROUND_MS = 4 * 60 * 60 * 1000L
private val EVENT_NAME = Regex("^[A-Za-z0-9_.:-]+$")
/** Server warnings the SDK explains; others are ignored. */
private val WARNING_TEXT = mapOf(
    "platform_mismatch" to
        "this key belongs to an app on the other store. Use the iOS app's key in the iOS build and the Android app's key in the Android build; until then both platforms are mixed in one app's reports.",
)

/** Small persisted values and the queue (files on Android). Blocking calls. */
internal interface KeyValueStorage {
    fun getItem(key: String): String?
    fun setItem(key: String, value: String)
}

/** An HTTP answer: the status and, when it could be read, the response body. */
internal class TransportResponse(val status: Int, val body: String? = null)

/** POSTs a JSON body and returns the answer. Throws when the request could not be made. */
internal fun interface Transport {
    suspend fun post(url: String, headers: Map<String, String>, body: String): TransportResponse
}

internal interface Timers {
    /** Runs [block] every [ms]; returns a function that stops it. */
    fun every(ms: Long, block: () -> Unit): () -> Unit
    fun after(ms: Long, block: () -> Unit)
}

internal data class DeviceContext(
    val platform: String? = "android",
    val appVersion: String? = null,
    val build: String? = null,
    val osVersion: String? = null,
    val deviceModel: String? = null,
    val locale: String? = null,
    /**
     * A debuggable build or an emulator. The server stores the install and
     * keeps it out of reports.
     */
    val testDevice: Boolean = false,
)

internal data class CoreOptions(
    val key: String,
    val endpoint: String = Appeeky.DEFAULT_ENDPOINT,
    val flushIntervalMs: Long = 10_000,
    val flushAt: Int = 20,
    val maxQueueSize: Int = 1_000,
    val sessionTimeoutMs: Long = 30 * 60 * 1000L,
    val disabled: Boolean = false,
    val debug: Boolean = false,
)

internal class CoreDeps(
    val storage: KeyValueStorage,
    val transport: Transport,
    /** Serial dispatcher (one task at a time) that owns all client state. */
    val dispatcher: CoroutineDispatcher,
    val now: () -> Long,
    val randomId: () -> String,
    val deviceContext: () -> DeviceContext,
    /** Android: the Play Install Referrer string. */
    val installReferrer: (suspend () -> String?)? = null,
    /** URL the app was opened with, for campaign links. */
    val initialUrl: (suspend () -> String?)? = null,
    /** Null: coroutine timers on [dispatcher]. */
    val timers: Timers? = null,
    val log: (level: String, message: String) -> Unit = { _, _ -> },
)

internal class PersistedState(
    var installId: String,
    var userId: String? = null,
    var sessionId: String? = null,
    var lastActiveAt: Long? = null,
    var attributionSent: Boolean = false,
    /** Batches the server rejected (4xx) while carrying the attribution. */
    var attributionRejects: Int = 0,
    /** First launch time. */
    var installedAt: Long? = null,
    /** The deferred deep link was already handed to the app. */
    var deepLinkDelivered: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("installId", installId)
        userId?.let { put("userId", it) }
        sessionId?.let { put("sessionId", it) }
        lastActiveAt?.let { put("lastActiveAt", it) }
        if (attributionSent) put("attributionSent", true)
        if (attributionRejects > 0) put("attributionRejects", attributionRejects)
        installedAt?.let { put("installedAt", it) }
        if (deepLinkDelivered) put("deepLinkDelivered", true)
    }

    companion object {
        fun fromJson(raw: String?): PersistedState? {
            val map = Json.parseOrNull(raw) as? Map<*, *> ?: return null
            val installId = (map["installId"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return PersistedState(
                installId = installId,
                userId = map["userId"] as? String,
                sessionId = map["sessionId"] as? String,
                lastActiveAt = (map["lastActiveAt"] as? Number)?.toLong(),
                attributionSent = map["attributionSent"] == true,
                attributionRejects = (map["attributionRejects"] as? Number)?.toInt() ?: 0,
                installedAt = (map["installedAt"] as? Number)?.toLong(),
                deepLinkDelivered = map["deepLinkDelivered"] == true,
            )
        }
    }
}

internal data class QueuedEvent(
    val id: String,
    val name: String,
    val ts: Long,
    val sessionId: String? = null,
    val screen: String? = null,
    val props: Map<String, Any?>? = null,
    val value: Double? = null,
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("id", id)
        put("name", name)
        put("ts", ts)
        sessionId?.let { put("sessionId", it) }
        screen?.let { put("screen", it) }
        props?.let { put("props", it) }
        value?.let { put("value", it) }
    }

    companion object {
        fun fromMap(map: Map<*, *>): QueuedEvent? {
            val id = map["id"] as? String ?: return null
            val name = map["name"] as? String ?: return null
            val ts = (map["ts"] as? Number)?.toLong() ?: return null
            @Suppress("UNCHECKED_CAST")
            return QueuedEvent(
                id = id,
                name = name,
                ts = ts,
                sessionId = map["sessionId"] as? String,
                screen = map["screen"] as? String,
                props = (map["props"] as? Map<String, Any?>),
                value = (map["value"] as? Number)?.toDouble(),
            )
        }

        fun listFromJson(raw: String?): MutableList<QueuedEvent> {
            val list = Json.parseOrNull(raw) as? List<*> ?: return ArrayList()
            return list.mapNotNullTo(ArrayList()) { (it as? Map<*, *>)?.let(::fromMap) }
        }
    }
}

/** Keys up to 64 characters, strings up to 512; values must be strings, numbers, booleans or null. */
internal fun cleanProps(props: Map<String, Any?>?): Map<String, Any?>? {
    if (props == null) return null
    val out = LinkedHashMap<String, Any?>()
    for ((k, v) in props) {
        val key = k.take(64)
        if (key.isEmpty()) continue
        out[key] = when (v) {
            null -> null
            is String -> v.take(512)
            is Boolean -> v
            is Double -> if (v.isFinite()) v else null
            is Float -> if (v.isFinite()) v.toDouble() else null
            is Int, is Long, is Short, is Byte -> v
            is Number -> v.toDouble().takeIf { it.isFinite() }
            else -> v.toString().take(512)
        }
    }
    return out.ifEmpty { null }
}

/** `aky_dl` from a Play referrer, or the opening URL when it came from a smart link. */
internal fun deferredDeepLinkFrom(installReferrer: String?, openUrl: String?): String? {
    if (!installReferrer.isNullOrEmpty()) {
        val dl = queryParam(installReferrer, "aky_dl")
        if (!dl.isNullOrEmpty()) return dl
    }
    if (openUrl != null && Regex("[?&]aky_l=").containsMatchIn(openUrl)) return openUrl
    return null
}

/** One value from an `a=1&b=2` string, decoded like URLSearchParams (`+` is a space). */
internal fun queryParam(query: String, name: String): String? {
    for (part in query.split('&')) {
        val eq = part.indexOf('=')
        val k = if (eq >= 0) part.substring(0, eq) else part
        if (decode(k) != name) continue
        return decode(if (eq >= 0) part.substring(eq + 1) else "")
    }
    return null
}

private fun decode(s: String): String = try {
    java.net.URLDecoder.decode(s, "UTF-8")
} catch (_: Exception) {
    s
}

/** Context fields the ingest schema accepts: trimmed, non-empty, within its length limits. */
private fun contextMap(context: DeviceContext, userId: String?): Map<String, Any> = buildMap {
    fun field(name: String, value: String?, max: Int) {
        val v = value?.trim()?.take(max)?.trim()
        if (!v.isNullOrEmpty()) put(name, v)
    }
    if (context.platform == "android" || context.platform == "ios") put("platform", context.platform)
    field("appVersion", context.appVersion, 64)
    field("build", context.build, 64)
    field("osVersion", context.osVersion, 64)
    field("deviceModel", context.deviceModel, 128)
    field("locale", context.locale, 35)
    field("userId", userId, 256)
    // Only ever sent as true; a real device leaves the field out.
    if (context.testDevice) put("testDevice", true)
}

/** The `warnings` strings of a response body; empty for a missing or malformed body. */
internal fun readWarnings(body: String?): List<String> {
    val map = Json.parseOrNull(body) as? Map<*, *> ?: return emptyList()
    return (map["warnings"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
}

internal class AppeekyCore(private val opts: CoreOptions, private val deps: CoreDeps) {
    private val scope = CoroutineScope(
        SupervisorJob() + deps.dispatcher + CoroutineExceptionHandler { _, e -> log("error", e.message ?: e.toString()) },
    )
    private var state: PersistedState? = null
    private var queue: MutableList<QueuedEvent> = ArrayList()
    /** Null until collected; an empty map means there was nothing to send. */
    private var attribution: Map<String, String>? = null
    private var context = DeviceContext()
    /** Server warnings already handled in this launch. */
    private val warned = HashSet<String>()
    private var ready: Deferred<Unit>? = null
    /** Completes once first-launch attribution is collected; batches wait for it. */
    private var attributionReady: Deferred<Unit>? = null
    /** In-app destination from the smart link that brought this install, if any. */
    private var deferredDeepLink: String? = null
    private var flushing: Deferred<Unit>? = null
    private var stopTimer: (() -> Unit)? = null
    private var backoffMs = 0L
    /** When the app last came to the foreground; null while in the background. */
    private var foregroundSince: Long? = null
    private var retryAt = 0L
    /** While now < quotaUntil only critical events are sent; the rest wait in the queue. */
    private var quotaUntil = 0L
    private var quotaBackoffMs = 0L
    @Volatile private var installIdValue: String? = null

    private val timers: Timers = deps.timers ?: object : Timers {
        override fun every(ms: Long, block: () -> Unit): () -> Unit {
            val job = scope.launch {
                while (isActive) {
                    delay(ms)
                    block()
                }
            }
            return { job.cancel() }
        }

        override fun after(ms: Long, block: () -> Unit) {
            scope.launch {
                delay(ms)
                block()
            }
        }
    }

    /** Load state, record first_open on a new install and start the first session. */
    suspend fun start() {
        if (opts.disabled) return
        val boot = withContext(deps.dispatcher) {
            ready ?: scope.async(start = CoroutineStart.LAZY) { boot() }.also {
                ready = it
                it.start()
            }
        }
        boot.await()
    }

    private suspend fun boot() {
        val rawState = runCatching { deps.storage.getItem(STATE_KEY) }.getOrNull()
        val rawQueue = runCatching { deps.storage.getItem(QUEUE_KEY) }.getOrNull()
        queue = QueuedEvent.listFromJson(rawQueue)
        val saved = PersistedState.fromJson(rawState)
        val isNewInstall = saved == null
        val st = saved ?: PersistedState(installId = deps.randomId())
        state = st
        installIdValue = st.installId
        context = deps.deviceContext()

        if (!st.attributionSent) {
            attributionReady = scope.async {
                try {
                    collectAttribution()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attribution = emptyMap()
                }
            }
        }

        // Session first, so first_open carries the session id.
        ensureSession()
        foregroundSince = deps.now()
        if (isNewInstall) {
            st.installedAt = deps.now()
            enqueue("first_open")
        }
        persist()

        stopTimer = timers.every(opts.flushIntervalMs) { beginFlush(false) }
        beginFlush(false)
    }

    fun track(name: String, props: Map<String, Any?>? = null, value: Double? = null) = whenReady {
        ensureSession()
        enqueue(name, props = cleanProps(props), value = value?.takeIf { it.isFinite() })
    }

    fun screen(name: String, props: Map<String, Any?>? = null) = whenReady {
        ensureSession()
        val screen = name.trim().take(128).trim()
        if (screen.isEmpty()) {
            log("warn", "screen() needs a non-empty name; ignored")
            return@whenReady
        }
        enqueue("screen", screen = screen, props = cleanProps(props))
    }

    /** Ad revenue from one impression, in USD. */
    fun adRevenue(valueUsd: Double, network: String?, adUnit: String?, format: String?) {
        if (!valueUsd.isFinite() || valueUsd < 0) return
        track(
            "ad_revenue",
            mapOf("network" to network, "ad_unit" to adUnit, "format" to format),
            Math.round(valueUsd * 1e6) / 1e6,
        )
    }

    /**
     * The in-app destination of the smart link that brought this install
     * (from the Play referrer) or opened the app on first launch. Returns it
     * once, on the first launch; null afterwards or when there is none.
     */
    suspend fun getDeferredDeepLink(): String? {
        if (opts.disabled) return null
        start()
        withContext(deps.dispatcher) { attributionReady }?.await()
        return withContext(deps.dispatcher) {
            val st = state
            val link = deferredDeepLink
            if (st == null || st.deepLinkDelivered || link == null) {
                null
            } else {
                st.deepLinkDelivered = true
                persist()
                link
            }
        }
    }

    /** Link this install to your RevenueCat / Superwall app user id so purchases get a source. */
    fun identify(userId: String?) = whenReady {
        val st = state ?: return@whenReady
        val id = userId?.trim()?.take(256)?.trim()?.takeIf { it.isNotEmpty() }
        // Apps call identify() on every launch. The id already rides on every
        // batch's context, so only a change is worth an event.
        if (id == st.userId) return@whenReady
        st.userId = id
        persist()
        // The user id rides on every batch's context; this event makes sure a
        // batch goes out soon so the install is linked before the first purchase.
        enqueue(if (id != null) "identify" else "reset")
        timers.after(1_000) { beginFlush(false) }
    }

    /** The app came to the foreground. */
    fun onForeground() = whenReady {
        ensureSession()
        if (foregroundSince == null) foregroundSince = deps.now()
    }

    /** The app went to the background: marks activity and flushes. */
    fun onBackground() = whenReady {
        val now = deps.now()
        state?.lastActiveAt = now
        // Time on screen since the last foreground; sessions add these up.
        foregroundSince?.let { since ->
            val foregroundMs = (now - since).coerceIn(0, MAX_FOREGROUND_MS)
            foregroundSince = null
            enqueue("app_background", props = mapOf("foreground_ms" to foregroundMs))
        }
        persist()
        beginFlush(true)
    }

    /** Send everything queued now. Returns when the queue is empty or a send failed. */
    suspend fun flush(force: Boolean = false) {
        if (opts.disabled) return
        withContext(deps.dispatcher) { beginFlush(force) }?.await()
    }

    fun shutdown() {
        scope.launch {
            stopTimer?.invoke()
            stopTimer = null
        }
    }

    /** Null until the client has started. */
    val installId: String? get() = installIdValue

    // ─── internals ────────────────────────────────────────────────────────────

    private fun whenReady(fn: suspend () -> Unit) {
        if (opts.disabled) return
        scope.launch {
            try {
                start()
                fn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("error", e.message ?: e.toString())
            }
        }
    }

    private suspend fun collectAttribution() = coroutineScope {
        val referrerJob = async { withTimeoutOrNull(ATTRIBUTION_TIMEOUT_MS) { safely(deps.installReferrer) } }
        val urlJob = async { withTimeoutOrNull(ATTRIBUTION_TIMEOUT_MS) { safely(deps.initialUrl) } }
        val (installReferrer, deepLinkUrl) = awaitAll(referrerJob, urlJob)
        deferredDeepLink = deferredDeepLinkFrom(installReferrer, deepLinkUrl)
        val out = LinkedHashMap<String, String>()
        installReferrer?.trim()?.takeIf { it.isNotEmpty() }?.let { out["installReferrer"] = it.take(MAX_URL_LENGTH) }
        deepLinkUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { out["deepLinkUrl"] = it.take(MAX_URL_LENGTH) }
        attribution = out
    }

    private suspend fun safely(fn: (suspend () -> String?)?): String? {
        if (fn == null) return null
        return try {
            fn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("warn", e.message ?: e.toString())
            null
        }
    }

    private fun ensureSession() {
        val st = state ?: return
        val now = deps.now()
        val last = st.lastActiveAt
        val expired = last == null || last == 0L || now - last > opts.sessionTimeoutMs
        if (st.sessionId == null || expired) {
            st.sessionId = deps.randomId()
            st.lastActiveAt = now
            enqueue("session_start")
        }
        st.lastActiveAt = now
    }

    private fun enqueue(
        rawName: String,
        screen: String? = null,
        props: Map<String, Any?>? = null,
        value: Double? = null,
    ) {
        val st = state ?: return
        val name = rawName.trim().take(64)
        if (!EVENT_NAME.matches(name)) {
            // The server rejects a whole batch over one bad name; drop just this event.
            log("warn", "event name \"$rawName\" may only use letters, digits, _ . : - (1-64 characters); ignored")
            return
        }
        queue.add(QueuedEvent(deps.randomId(), name, deps.now(), st.sessionId, screen, props, value))
        // Full: the oldest ordinary event goes first; first_open and ad revenue stay.
        while (queue.size > opts.maxQueueSize) {
            val oldest = queue.indexOfFirst { it.name !in CRITICAL_EVENTS }
            queue.removeAt(if (oldest == -1) 0 else oldest)
        }
        persistQueue()
        if (queue.size >= opts.flushAt) beginFlush(false)
    }

    /** Must run on the client dispatcher. Returns the running flush, if any. */
    private fun beginFlush(force: Boolean): Deferred<Unit>? {
        if (opts.disabled || state == null) return null
        flushing?.let { return it }
        if (!force && deps.now() < retryAt) return null
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                drain()
            } finally {
                flushing = null
            }
        }
        flushing = job
        job.start()
        return job
    }

    private suspend fun drain() {
        if (state?.attributionSent == false) attributionReady?.await()
        while (true) {
            val st = state ?: return
            val pending = attribution
            val sendAttribution = !st.attributionSent && pending != null
            val quotaMode = deps.now() < quotaUntil
            val batch = (if (quotaMode) queue.filter { it.name in CRITICAL_EVENTS } else queue).take(MAX_BATCH)
            if (batch.isEmpty() && !sendAttribution) return

            val body = LinkedHashMap<String, Any?>()
            body["installId"] = st.installId
            body["sdk"] = "$SDK_NAME/${BuildConfig.SDK_VERSION}"
            body["context"] = contextMap(context, st.userId)
            body["events"] = batch.map { it.toMap() }
            if (sendAttribution && !pending.isNullOrEmpty()) body["attribution"] = pending

            var status = 0
            try {
                val response = deps.transport.post(
                    opts.endpoint,
                    mapOf("Content-Type" to "application/json", "X-Appeeky-Key" to opts.key),
                    Json.encode(body),
                )
                status = response.status
                // The server stored the batch (or, past the quota, part of it).
                if (status in 200..299 || status == QUOTA_STATUS) reportWarnings(readWarnings(response.body))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("warn", e.message ?: e.toString())
            }

            val retryable = status == 0 || status == 429 || status >= 500
            if (retryable) {
                backoffMs = minOf(if (backoffMs > 0) backoffMs * 2 else 5_000L, MAX_BACKOFF_MS)
                retryAt = deps.now() + backoffMs
                log("warn", "batch not sent (HTTP $status), retrying in ${backoffMs / 1000}s")
                return
            }
            backoffMs = 0
            retryAt = 0
            val hadAttribution = body.containsKey("attribution")

            if (status == QUOTA_STATUS) {
                // Past the account's quota the server stored the install, its
                // attribution and the critical events. The other events stay
                // queued and are sent again later.
                val stored = batch.filter { it.name in CRITICAL_EVENTS }.mapTo(HashSet()) { it.id }
                queue.removeAll { it.id in stored }
                if (sendAttribution) markAttributionSent(st)
                persist()
                persistQueue()
                // Already waiting: go on with the critical events that are left.
                if (quotaMode) continue
                quotaBackoffMs = minOf(if (quotaBackoffMs > 0) quotaBackoffMs * 2 else QUOTA_RETRY_MS, MAX_QUOTA_RETRY_MS)
                quotaUntil = deps.now() + quotaBackoffMs
                log("warn", "event quota used up: installs and ad revenue are still sent, other events wait")
                return
            }

            val sent = batch.mapTo(HashSet()) { it.id }
            queue.removeAll { it.id in sent }
            if (status >= 400) {
                // The server will never accept this batch: drop its events
                // rather than retrying forever. The attribution is collected
                // only once, so it rides on the next batches before it is
                // given up.
                log("warn", "batch rejected with HTTP $status")
                if (hadAttribution) {
                    st.attributionRejects += 1
                    if (st.attributionRejects >= MAX_ATTRIBUTION_REJECTS) markAttributionSent(st)
                } else if (sendAttribution) {
                    markAttributionSent(st)
                }
            } else {
                quotaBackoffMs = 0
                quotaUntil = 0
                if (sendAttribution) markAttributionSent(st)
            }
            persist()
            persistQueue()
            // Rejected with the attribution still pending: wait for new events
            // instead of sending the attribution alone in a loop.
            if (status >= 400 && queue.isEmpty()) return
        }
    }

    private fun markAttributionSent(st: PersistedState) {
        st.attributionSent = true
        st.attributionRejects = 0
        attribution = null
    }

    private fun persist() {
        val st = state ?: return
        try {
            deps.storage.setItem(STATE_KEY, Json.encode(st.toMap()))
        } catch (e: Exception) {
            log("warn", "could not save state: ${e.message}")
        }
    }

    private fun persistQueue() {
        try {
            deps.storage.setItem(QUEUE_KEY, Json.encode(queue.map { it.toMap() }))
        } catch (e: Exception) {
            log("warn", "could not save queue: ${e.message}")
        }
    }

    /** Server warnings about a stored batch, said once per launch on a test device or with `debug`. */
    private fun reportWarnings(warnings: List<String>) {
        for (warning in warnings) {
            if (!warned.add(warning)) continue
            val text = WARNING_TEXT[warning] ?: continue
            if (opts.debug || context.testDevice) deps.log("warn", text)
        }
    }

    private fun log(level: String, message: String) {
        if (opts.debug) deps.log(level, message)
    }
}
