# Qanary SDK for Android

The SDK that goes inside an app being tested on [Qanary](https://qanaries.com), the marketplace for paid app testing.
It measures how testers use your app during a Qanary test, which is what they're paid for and what your struggle
ranking is built from. For everyone else it does nothing: it records only after your app was opened from the Qanary
app for a test.

Kotlin, Android 5.0 (API 21) and later, no dependencies beyond the Kotlin standard library. The iPhone SDK is at
[meex-works/qanary-ios-sdk](https://github.com/meex-works/qanary-ios-sdk). The full guide is at
[qanaries.com/docs](https://qanaries.com/docs/developer/).

## 1. Install

The SDK is published to our own Maven repository, not to Maven Central. In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://qanaries.com/maven") {
            content { includeGroup("com.qanaries") }
        }
    }
}
```

The `content` filter means Gradle asks our repository for `com.qanaries` artifacts only. Then in your app module's
`build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.qanaries:sdk:0.2.1")
}
```

Use `implementation`, not `debugImplementation`: testers install your release build from Google Play, so the SDK has
to be in it.

## 2. Start it

Call `Qanary.init` in your `Application` class's `onCreate`:

```kotlin
import android.app.Application
import com.qanaries.sdk.Qanary

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Qanary.init(this)
    }
}
```

In Java it's the same call: `Qanary.init(this);`. If you don't have an `Application` class yet, add one and name it in
`AndroidManifest.xml` with `android:name=".MyApp"` on the `<application>` element.

Call it once, on the main thread, in every build type. Leave the endpoint out: it defaults to
`https://api.qanaries.com`, and the second parameter exists only for testing against a local copy of our API. A plain
`http` endpoint for any host other than `10.0.2.2`, `localhost` or `127.0.0.1` leaves the SDK off.

Qanary opens your launcher activity with the session token as the intent extra `com.qanaries.sdk.SESSION`. There's
nothing to add to your manifest for that.

## 3. Screens, fields and errors

- **Screens.** Activities are named automatically by their class. Compose, fragments and other single-activity apps
  name their screens: call `Qanary.screen("Checkout")` whenever the visible screen changes. With Navigation Compose:

  ```kotlin
  @Composable
  fun QanaryScreens(navController: NavHostController) {
      val lifecycle = LocalLifecycleOwner.current.lifecycle
      LaunchedEffect(navController, lifecycle) {
          lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
              navController.currentBackStackEntryFlow.collect { entry ->
                  Qanary.screen(entry.destination.route ?: "unknown")
              }
          }
      }
  }
  ```

- **Text fields.** `EditText` fields (including Material's `TextInputEditText`) are counted automatically: how many
  times each was edited, cleared or abandoned. Never the text. A field is named by its view ID, so `@+id/email` is
  `email`; give your fields IDs. Compose text fields aren't `EditText`s, so report each one when it loses focus:

  ```kotlin
  Qanary.field("promo_code", edits = edits, clears = clears)
  Qanary.fieldAbandoned("promo_code")   // typed in, then left empty
  ```

- **Crashes and freezes** (the main thread blocked for 5 seconds while your app is in front) are recorded
  automatically. A crash still goes on to your own crash reporter.
- **Failed requests.** Report the status only, never the URL or the body. With OkHttp, one interceptor covers every
  call:

  ```kotlin
  val client = OkHttpClient.Builder()
      .addInterceptor { chain ->
          try {
              chain.proceed(chain.request()).also { if (it.code >= 500) Qanary.requestFailed(it.code) }
          } catch (e: IOException) {
              Qanary.requestFailed(0)   // no response at all
              throw e
          }
      }
      .build()
  ```

- **Tasks (optional).** Mark a test task from inside your app with `Qanary.task("task_1", "done")`. Task ids are
  `task_1`, `task_2`, … in the order your tasks appear in your report form in the console.

## 4. Verify your build

A test opens only once the SDK has reported from your build, because testers are paid for the use it measures. In
the [console](https://qanaries.com/console), go to **Your apps**, choose **Verify the SDK**, then **Copy command**.
Install your build on a phone or emulator, run the `adb` command in a terminal, and use the app for a few seconds.
The check turns ✓ and lists the screens it saw. It uses no paid slot and expires after a day. Without a computer,
the Qanary app on the phone can open your build for the check from **My apps**.

The check counts only for a build of your app: the SDK (0.2.1 and later) sends your application ID with every
batch, and we compare it with the package name on your card. A debug build with an `applicationIdSuffix` is another
package, so it doesn't verify. A build of another app, or an SDK older than 0.2.1, reports but doesn't verify; the
console says why.

To watch it work, set `Qanary.debug = true` before `init` (`Qanary.setDebug(true)` in Java) and run
`adb logcat -s Qanary`. Session tokens are never logged.

## What it records

- **When:** only after your app was opened from the Qanary app for a test (or by your own verify command). It stops
  when that tester's work is done: their report is in, the test closes, or their claim expires; or after 15 days at
  most. Your other users are never recorded. `Qanary.isRecording` tells you whether a session is recording.
- **What:** screens viewed and time on each, rage taps (fast repeated taps in one spot), how many times each field was
  edited, cleared or abandoned (counts only), failed request status codes you report, crashes (the exception class
  and the top stack frame) and freezes, app in front or behind, the device model, Android version and your app's
  version.
- **Never:** text typed into fields, exception messages, request URLs or bodies, screenshots, contacts, location, the
  Android ID, the advertising ID or any other identifier, or anything outside your app. Qanary's server refuses event
  data that tries to carry field contents.

It sends to `https://api.qanaries.com` only, over HTTPS. During a session it keeps a small state file and one events
file in your app's no-backup folder, so a crash or a kill loses nothing and a session never travels to another phone
in a backup. It adds one permission, `INTERNET`, and no activities, services, receivers or providers.

## Google Play Data safety

In Play Console, **Policy and programs** → **App content** → **Data safety**, declare the SDK's data like this:

| Category | Data type | What the SDK sends |
| --- | --- | --- |
| App activity | App interactions | Screens and time on each, rage taps, text-field counts, task markers, app in front or behind |
| App info and performance | Crash logs | Crash signatures: the exception class and the top stack frame |
| App info and performance | Diagnostics | Freezes, failed-request status codes, the device model, Android version and your app's version |

For each: collected, not shared (we process it for you as a service provider); encrypted in transit; purposes
Analytics and App functionality; not processed ephemerally (we keep it 180 days, then delete it). The SDK records only
people who chose to join a Qanary test and opened your app from Qanary. A sentence you can adapt for your privacy
policy:

> When you join a paid test through Qanary (qanaries.com), the Qanary SDK in this app records how you use it during
> that test: screens viewed, taps, how many times fields were edited (never what you typed), crashes and freezes. It
> sends this to Qanary so we can see where the app is hard to use. It records nothing unless you opened the app from
> the Qanary app for a test.

This is guidance, not legal advice; your own counsel decides what your disclosures say.

## Building from source

```sh
./gradlew :sdk:testDebugUnitTest                           # the unit tests
./gradlew :sdk:publishReleasePublicationToLocalRepository  # the AAR, in sdk/build/repo
```

Built with this repository's Gradle files, the classes in the AAR match the ones served from
`https://qanaries.com/maven` byte for byte, so what you read here is what your app ships.

## Requirements

- Android 5.0 (API 21) or later. Any compile SDK from 21 up.
- Kotlin 1.9 or newer, or plain Java. Its only dependency is `kotlin-stdlib` 1.9.0; a newer one in your app wins.
- About 86 KB as an AAR before R8, with its own keep rule. No other libraries, so it can't clash with yours.

Questions: [hello@qanaries.com](mailto:hello@qanaries.com). License: free to use in apps tested with Qanary; see
[LICENSE](LICENSE).
