package com.appeeky.analytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Sends real batches through the SDK's HTTP client to a running server.
 * Skipped unless APPEEKY_SMOKE_ENDPOINT and APPEEKY_SMOKE_KEY are set;
 * APPEEKY_SMOKE_REFERRER is the Play referrer to report, and the install id
 * is written to APPEEKY_SMOKE_OUT for the caller to look up.
 */
class IngestSmokeTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sends batches to a live ingest endpoint`() = runBlocking {
        val endpoint = System.getenv("APPEEKY_SMOKE_ENDPOINT")
        val key = System.getenv("APPEEKY_SMOKE_KEY")
        assumeTrue("set APPEEKY_SMOKE_ENDPOINT and APPEEKY_SMOKE_KEY to run", endpoint != null && key != null)
        val referrer = System.getenv("APPEEKY_SMOKE_REFERRER")

        val storage = MemoryStorage()
        val statuses = mutableListOf<Int>()
        val http = HttpTransport()
        val transport = Transport { url, headers, body -> http.post(url, headers, body).also { statuses.add(it.status) } }
        fun deps() = CoreDeps(
            storage = storage,
            transport = transport,
            dispatcher = Dispatchers.IO.limitedParallelism(1),
            now = System::currentTimeMillis,
            randomId = { UUID.randomUUID().toString() },
            deviceContext = {
                DeviceContext(platform = "android", appVersion = "1.0.0", build = "7", osVersion = "15", deviceModel = "Pixel 9", locale = "tr-TR")
            },
            installReferrer = { referrer },
            initialUrl = { null },
            log = { level, message -> println("[$level] $message") },
        )
        // A short session timeout, so the relaunch below starts a second session.
        val options = CoreOptions(key = key!!, endpoint = endpoint!!, sessionTimeoutMs = 1_000, debug = true)

        // First launch: session_start, first_open and the referrer.
        val sdk = AppeekyCore(options, deps())
        sdk.start()
        sdk.flush(true)
        assertEquals("deep link from the referrer", referrer?.let { queryParam(it, "aky_dl") }, sdk.getDeferredDeepLink())
        sdk.screen("Onboarding", null)
        sdk.screen("Paywall", mapOf("variant" to "b"))
        sdk.track("onboarding_completed", mapOf("step" to 3), null)
        sdk.identify("rc_smoke_user")
        delay(1_200)
        sdk.onBackground()
        delay(300)
        sdk.flush(true)
        sdk.shutdown()

        // Relaunch after the session timeout: same install, new session.
        delay(1_500)
        val again = AppeekyCore(options, deps())
        again.start()
        again.track("opened_again", null, null)
        delay(300)
        again.flush(true)
        assertEquals(sdk.installId, again.installId)
        again.shutdown()

        println("statuses $statuses install ${sdk.installId}")
        assertTrue("all batches accepted: $statuses", statuses.isNotEmpty() && statuses.all { it == 202 })
        assertEquals(0, storage.queueSize())
        System.getenv("APPEEKY_SMOKE_OUT")?.let { File(it).writeText(sdk.installId!!) }
        Unit
    }
}
