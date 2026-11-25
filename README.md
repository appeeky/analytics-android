# Appeeky Analytics for Android

Native Kotlin SDK for Appeeky Analytics. It records installs, sessions,
screens and your own events, and links each install to the RevenueCat or
Superwall customer, so your Appeeky Analytics server can show revenue by
install source: a smart link, a campaign link, the Google Play referrer, or
organic store traffic.

It behaves like the React Native / Expo SDK (`@appeeky/analytics-expo`): same ingest
payload, same automatic events, same queue, retry and session rules.

- No advertising id. The install id is a random UUID stored in the app.
- Events are queued on the device and sent in batches. They survive offline
  periods and app restarts.
- Reads the Google Play Install Referrer on the first launch, so installs from
  a smart link are attributed exactly and can open a deferred deep link.
- Kotlin coroutines, `HttpURLConnection` and the AndroidX process lifecycle.
  No HTTP or JSON library.

Requires Android 6.0 (API 23) or later.

## Install

The SDK is published through JitPack. Add the repository and the dependency:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.appeeky:analytics-android:0.3.0")
}
```

It brings its own dependencies: `kotlinx-coroutines-android`,
`androidx.lifecycle:lifecycle-process` and the Play Install Referrer library.

The SDK's manifest adds the `INTERNET` permission.

## Set up

Get the app's publishable key (`apk_…`): in Appeeky, turn on Analytics for
the app; on a self-hosted server, create the app with
`POST /v1/apps` and `"platform": "google"` (see the
[self-hosting guide](https://docs.appeeky.com/docs/analytics-self-hosting)). The key ships inside your app, so it
can only write events for that app.

Configure the SDK once, in your `Application`:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Appeeky.configure(this, "apk_...")
        // Self-hosted:
        // Appeeky.configure(this, "apk_...", endpoint = "https://analytics.example.com/v1/ingest")
    }
}
```

| Parameter | Default | |
| --- | --- | --- |
| `key` | required | Publishable key (`apk_…`) |
| `endpoint` | Appeeky hosted | A self-hosted server's ingest URL, ending in `/v1/ingest` |
| `debug` | `false` | Log rejected batches, dropped events and network errors to Logcat (tag `AppeekyAnalytics`) |
| `options.flushIntervalMs` | `10000` | How often queued events are sent while the app is open |
| `options.flushAt` | `20` | Send as soon as this many events are queued |
| `options.maxQueueSize` | `1000` | Oldest events are dropped beyond this |
| `options.sessionTimeoutMs` | `1800000` | Time in the background before a new session starts |
| `options.disabled` | `false` | Collect and send nothing, for example after an opt-out |
| `options.testDevice` | `null` | `true` or `false` marks the install as a test or a real one; `null` detects it (see [Test installs](#test-installs)) |

```kotlin
Appeeky.configure(
    this,
    "apk_...",
    debug = BuildConfig.DEBUG,
    options = AppeekyOptions(sessionTimeoutMs = 15 * 60 * 1000L),
)
```

## Identify the user

Link the install to the customer id your purchases are recorded under, so
each sale gets the install's source.

RevenueCat:

```kotlin
Purchases.configure(PurchasesConfiguration.Builder(this, "goog_...").build())
Appeeky.identify(Purchases.sharedInstance.appUserID)

// After Purchases.sharedInstance.logIn(id) succeeds:
Appeeky.identify(id)
// On logout:
Appeeky.identify(null)
```

`identify` can be called on every launch: an id that has not changed sends
nothing.

Superwall: pass the same user id you give `Superwall.instance.identify(userId)`;
Superwall's webhook events are matched to the install by it.

```kotlin
Superwall.instance.identify(userId)
Appeeky.identify(userId)
```

## Screens and events

```kotlin
Appeeky.screen("Paywall")
Appeeky.screen("Paywall", mapOf("variant" to "b"))
Appeeky.track("onboarding_completed", mapOf("step" to 3))
Appeeky.track("export", mapOf("format" to "pdf"), value = 1.0)
```

Event names may use letters, digits, `_`, `.`, `:` and `-`, up to 64
characters; an event with another name is dropped on the device (logged with
`debug = true`). Property keys are cut to 64 characters. Values are strings
(cut to 512 characters), numbers, booleans or `null`; anything else is sent as
its `toString()`.

With Jetpack Navigation:

```kotlin
navController.addOnDestinationChangedListener { _, destination, _ ->
    destination.route?.let { Appeeky.screen(it) }
}
```

Ad revenue from a paid impression, in USD:

```kotlin
// AdMob
rewardedAd.onPaidEventListener = OnPaidEventListener { value ->
    if (value.currencyCode == "USD") {
        Appeeky.adRevenue(value.valueMicros / 1_000_000.0, network = "admob", adUnit = "rewarded_home", format = "rewarded")
    }
}
```

`flush()` sends the queue now. It is a suspend function; the SDK also flushes
every 10 seconds, when 20 events are queued, and when the app goes to the
background.

```kotlin
lifecycleScope.launch { Appeeky.flush() }
```

`Appeeky.installId` is the install's random id (null until the SDK has
loaded its state).

### What is collected automatically

| Event | When |
| --- | --- |
| `first_open` | First launch after install |
| `session_start` | Launch, or returning after `sessionTimeoutMs` in the background |
| `app_background` | Going to the background, with `foreground_ms`: time on screen since the app came to the foreground. Session time is the sum per session. |
| `identify` / `reset` | `identify(id)` with a new id / `identify(null)` after one was set |
| `ad_revenue` | `adRevenue(valueUsd, …)` |

Foreground and background come from `ProcessLifecycleOwner` (the whole app,
not single Activities). Going to the background also sends the queue.

## Test installs

A debuggable build of your app and any build running on an emulator are
reported as test installs. The server stores them and keeps them out of your
reports; the dashboard's test data view shows them. To decide yourself, pass
`options = AppeekyOptions(testDevice = true)` (or `false`, for example to
count a debuggable QA build as a real install).

## Smart links and deep links

Create a smart link for a campaign in Appeeky (or `POST /v1/apps/:id/links`
on your server, with an `androidUrl` pointing at your Play listing and an
optional in-app `deepLink`). On Android the link sends the visitor to Google
Play with the campaign, the link and the click in the install referrer.

On the first launch after install, `getDeferredDeepLink()` returns the
link's in-app destination once:

```kotlin
lifecycleScope.launch {
    Appeeky.getDeferredDeepLink()?.let { destination ->
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
    }
}

// Or with a callback, on the main thread:
Appeeky.getDeferredDeepLink { destination -> /* … */ }
```

It returns null on later launches, or when the install did not come from a
link with a destination.

### The launch Intent

On the first launch the SDK also sends the URL the app was opened with, so
campaign links (`?aky_c=spring` or `utm_*` parameters) are attributed. When
`configure()` runs in `Application.onCreate()`, the SDK reads the first
Activity's `ACTION_VIEW` Intent itself. If you configure later, or want to be
explicit, pass the Intent from your launcher Activity:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Appeeky.handleDeepLink(intent)
}
```

`handleDeepLink(uri: Uri)` takes a `Uri` instead. Only the URL of the first
launch is attribution input; later links are yours to route.

## Install referrer

On the first launch the SDK reads the Play Install Referrer with
`com.android.installreferrer:installreferrer` and sends the raw string once,
with the first batch. The server reads from it:

- `utm_source`, `utm_medium`, `utm_campaign`, `utm_term`, `utm_content`;
- `aky_c` (campaign), `aky_l` (smart link), `aky_click` (the click, with any
  ad network click id it carried) and `aky_dl` (in-app destination).

A referrer with `aky_l` is attributed to that smart link; `utm_*` without it
counts as a Play referrer campaign; `utm_source=google-play&utm_medium=organic`
is organic. The referrer and the launch URL each get 3 seconds; batches wait
for them on the first launch only. Once a batch with them is accepted, they
are not read again. Installs from outside Google Play have no referrer.

## Delivery

- Up to 100 events per request, `POST` to the endpoint with the
  `X-Appeeky-Key` header.
- No network, HTTP 429 or 5xx: the batch stays queued and is retried with
  backoff (5 s, doubling, up to 5 minutes). Event ids stay the same, so a
  retry is not counted twice.
- Other 4xx: the server will never accept the batch, so it is dropped.
- The install id, session state and queue are files in the app's no-backup
  directory (`no_backup/appeeky-analytics/`). Auto Backup never copies them,
  so a restored or new device counts as a new install, and they are removed
  with the app's data.

## R8 / ProGuard

The SDK ships consumer rules (`consumer-rules.pro`). It uses no reflection,
so nothing needs to be added to your app's rules.

## Privacy

The SDK sends the install id, app version and build, Android version, device
model, locale, whether the install is a test install, the install referrer
and launch URL of the first launch, and the events you track. It never reads the advertising id, Android id or other
device identifiers. In the Play Console's Data safety form, declare **App
activity → App interactions** (and **Financial info → Purchase history** if
you use RevenueCat or Superwall with Appeeky) as collected, not shared, and not used for
advertising. If the user id you pass to `identify` is a real account id,
declare it as linked to the user. Use `options = AppeekyOptions(disabled = true)`
to collect nothing for users who opt out.

## Development

```sh
./gradlew :appeeky-analytics:testDebugUnitTest :appeeky-analytics:assembleRelease
```

The unit tests run on the JVM with fakes for storage, network, clock and
lifecycle. `IngestSmokeTest` sends real batches through the SDK's HTTP client
when `APPEEKY_SMOKE_ENDPOINT` (for example a self-hosted server's
`/v1/ingest`) and `APPEEKY_SMOKE_KEY` are set;
`APPEEKY_SMOKE_REFERRER` is the Play referrer it reports, and
`APPEEKY_SMOKE_OUT` a file it writes the install id to.
