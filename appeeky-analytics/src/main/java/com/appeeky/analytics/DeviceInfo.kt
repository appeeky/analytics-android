package com.appeeky.analytics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

/** [testDevice] null: detect a debuggable host app or an emulator. */
internal fun androidDeviceContext(context: Context, testDevice: Boolean? = null): DeviceContext {
    var appVersion: String? = null
    var build: String? = null
    try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        appVersion = info.versionName
        build = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toString()
        }
    } catch (_: Exception) {
        // Leave the app version out rather than fail.
    }
    return DeviceContext(
        platform = "android",
        appVersion = appVersion,
        build = build,
        osVersion = Build.VERSION.RELEASE,
        deviceModel = Build.MODEL,
        locale = Locale.getDefault().toLanguageTag(),
        testDevice = testDevice ?: (isDebuggable(context) || isEmulator()),
    )
}

/**
 * The host app's own debuggable flag. The library's BuildConfig.DEBUG says
 * nothing about the app: the library is always consumed as a release build.
 */
private fun isDebuggable(context: Context): Boolean = try {
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
} catch (_: Exception) {
    false
}

/** The Android emulator, by the names its system images report. */
internal fun isEmulator(
    model: String? = Build.MODEL,
    product: String? = Build.PRODUCT,
    hardware: String? = Build.HARDWARE,
    fingerprint: String? = Build.FINGERPRINT,
): Boolean {
    val m = model.orEmpty()
    val p = product.orEmpty()
    val h = hardware.orEmpty()
    val f = fingerprint.orEmpty()
    return m.startsWith("sdk_gphone") || m.contains("Android SDK built for") || m.contains("Emulator") ||
        p.startsWith("sdk_gphone") || p == "sdk" || p == "google_sdk" || p.startsWith("sdk_") ||
        h == "goldfish" || h == "ranchu" ||
        f.startsWith("generic") || f.contains("/sdk_gphone")
}
