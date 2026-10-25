package com.appeeky.analytics

import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * The rules of packages/core/src/ingest-payload.ts (ingestBatchSchema, all
 * objects strict): any batch the SDK sends must pass them, or the server
 * answers 400 and the SDK drops the batch.
 */
internal object IngestSchema {
    private val EVENT_NAME = Regex("^[A-Za-z0-9_.:-]+$")

    fun validate(body: Map<String, Any?>) {
        keys(body, "batch", setOf("installId", "sdk", "context", "attribution", "events"))
        shortText(body["installId"], 64, "installId", required = true)
        shortText(body["sdk"], 64, "sdk")

        (body["context"] as? Map<*, *>)?.let { context ->
            keys(context, "context", setOf("platform", "appVersion", "build", "osVersion", "deviceModel", "locale", "userId"))
            context["platform"]?.let { assertTrue("platform: $it", it == "ios" || it == "android") }
            shortText(context["appVersion"], 64, "context.appVersion")
            shortText(context["build"], 64, "context.build")
            shortText(context["osVersion"], 64, "context.osVersion")
            shortText(context["deviceModel"], 128, "context.deviceModel")
            shortText(context["locale"], 35, "context.locale")
            shortText(context["userId"], 256, "context.userId")
        }

        (body["attribution"] as? Map<*, *>)?.let { attribution ->
            keys(attribution, "attribution", setOf("adServicesToken", "adServices", "installReferrer", "deepLinkUrl"))
            shortText(attribution["installReferrer"], 2_048, "attribution.installReferrer")
            shortText(attribution["deepLinkUrl"], 2_048, "attribution.deepLinkUrl")
        }

        val events = body["events"] as? List<*> ?: emptyList<Any?>()
        assertTrue("at most ${MAX_BATCH} events", events.size <= MAX_BATCH)
        for (raw in events) {
            val event = raw as? Map<*, *> ?: fail("event is not an object").let { return }
            keys(event, "event", setOf("id", "name", "ts", "sessionId", "screen", "props", "value"))
            shortText(event["id"], 64, "event.id", required = true)
            val name = (event["name"] as? String)?.trim()
            assertTrue("event.name: $name", name != null && name.length in 1..64 && EVENT_NAME.matches(name))
            val ts = event["ts"]
            assertTrue("event.ts: $ts", ts is Long && ts > 0)
            shortText(event["sessionId"], 64, "event.sessionId")
            shortText(event["screen"], 128, "event.screen")
            (event["props"] as? Map<*, *>)?.forEach { (k, v) ->
                assertTrue("props.$k: $v", v == null || v is Boolean || v is Long || v is Double || (v is String && v.length <= 512))
            }
            event["value"]?.let { assertTrue("event.value: $it", it is Long || (it is Double && it.isFinite())) }
        }
    }

    private fun keys(obj: Map<*, *>, what: String, allowed: Set<String>) {
        val extra = obj.keys.map { it.toString() }.filterNot { it in allowed }
        assertTrue("$what has unknown keys $extra", extra.isEmpty())
    }

    private fun shortText(value: Any?, max: Int, path: String, required: Boolean = false) {
        if (value == null) {
            if (required) fail("$path is required")
            return
        }
        assertTrue("$path is not a string", value is String)
        val trimmed = (value as String).trim()
        assertTrue("$path length ${trimmed.length} not in 1..$max", trimmed.length in 1..max)
    }
}
