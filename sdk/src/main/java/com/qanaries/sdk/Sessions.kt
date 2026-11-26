package com.qanaries.sdk

/** A session from Qanary: its token and its events. */
internal class Session(val token: String, val queue: EventQueue)

/**
 * Which session is recording, if any. A session starts when a token arrives from Qanary, carries on across launches,
 * and ends for good when the server says so, after 15 days, or when a new token replaces it (the old one sends what it
 * has first). No Android types, so it runs in plain JVM tests.
 */
internal class Sessions(
    private val store: Store,
    private val newQueue: (Store.State) -> EventQueue,
    private val device: () -> Map<String, Any?>,
    private val startQueue: (EventQueue) -> Unit,
    private val onRecording: () -> Unit = {},
    private val onStopped: () -> Unit = {},
    private val wall: () -> Long = { System.currentTimeMillis() },
    private val say: (String) -> Unit = Qanary::log,
) {
    private val lock = Any()
    @Volatile var current: Session? = null
        private set

    /** At launch: carry on the session an earlier launch saved, unless it's over 15 days old. */
    fun restore() = synchronized(lock) {
        val saved = store.load()
        when {
            saved == null -> store.removeEventFiles(except = null)
            wall() - saved.since > SESSION_TTL_MS -> {
                say("session is over 15 days old; recording stopped")
                store.clearState(ifToken = saved.token)
                store.removeEventFiles(except = null)
            }
            else -> {
                store.removeEventFiles(except = store.eventsFile(saved.token))
                begin(saved, fresh = false)
            }
        }
    }

    /** A token arrived in an intent from Qanary. */
    fun take(token: String) = synchronized(lock) {
        say("token received from Qanary (${if (token.startsWith("qc_")) "check" else "tester"} session)")
        val cur = current
        if (cur?.token == token) return@synchronized say("same session token again; still recording")
        if (cur != null) {
            // A new test (or a check) replaced the old one: the old queue sends what it has, then stops. (No moment
            // without a session in between: the freeze watchdog keeps running for the new one.)
            say("a new session replaced the previous one")
            cur.queue.finish()
        }
        begin(store.load()?.takeIf { it.token == token } ?: Store.State(token, wall(), 0), fresh = true)
    }

    private fun begin(state: Store.State, fresh: Boolean) {
        store.save(state)
        val q = newQueue(state)
        q.onFinished = { why -> end(state.token, why) }
        current = Session(state.token, q)
        // Every launch begins with session_start: the clock restarts at 0 in each process, and the server times launches separately.
        q.add("session_start", data = device() + ("resumed" to !fresh))
        startQueue(q)
        onRecording()
        say(if (fresh) "session started from Qanary" else "session resumed")
    }

    /** The session is over for good: nothing of it stays on the device. */
    fun end(token: String, why: String) = synchronized(lock) {
        val s = current ?: return@synchronized
        if (s.token != token) return@synchronized
        current = null
        s.queue.shutdown(deleteFile = true)
        store.clearState(ifToken = token)
        onStopped()
        say("session finished ($why); recording stopped")
    }

    companion object {
        /** Covers the longest test (the 14-day closed test); a session older than this stops on the device. */
        const val SESSION_TTL_MS = 15L * 24 * 3600 * 1000

        /** `qs_` (a tester's session) or `qc_` (a developer's check) and then base64url: 16 to 128 characters (the API's bounds). */
        fun isToken(s: String): Boolean =
            s.length in 16..128 && (s.startsWith("qs_") || s.startsWith("qc_")) &&
                s.drop(3).all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
    }
}
