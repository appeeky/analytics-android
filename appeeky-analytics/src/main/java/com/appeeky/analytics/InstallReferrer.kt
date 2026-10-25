package com.appeeky.analytics

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The Play Install Referrer string ("utm_source=…&aky_c=…&aky_l=…&aky_click=…"),
 * or null when Play is missing or has none. Only called on the first launch.
 */
internal suspend fun readInstallReferrer(context: Context): String? = suspendCancellableCoroutine { cont ->
    val client = try {
        InstallReferrerClient.newBuilder(context).build()
    } catch (e: Exception) {
        cont.resume(null)
        return@suspendCancellableCoroutine
    }
    var settled = false
    fun finish(value: String?) {
        if (settled) return
        settled = true
        runCatching { client.endConnection() }
        if (cont.isActive) cont.resume(value)
    }
    cont.invokeOnCancellation { runCatching { client.endConnection() } }
    try {
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                val referrer = if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                    runCatching { client.installReferrer.installReferrer }.getOrNull()
                } else {
                    null
                }
                finish(referrer)
            }

            override fun onInstallReferrerServiceDisconnected() = finish(null)
        })
    } catch (e: Exception) {
        finish(null)
    }
}
