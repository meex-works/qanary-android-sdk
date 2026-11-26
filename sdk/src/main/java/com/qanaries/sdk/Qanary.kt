package com.qanaries.sdk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import java.io.File

/**
 * Qanary SDK — goes inside the app being tested.
 *
 *     class MyApp : Application() {
 *         override fun onCreate() { super.onCreate(); Qanary.init(this) }
 *     }
 *
 * DORMANT by default: it records nothing unless the app was opened from the Qanary app with a session token
 * (intent extra [EXTRA_SESSION]). Real users of the developer's app are never recorded.
 *
 * Privacy rules (also enforced server side): no text from input fields, ever — counts only;
 * screen names are Activity class names or routes passed to [screen]; no screenshots in this version.
 */
object Qanary {
    const val EXTRA_SESSION = "com.qanaries.sdk.SESSION"
    const val VERSION = "0.2.1"
    private const val TAG = "Qanary"
    internal const val ENDPOINT_ERROR = "Qanary: the endpoint must be https (http only to 10.0.2.2, localhost or 127.0.0.1); the SDK stays off"

    /**
     * Log what the SDK does to Logcat (tag "Qanary"): a token received from Qanary, sessions started, resumed, replaced
     * and finished, batches sent, refused or retried. Off by default. Session tokens are never logged.
     */
    @JvmStatic var debug: Boolean = false
    internal fun log(msg: String) { if (debug) android.util.Log.d(TAG, msg) }

    @Volatile private var sessions: Sessions? = null
    internal val session: Session? get() = sessions?.current

    /** True while one of the app's activities is in front: heartbeats and freezes only count then. */
    internal val inForeground: Boolean get() = started > 0
    @Volatile private var started = 0
    private var currentScreen: String? = null

    /**
     * Call once, in Application.onCreate. Resumes a session opened from Qanary on an earlier launch; otherwise stays
     * dormant. [endpoint] must be https (plain http only to the emulator host or localhost): any other endpoint logs an
     * error and the SDK stays off, so a wrong endpoint can never crash your app.
     */
    @JvmStatic
    @JvmOverloads
    fun init(application: Application, endpoint: String = "https://api.qanaries.com") {
        if (sessions != null) return log("init() was already called; ignored")
        if (!endpointAllowed(endpoint)) { android.util.Log.e(TAG, ENDPOINT_ERROR); return }
        val store = Store(File(application.noBackupFilesDir, "com.qanaries.sdk"))
        val s = Sessions(
            store,
            newQueue = { st ->
                EventQueue(st.token, EventLog(store.eventsFile(st.token)), SeqCounter(st.seqNext) { upTo -> store.save(st.copy(seqNext = upTo)) },
                    HttpTransport(endpoint), application.packageName, st.since + Sessions.SESSION_TTL_MS)
            },
            device = { DeviceInfo.basic(application) },
            startQueue = { q -> q.start { inForeground } },
            onRecording = { AnrWatchdog.start { session } },
            onStopped = { AnrWatchdog.stop() },
        )
        sessions = s
        // Small files, read once at launch on the main thread so a resumed session is recording before the first activity.
        diskOk {
            runCatching { Legacy.migrate(application, store) }
            s.restore()
        }
        CrashCapture.install(application) { session }
        application.registerActivityLifecycleCallbacks(Callbacks)
    }

    /** For Compose or single-Activity apps: report a route change yourself. */
    @JvmStatic
    fun screen(name: String) {
        val s = session ?: return
        if (name == currentScreen) return
        currentScreen = name
        s.queue.add("screen_view", screen = name)
    }

    /** Optional: mark a test task from inside the app, so the report can prefill it. */
    @JvmStatic
    fun task(taskId: String, state: String) {
        session?.queue?.add("task_marker", data = mapOf("taskId" to taskId, "state" to state))
    }

    /**
     * For Compose (or any custom) text fields: report how a field went when it loses focus. Counts only — never pass
     * the text. `edits` = changes typed, `clears` = times it was emptied after having text.
     */
    @JvmStatic
    @JvmOverloads
    fun field(name: String, edits: Int, clears: Int = 0) {
        val s = session ?: return
        if (edits > 0) s.queue.add("field_edit", screen = currentScreen, data = mapOf("field" to name.take(60), "edits" to edits, "clears" to clears))
    }

    /** A field the tester typed in, then left empty: the form was abandoned at this field. */
    @JvmStatic
    fun fieldAbandoned(name: String) {
        session?.queue?.add("field_abandon", screen = currentScreen, data = mapOf("field" to name.take(60)))
    }

    /** A network request failed (an HTTP status of 500+, or 0 for no response). Only the status is sent — never the URL or body. */
    @JvmStatic
    fun requestFailed(status: Int) {
        session?.queue?.add("error", screen = currentScreen, data = mapOf("kind" to "network", "status" to status))
    }

    val isRecording: Boolean get() = session != null

    // ---------------------------------------------------------------------------------

    /** https, or plain http only to the emulator's view of your computer or localhost (testing against a local API). */
    internal fun endpointAllowed(endpoint: String): Boolean {
        val u = runCatching { java.net.URL(endpoint) }.getOrNull() ?: return false
        if (u.host.isNullOrEmpty()) return false
        return u.protocol == "https" || (u.protocol == "http" && u.host in setOf("10.0.2.2", "localhost", "127.0.0.1"))
    }

    private object Callbacks : Application.ActivityLifecycleCallbacks {
        private fun takeToken(a: Activity) {
            val token = a.intent?.getStringExtra(EXTRA_SESSION) ?: return
            a.intent?.removeExtra(EXTRA_SESSION)
            if (!Sessions.isToken(token)) return log("ignored a session extra that isn't a Qanary token")
            diskOk { sessions?.take(token) }
        }

        override fun onActivityCreated(a: Activity, b: Bundle?) = takeToken(a)

        override fun onActivityStarted(a: Activity) {
            if (started++ == 0) session?.queue?.add("foreground")
        }

        override fun onActivityResumed(a: Activity) {
            takeToken(a)   // the app was already running and Qanary brought it forward with a token
            session ?: return
            screen(a.javaClass.simpleName)
            // The watchers look up the session when they fire: after it ends they record nothing, and a session that
            // replaced it gets their events (a window is wrapped only once).
            RageTapDetector.attach(a.window) { taps, region -> session?.queue?.add("rage_tap", screen = currentScreen, data = mapOf("taps" to taps, "region" to region)) }
            FieldWatcher.attach(a.window.decorView) { type, field, data -> session?.queue?.add(type, screen = currentScreen, data = mapOf("field" to field) + data) }
        }

        override fun onActivityStopped(a: Activity) {
            if (--started == 0) session?.queue?.let { it.add("background"); it.flushNow() }
        }

        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
}

/**
 * Counts edits and clears per EditText field (View-based UIs). Never reads the text itself.
 * Compose text fields: call [Qanary.field] and [Qanary.fieldAbandoned] when a field loses focus (see the docs).
 */
internal object FieldWatcher {
    private val attached = java.util.WeakHashMap<View, Boolean>()

    fun attach(root: View, emit: (type: String, field: String, data: Map<String, Any>) -> Unit) {
        if (attached.put(root, true) != null) return
        var focused: EditText? = null
        var edits = 0
        var clears = 0
        val watcher = object : android.text.TextWatcher {
            var before = 0
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { before = s?.length ?: 0 }
            override fun onTextChanged(s: CharSequence?, start: Int, b: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                edits++
                if (before > 0 && (s?.length ?: 0) == 0) clears++ // only lengths are compared, never content
            }
        }
        root.viewTreeObserver.addOnGlobalFocusChangeListener(ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
            (old as? EditText)?.let { f ->
                f.removeTextChangedListener(watcher)
                val name = fieldName(f)
                if (edits > 0) emit("field_edit", name, mapOf("edits" to edits, "clears" to clears))
                if (f.length() == 0 && edits > 0) emit("field_abandon", name, emptyMap())
            }
            focused = new as? EditText
            focused?.let { f ->
                edits = 0; clears = 0
                f.addTextChangedListener(watcher)
                emit("field_focus", fieldName(f), emptyMap())
            }
        })
    }

    private fun fieldName(v: View): String =
        if (v.id != View.NO_ID) runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault("field_${v.id}") else "unnamed_field"
}
