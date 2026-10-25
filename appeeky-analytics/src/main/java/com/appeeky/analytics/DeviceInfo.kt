package com.appeeky.analytics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

internal fun androidDeviceContext(context: Context): DeviceContext {
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
    )
}
