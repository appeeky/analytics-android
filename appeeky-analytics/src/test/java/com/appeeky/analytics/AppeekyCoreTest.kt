package com.appeeky.analytics

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files

/** Mirrors packages/sdk-expo/src/core.test.ts. */
class AppeekyCoreTest {
    private fun TestScope.tick() = advanceUntilIdle()

    @Test
    fun `first launch sends first_open, session_start and attribution once`() = runTest {
        val referrer = "utm_source=google-play&utm_medium=organic"
        val h = Harness(testScheduler, referrer = { referrer })
        val sdk = h.make()
        sdk.start()
        sdk.flush(true)

        assertEquals(1, h.sent.size)
        val first = h.sent[0]
        assertEquals("apk_test", first.headers["X-Appeeky-Key"])
        assertEquals("application/json", first.headers["Content-Type"])
        assertEquals(listOf("session_start", "first_open"), first.events.map { it["name"] })
        assertTrue("every event carries the session id", first.events.all { it["sessionId"] != null })
        assertEquals(mapOf("installReferrer" to referrer, "deepLinkUrl" to "myapp://open?aky_c=launch"), first.attribution)
        assertEquals("tr-TR", first.context["locale"])
        assertEquals("android", first.context["platform"])
        assertEquals("android/${BuildConfig.SDK_VERSION}", first.body["sdk"])

        sdk.screen("Paywall")
        tick()
        sdk.flush(true)
        assertEquals(2, h.sent.size)
        assertNull(h.sent[1].attribution)
        assertEquals("Paywall", h.sent[1].events[0]["screen"])
        assertEquals("screen", h.sent[1].events[0]["name"])
    }

    @Test
    fun `a relaunch keeps the install id and skips first_open`() = runTest {
        val h = Harness(testScheduler)
        val first = h.make()
        first.start()
        first.flush(true)
        first.shutdown()

        val second = h.make()
        second.start()
        second.track("opened_again", null, null)
        tick()
        second.flush(true)
        assertNotNull(first.installId)
        assertEquals(first.installId, second.installId)
        val names = h.sent.drop(1).flatMap { s -> s.events.map { it["name"] } }
        assertFalse(names.contains("first_open"))
        assertTrue(names.contains("opened_again"))
    }

    @Test
    fun `keeps the queue on network errors and 5xx, drops it on 4xx`() = runTest {
        val h = Harness(testScheduler, statuses = listOf(-1, 503, 202))
        val sdk = h.make()
        sdk.start()
        sdk.flush(true) // joins the boot flush: offline
        sdk.flush(true) // 503
        sdk.flush(true) // 202
        assertEquals(3, h.sent.size)
        assertEquals(listOf("session_start", "first_open"), h.sent[2].events.map { it["name"] })
        // The same event ids are retried, so the server can de-duplicate.
        assertEquals(h.sent[0].events.map { it["id"] }, h.sent[2].events.map { it["id"] })
        assertEquals(0, h.store.queueSize())

        val rejected = Harness(testScheduler, statuses = listOf(400))
        val sdk2 = rejected.make()
        sdk2.start()
        sdk2.flush(true)
        assertEquals(1, rejected.sent.size)
        assertEquals(0, rejected.store.queueSize())
    }

    @Test
    fun `429 is retried like a server error`() = runTest {
        val h = Harness(testScheduler, statuses = listOf(429))
        val sdk = h.make()
        sdk.start()
        sdk.flush(true)
        assertEquals(2, h.store.queueSize())
        sdk.flush(true)
        assertEquals(0, h.store.queueSize())
        assertEquals(h.sent[0].events.map { it["id"] }, h.sent[1].events.map { it["id"] })
    }

    @Test
    fun `backs off after a failure until forced`() = runTest {
        val h = Harness(testScheduler, statuses = listOf(500))
        val sdk = h.make()
        sdk.start()
        sdk.flush() // joins the boot flush, which fails
        sdk.flush() // within the backoff window: no request
        assertEquals(1, h.sent.size)
        h.advance(6_000)
        sdk.flush()
        assertEquals(2, h.sent.size)
    }

    @Test
    fun `backoff doubles up to five minutes`() = runTest {
        val h = Harness(testScheduler, statuses = List(10) { 500 })
        val sdk = h.make()
        sdk.start()
        sdk.flush()
        // 5s, then 10s: 9s later is still inside the second window.
        h.advance(5_000)
        sdk.flush()
        assertEquals(2, h.sent.size)
        h.advance(9_000)
        sdk.flush()
        assertEquals(2, h.sent.size)
        h.advance(1_000)
        sdk.flush()
        assertEquals(3, h.sent.size)
    }

    @Test
    fun `starts a new session after the timeout and carries identify()`() = runTest {
        val h = Harness(testScheduler)
        val sdk = h.make(sessionTimeoutMs = 1_000)
        sdk.start()
        sdk.flush(true)
        val firstSession = h.sent[0].events[0]["sessionId"]

        sdk.identify("rc_user_1")
        sdk.onBackground()
        tick()
        h.advance(5_000)
        sdk.onForeground()
        sdk.track("after_resume", null, null)
        tick()
        sdk.flush(true)

        val last = h.sent.last()
        assertEquals("rc_user_1", last.context["userId"])
        val resumed = last.events.first { it["name"] == "after_resume" }
        assertNotEquals(firstSession, resumed["sessionId"])
        assertTrue(h.allEvents.count { it["name"] == "session_start" } >= 2)
        assertTrue(h.allEvents.any { it["name"] == "identify" })
    }

    @Test
    fun `no new session within the timeout`() = runTest {
        val h = Harness(testScheduler)
        val sdk = h.make(sessionTimeoutMs = 60_000)
        sdk.start()
        sdk.onBackground()
        tick()
        h.advance(30_000)
        sdk.onForeground()
        sdk.track("back", null, null)
        tick()
        sdk.flush(true)
        assertEquals(1, h.allEvents.count { it["name"] == "session_start" })
        assertEquals(1, h.allEvents.map { it["sessionId"] }.toSet().size)
    }

    @Test
    fun `identify(null) sends reset and removes the user id`() = runTest {
        val h = Harness(testScheduler)
        val sdk = h.make()
        sdk.start()
        sdk.identify("rc_user_1")
        tick()
        sdk.identify(null)
        tick()
        sdk.flush(true)
        assertTrue(h.allEvents.any { it["name"] == "reset" })
        assertNull(h.sent.last().context["userId"])
        // A blank id would fail the schema on every batch; it counts as a logout.
        sdk.identify("   ")
        tick()
        sdk.flush(true)
        assertNull(h.sent.last().context["userId"])
    }

    @Test
    fun `identify survives a relaunch`() = runTest {
        val h = Harness(testScheduler)
        val first = h.make()
        first.start()
        first.identify("rc_user_9")
        tick()
        first.flush(true)
        first.shutdown()

        val second = h.make()
        second.start()
        second.track("later", null, null)
        tick()
        second.flush(true)
        assertEquals("rc_user_9", h.sent.last().context["userId"])
    }

    @Test
    fun `disabled sends nothing`() = runTest {
        val h = Harness(testScheduler)
        val sdk = h.make(disabled = true)
        sdk.start()
        sdk.track("x", null, null)
        tick()
        sdk.flush(true)
        assertEquals(0, h.sent.size)
        assertTrue(h.store.map.isEmpty())
        assertNull(sdk.getDeferredDeepLink())
    }

    @Test
    fun `app_background reports foreground time once per background`() = runTest {
        val h = Harness(testScheduler)
        val sdk = h.make()
        sdk.start()
        h.advance(42_000)
        sdk.onBackground()
        sdk.onBackground() // a second background without a foreground adds nothing
        tick()
        h.advance(10_000)
        sdk.onForeground()
        tick()
        h.advance(5_000)
        sdk.onBackground()
        tick()
        sdk.flush(true)
        val backgrounds = h.allEvents
            .filter { it["name"] == "app_background" }
            .map { (it["props"] as Map<*, *>)["foreground_ms"] }
        assertEquals(listOf(42_000L, 5_000L), backgrounds)
    }

    @Test
    fun `adRevenue sends a USD value and drops invalid amounts`() = runTest {
        val h = Harness(testScheduler)
        val sdk = h.make()
        sdk.start()
        sdk.adRevenue(0.0123456789, "admob", null, "rewarded")
        sdk.adRevenue(-1.0, null, null, null)
        sdk.adRevenue(Double.NaN, null, null, null)
        tick()
        sdk.flush(true)
        val ads = h.allEvents.filter { it["name"] == "ad_revenue" }
        assertEquals(1, ads.size)
        assertEquals(0.012346, ads[0]["value"])
        assertEquals("admob", (ads[0]["props"] as Map<*, *>)["network"])
    }

    @Test
    fun `sends to Appeeky's hosted endpoint unless one is given`() = runTest {
        val h = Harness(testScheduler)
        val sdk = h.make(endpoint = Appeeky.DEFAULT_ENDPOINT)
        sdk.start()
        sdk.flush(true)
        assertEquals(Appeeky.DEFAULT_ENDPOINT, h.sent[0].url)
        assertEquals("https://ingest.appeeky.com/v1/ingest", Appeeky.DEFAULT_ENDPOINT)
        assertEquals(Appeeky.DEFAULT_ENDPOINT, CoreOptions(key = "k").endpoint)
    }

    @Test
    fun `hands the Play referrer's deep link to the app once`() = runTest {
        val referrer = "utm_source=appeeky&aky_c=spring&aky_l=Ab3dE9x&aky_click=c1&aky_dl=" +
            URLEncoder.encode("myapp://offer/42", "UTF-8")
        val h = Harness(testScheduler, referrer = { referrer })
        val sdk = h.make()
        assertEquals("myapp://offer/42", sdk.getDeferredDeepLink())
        assertNull(sdk.getDeferredDeepLink())
        sdk.flush(true)
        assertEquals(referrer, h.sent[0].attribution?.get("installReferrer"))

        // Delivered once, also across launches.
        val again = h.make()
        assertNull(again.getDeferredDeepLink())
    }

    @Test
    fun `a smart link launch URL is the deferred deep link`() = runTest {
        val url = "myapp://offer/7?aky_c=spring&aky_l=Ab3dE9x"
        val h = Harness(testScheduler, initialUrl = { url })
        val sdk = h.make()
        assertEquals(url, sdk.getDeferredDeepLink())
        val plain = Harness(testScheduler, initialUrl = { "myapp://open?aky_c=launch" })
        assertNull(plain.make().getDeferredDeepLink())
    }

    @Test
    fun `the referrer is collected once and only on the first launch`() = runTest {
        var calls = 0
        val h = Harness(testScheduler, referrer = { calls++; "utm_source=x&utm_campaign=y" })
        val first = h.make()
        first.start()
        first.flush(true)
        first.shutdown()
        assertEquals(1, calls)

        val second = h.make()
        second.start()
        second.track("x", null, null)
        tick()
        second.flush(true)
        assertEquals(1, calls)
        assertEquals(1, h.sent.count { it.attribution != null })
    }

    @Test
    fun `attribution is kept until a batch with it is accepted`() = runTest {
        var calls = 0
        val h = Harness(testScheduler, statuses = listOf(503), referrer = { calls++; "utm_source=x&utm_campaign=y" })
        val sdk = h.make()
        sdk.start()
        sdk.flush(true) // 503
        sdk.flush(true) // 202
        assertNotNull(h.sent[0].attribution)
        assertNotNull(h.sent[1].attribution)
        assertEquals(1, calls)
    }

    @Test
    fun `a slow referrer times out after 3 seconds and the batch goes without it`() = runTest {
        val h = Harness(
            testScheduler,
            referrer = { kotlinx.coroutines.delay(60_000); "late" },
            initialUrl = { kotlinx.coroutines.delay(60_000); null },
        )
        val sdk = h.make()
        sdk.start()
        val started = testScheduler.currentTime
        sdk.flush(true)
        assertTrue(testScheduler.currentTime - started <= ATTRIBUTION_TIMEOUT_MS)
        assertEquals(1, h.sent.size)
        // Nothing to report: no attribution object, but it is not asked again.
        assertNull(h.sent[0].attribution)
        assertTrue(PersistedState.fromJson(h.store.map[STATE_KEY])!!.attributionSent)
    }

    @Test
    fun `queued events survive a restart while offline`() = runTest {
        val h = Harness(testScheduler, statuses = listOf(-1, -1))
        val first = h.make()
        first.start()
        first.track("purchase_tapped", mapOf("plan" to "yearly"), null)
        tick()
        first.flush(true)
        first.shutdown()
        assertEquals(3, h.store.queueSize())

        val second = h.make()
        second.start()
        second.flush(true) // offline again
        second.flush(true) // online
        val delivered = h.sent.last().events.map { it["name"] }
        assertEquals(listOf("session_start", "first_open", "purchase_tapped"), delivered.take(3))
        assertEquals(0, h.store.queueSize())
    }

    @Test
    fun `first_open is recorded once per install`() = runTest {
        val h = Harness(testScheduler)
        repeat(3) {
            val sdk = h.make()
            sdk.start()
            sdk.flush(true)
            sdk.shutdown()
        }
        assertEquals(1, h.allEvents.count { it["name"] == "first_open" })
    }

    @Test
    fun `drops the oldest events past maxQueueSize and sends in batches of 100`() = runTest {
        val h = Harness(testScheduler, statuses = listOf(-1))
        val sdk = h.make(maxQueueSize = 150)
        sdk.start()
        sdk.flush(true) // offline: keep everything queued
        repeat(200) { sdk.track("e$it", null, null) }
        tick()
        assertEquals(150, h.store.queueSize())
        sdk.flush(true)
        val sizes = h.sent.drop(1).map { it.events.size }
        assertEquals(listOf(100, 50), sizes)
        assertEquals("e50", h.sent[1].events[0]["name"]) // session_start, first_open, e0..e49 dropped
    }

    @Test
    fun `flushAt sends without waiting for the timer`() = runTest {
        val h = Harness(testScheduler)
        val sdk = h.make(flushAt = 5)
        sdk.start()
        tick()
        val before = h.sent.size
        repeat(5) { sdk.track("e$it", null, null) }
        tick()
        assertTrue(h.sent.size > before)
    }

    @Test
    fun `drops invalid names and cleans props without losing the batch`() = runTest {
        val h = Harness(testScheduler)
        val sdk = h.make()
        sdk.start()
        sdk.track("has space", null, null)
        sdk.track("", null, null)
        sdk.screen("  ", null)
        sdk.track(
            "ok.event:1-a_b",
            mapOf(
                "k".repeat(80) to "v".repeat(600),
                "n" to 3,
                "d" to 1.5,
                "nan" to Double.NaN,
                "b" to true,
                "nil" to null,
                "obj" to listOf(1, 2),
            ),
            Double.POSITIVE_INFINITY,
        )
        tick()
        sdk.flush(true)
        val names = h.allEvents.map { it["name"] }
        assertFalse(names.contains("has space"))
        val ok = h.allEvents.first { it["name"] == "ok.event:1-a_b" }
        val props = ok["props"] as Map<*, *>
        assertEquals(512, (props["k".repeat(64)] as String).length)
        assertEquals(3L, props["n"])
        assertEquals(1.5, props["d"])
        assertTrue(props.containsKey("nan") && props["nan"] == null)
        assertEquals(true, props["b"])
        assertEquals("[1, 2]", props["obj"])
        assertFalse("non-finite value is left out", ok.containsKey("value"))
        h.sent.forEach { IngestSchema.validate(it.body) }
    }

    @Test
    fun `every batch matches the ingest schema`() = runTest {
        val longContext = DeviceContext(
            platform = "android",
            appVersion = "1.2.3",
            build = "  ",
            osVersion = "15",
            deviceModel = "M".repeat(200),
            locale = "en-US-u-ca-gregory-nu-latn-x-something-long",
        )
        val h = Harness(testScheduler, referrer = { "utm_source=a&aky_l=x&aky_click=c" }, context = longContext)
        val sdk = h.make(sessionTimeoutMs = 1_000)
        sdk.start()
        sdk.screen("Home", mapOf("tab" to "feed"))
        sdk.track("export", mapOf("format" to "pdf"), 1.0)
        sdk.identify("u".repeat(300))
        sdk.adRevenue(0.5, "admob", "home", "banner")
        sdk.onBackground()
        tick()
        h.advance(2_000)
        sdk.onForeground()
        tick()
        sdk.flush(true)
        assertTrue(h.sent.isNotEmpty())
        h.sent.forEach { IngestSchema.validate(it.body) }
        val context = h.sent.last().context
        assertNull("blank build is left out", context["build"])
        assertEquals(128, (context["deviceModel"] as String).length)
        assertEquals(256, (context["userId"] as String).length)
        assertEquals(setOf("installId", "sdk", "context", "events"), h.sent.last().body.keys)
    }

    @Test
    fun `file store writes atomically and reads back`() {
        val dir = Files.createTempDirectory("appeeky").toFile()
        try {
            val store = FileStore(File(File(dir, "appeeky-analytics"), "queue.json"))
            assertNull(store.read())
            store.write("[1]")
            store.write("[1,2]")
            assertEquals("[1,2]", store.read())
            assertFalse(File(dir, "appeeky-analytics/queue.json.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `json round trip`() {
        val value = mapOf(
            "s" to "quote \" backslash \\ newline \n tab \t unicode ğüş \u0001",
            "l" to 1_700_000_000_000L,
            "d" to 0.012346,
            "b" to false,
            "n" to null,
            "a" to listOf(1L, "x", mapOf("k" to true)),
        )
        assertEquals(value, Json.parse(Json.encode(value)))
        assertNull(Json.parseOrNull("{broken"))
        assertEquals("null", Json.encode(Double.NaN))
    }

    @Test
    fun `query params decode like URLSearchParams`() {
        assertEquals("myapp://offer/42", queryParam("a=1&aky_dl=myapp%3A%2F%2Foffer%2F42", "aky_dl"))
        assertEquals("a b", queryParam("x=a+b", "x"))
        assertNull(queryParam("x=1", "y"))
        assertNull(deferredDeepLinkFrom("utm_source=google-play&utm_medium=organic", null))
    }
}
