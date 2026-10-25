package com.appeeky.analytics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/** Tuning options; the defaults suit most apps. */
public data class AppeekyOptions @JvmOverloads constructor(
    /** Flush at least this often while the app is open. */
    public val flushIntervalMs: Long = 10_000,
    /** Flush as soon as this many events are queued. */
    public val flushAt: Int = 20,
    /** Oldest events are dropped past this many queued events. */
    public val maxQueueSize: Int = 1_000,
    /** A new session starts after this much time in the background. */
    public val sessionTimeoutMs: Long = 30 * 60 * 1000L,
    /** Collect nothing and send nothing (e.g. the user opted out). */
    public val disabled: Boolean = false,
)

/**
 * Appeeky Analytics for Android.
 *
 * ```
 * class App : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         Appeeky.configure(this, "apk_...")
 *     }
 * }
 *
 * Appeeky.screen("Paywall")
 * Appeeky.track("onboarding_completed", mapOf("step" to 3))
 * Appeeky.identify(Purchases.sharedInstance.appUserID)
 * ```
 */
public object Appeeky {
    /** Appeeky's hosted ingest endpoint; self-hosted servers pass their own. */
    public const val DEFAULT_ENDPOINT: String = "https://ingest.appeeky.com/v1/ingest"
    public const val SDK_VERSION: String = BuildConfig.SDK_VERSION

    private const val TAG = "AppeekyAnalytics"

    @Volatile private var client: AppeekyCore? = null
    private val lock = Any()
    /** The URL of the launch Intent, from [handleDeepLink] or the first Activity. */
    private val launchUrl = CompletableDeferred<String?>()
    private val main = Handler(Looper.getMainLooper())
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lifecycleObserver: DefaultLifecycleObserver? = null
    private var activityCallbacks: Application.ActivityLifecycleCallbacks? = null

    /**
     * Start the SDK. Call once, in `Application.onCreate()`. Later calls are
     * ignored until [shutdown].
     *
     * @param key the app's publishable key (`apk_…`).
     * @param endpoint a self-hosted server's ingest URL, ending in `/v1/ingest`.
     * @param debug log rejected batches and network errors to Logcat.
     */
    @JvmStatic
    @JvmOverloads
    @OptIn(ExperimentalCoroutinesApi::class)
    public fun configure(
        context: Context,
        key: String,
        endpoint: String = DEFAULT_ENDPOINT,
        debug: Boolean = false,
        options: AppeekyOptions = AppeekyOptions(),
    ) {
        val app = context.applicationContext ?: context
        val core: AppeekyCore
        synchronized(lock) {
            if (client != null) return
            core = AppeekyCore(
                CoreOptions(
                    key = key,
                    endpoint = endpoint,
                    flushIntervalMs = options.flushIntervalMs,
                    flushAt = options.flushAt,
                    maxQueueSize = options.maxQueueSize,
                    sessionTimeoutMs = options.sessionTimeoutMs,
                    disabled = options.disabled,
                    debug = debug,
                ),
                CoreDeps(
                    storage = AndroidStorage(app),
                    transport = HttpTransport(),
                    dispatcher = Dispatchers.IO.limitedParallelism(1),
                    now = System::currentTimeMillis,
                    randomId = { UUID.randomUUID().toString() },
                    deviceContext = { androidDeviceContext(app) },
                    installReferrer = { readInstallReferrer(app) },
                    initialUrl = { launchUrl.await() },
                    log = { level, message ->
                        if (level == "error") Log.e(TAG, message) else Log.w(TAG, message)
                    },
                ),
            )
            client = core
        }
        if (options.disabled) return
        watchLaunchActivity(app)
        onMain { watchLifecycle(core) }
        callbackScope.launch(Dispatchers.Default) { core.start() }
    }

    /** Record a screen view. */
    @JvmStatic
    @JvmOverloads
    public fun screen(name: String, properties: Map<String, Any?>? = null) {
        requireClient("screen")?.screen(name, properties)
    }

    /**
     * Record an event. Names may use letters, digits, `_ . : -`, up to 64
     * characters. Property values are strings (up to 512 characters),
     * numbers, booleans or null. [value] is an optional number for the event.
     */
    @JvmStatic
    @JvmOverloads
    public fun track(name: String, properties: Map<String, Any?>? = null, value: Double? = null) {
        requireClient("track")?.track(name, properties, value)
    }

    /**
     * Link this install to your RevenueCat app user id (or the id you give
     * Superwall) so purchases get a source. Pass null on logout.
     */
    @JvmStatic
    public fun identify(userId: String?) {
        requireClient("identify")?.identify(userId)
    }

    /** Ad revenue in USD from one paid impression (e.g. AdMob's paid event). */
    @JvmStatic
    @JvmOverloads
    public fun adRevenue(valueUsd: Double, network: String? = null, adUnit: String? = null, format: String? = null) {
        requireClient("adRevenue")?.adRevenue(valueUsd, network, adUnit, format)
    }

    /** Send everything queued now. Returns when the queue is empty or a send failed. */
    public suspend fun flush() {
        requireClient("flush")?.flush(force = true)
    }

    /** The random id of this install; null before [configure] or while it loads. */
    @JvmStatic
    public val installId: String?
        get() = client?.installId

    /**
     * The in-app destination of the smart link that brought this install
     * (from the Play Install Referrer), or of the smart link that opened the
     * app on its first launch. Returns it once, on the first launch; null
     * otherwise.
     */
    public suspend fun getDeferredDeepLink(): String? =
        requireClient("getDeferredDeepLink")?.getDeferredDeepLink()

    /** Callback form of [getDeferredDeepLink]; [callback] runs on the main thread. */
    @JvmStatic
    public fun getDeferredDeepLink(callback: (String?) -> Unit) {
        val core = requireClient("getDeferredDeepLink")
        if (core == null) {
            onMain { callback(null) }
            return
        }
        callbackScope.launch {
            val link = try {
                core.getDeferredDeepLink()
            } catch (e: Exception) {
                null
            }
            callback(link)
        }
    }

    /**
     * Hand the SDK the Intent that opened the app, from your launcher
     * Activity's `onCreate`. On the first launch its URL is sent as
     * attribution (campaign links, smart links). The SDK also reads the first
     * Activity's Intent by itself when [configure] runs in
     * `Application.onCreate`, so this matters when configure runs later.
     */
    @JvmStatic
    public fun handleDeepLink(intent: Intent?) {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        if (data != null) handleDeepLink(data)
    }

    /** The [Uri] that opened the app; see [handleDeepLink]. */
    @JvmStatic
    public fun handleDeepLink(uri: Uri) {
        launchUrl.complete(uri.toString())
    }

    /** Stop timers and lifecycle listeners (tests, or switching keys). */
    @JvmStatic
    public fun shutdown() {
        val core = synchronized(lock) {
            client.also { client = null }
        } ?: return
        core.shutdown()
        onMain {
            lifecycleObserver?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
            lifecycleObserver = null
        }
    }

    // ─── internals ────────────────────────────────────────────────────────────

    private fun requireClient(method: String): AppeekyCore? {
        val core = client
        if (core == null) Log.w(TAG, "$method() called before configure(); ignored.")
        return core
    }

    private fun watchLifecycle(core: AppeekyCore) {
        lifecycleObserver?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
        // The process lifecycle replays ON_START when it is already started;
        // a foreground call then is harmless (the session is already open).
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = core.onForeground()
            override fun onStop(owner: LifecycleOwner) = core.onBackground()
        }
        lifecycleObserver = observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }

    /** The first Activity created after configure() carries the launch Intent. */
    private fun watchLaunchActivity(context: Context) {
        val application = context as? Application ?: return
        if (launchUrl.isCompleted || activityCallbacks != null) return
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                application.unregisterActivityLifecycleCallbacks(this)
                activityCallbacks = null
                val data = activity.intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
                launchUrl.complete(data?.toString())
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        activityCallbacks = callbacks
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
