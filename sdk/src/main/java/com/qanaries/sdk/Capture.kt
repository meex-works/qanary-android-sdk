package com.qanaries.sdk

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Window
import kotlin.math.hypot

/** 3+ taps within 700 ms inside a 48 dp circle = a rage tap. */
internal object RageTapDetector {
    private const val WINDOW_MS = 700L
    private const val MIN_TAPS = 3

    fun attach(window: Window, emit: (taps: Int, region: String) -> Unit) {
        val original = window.callback ?: return
        if (original is Wrapped) return
        val density = window.context.resources.displayMetrics.density
        val radius = 48 * density
        val taps = ArrayDeque<Triple<Long, Float, Float>>()
        var reported = 0L
        window.callback = object : WindowCallbackWrapper(original), Wrapped {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val now = SystemClock.uptimeMillis()
                    taps.addLast(Triple(now, event.rawX, event.rawY))
                    while (taps.isNotEmpty() && now - taps.first().first > WINDOW_MS) taps.removeFirst()
                    val near = taps.count { hypot(it.second - event.rawX, it.third - event.rawY) < radius }
                    if (near >= MIN_TAPS && now - reported > WINDOW_MS) {
                        reported = now
                        val h = window.decorView.height.coerceAtLeast(1)
                        val region = when { event.rawY < h / 3f -> "top"; event.rawY < 2 * h / 3f -> "middle"; else -> "bottom" }
                        emit(near, region)
                        taps.clear()
                    }
                }
                return super.dispatchTouchEvent(event)
            }
        }
    }

    private interface Wrapped
}

/** Crashes: written to the session's file at once (every event is), and sent on the next launch. */
internal object CrashCapture {
    fun install(app: Application, session: () -> Session?) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                session()?.queue?.add("error", data = mapOf("kind" to "crash", "signature" to signature(error)))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Exception type + top frame. No messages: they can contain user data. */
    fun signature(t: Throwable): String {
        val frame = t.stackTrace.firstOrNull()
        return "${t.javaClass.name}@${frame?.className}.${frame?.methodName}:${frame?.lineNumber}".take(200)
    }
}

/**
 * A frozen main thread: pinged every second from a background thread; no answer for 5 s while the app is in front is
 * reported ONCE per freeze as `error {kind: "anr"}` (the struggle score counts each error event, so a long freeze isn't
 * counted again every 5 s). The same rule as the iPhone SDK.
 */
internal object AnrWatchdog {
    private val lock = Any()
    private var generation = 0
    private var running = false

    fun stop() = synchronized(lock) { running = false }

    fun start(session: () -> Session?) {
        val gen = synchronized(lock) {
            if (running) return
            running = true
            ++generation
        }
        val alive = { synchronized(lock) { running && generation == gen } && session() != null }
        val main = Handler(Looper.getMainLooper())
        Thread({
            val d = FreezeDetector()
            var ping: java.util.concurrent.atomic.AtomicBoolean? = null
            while (alive()) {
                val before = SystemClock.uptimeMillis()
                if (ping == null) {
                    val p = java.util.concurrent.atomic.AtomicBoolean(false)
                    ping = p
                    d.pinged(before)
                    main.post { p.set(true) }
                }
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
                val now = SystemClock.uptimeMillis()
                if (now - before > 3000) d.paused(now)   // this thread was frozen too (the app was cached): not a freeze
                if (ping.get()) { ping = null; d.answered(); continue }
                val blocked = d.check(now, Qanary.inForeground) ?: continue
                val top = Looper.getMainLooper().thread.stackTrace.firstOrNull()
                session()?.queue?.add("error", data = mapOf("kind" to "anr", "signature" to "main@${top?.className}.${top?.methodName}", "blockedMs" to blocked))
            }
            synchronized(lock) { if (generation == gen) running = false }   // ended by itself (no session): start() can run it again
        }, "qanary-anr").apply { isDaemon = true }.start()
    }
}

/** When a freeze is reported: once it has lasted [thresholdMs] with the app in front, and once per freeze. */
internal class FreezeDetector(private val thresholdMs: Long = 5000) {
    private var since = -1L
    private var reported = false

    /** A ping went to the main thread at [at]. */
    fun pinged(at: Long) { since = at }

    /** The main thread answered: the next freeze is a new one. */
    fun answered() { since = -1; reported = false }

    /** The watching thread itself was paused: count from now. */
    fun paused(now: Long) { if (since >= 0) since = now }

    /** How long the main thread has been blocked, when this is the moment to report it; else null. */
    fun check(now: Long, inFront: Boolean): Long? {
        if (since < 0 || reported || !inFront) return null
        val blocked = now - since
        if (blocked < thresholdMs) return null
        reported = true
        return blocked
    }
}

internal object DeviceInfo {
    fun basic(ctx: Context): Map<String, Any> = mapOf(
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "sdkInt" to Build.VERSION.SDK_INT,
        "appVersion" to runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" }.getOrDefault("?"),
        "sdkVersion" to Qanary.VERSION,
    )
}
