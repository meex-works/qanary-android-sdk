package com.qanaries.sdk

import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * One session's events. Each event is written to the session's file before [add] returns, so a crash or a kill loses
 * nothing, and leaves the file only once the server has answered for it. Batches of up to 500 go out every 10 s, and
 * when the app goes to the background. Each event's `seq` is unique for the session (see [SeqCounter]) and the API keeps
 * the first event with a given seq, so a batch sent again (a kill between the answer and the file update) is stored once.
 * Uses only Android's built-in JSON and HTTP.
 */
internal class EventQueue(
    private val token: String,
    private val log: EventLog,
    private val seq: SeqCounter,
    private val transport: Transport,
    /** The app the SDK is in (its package name): our server matches it to the app when you verify. */
    private val appId: String?,
    /** Wall-clock ms after which the session stops on this device (15 days after it was opened from Qanary). */
    private val expiresAt: Long,
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() },
    private val wall: () -> Long = { System.currentTimeMillis() },
    private val say: (String) -> Unit = Qanary::log,
) {
    /** The session is over for good: the server said so (410, or 404 for a session it doesn't know), or 15 days passed. */
    var onFinished: ((why: String) -> Unit)? = null

    private class Pending(val seq: Long, val line: String)

    private val lock = Any()
    private val pending = ArrayList<Pending>()   // in seq order
    private val t0 = elapsed()
    private var closing = false
    private var loaded = false
    @Volatile var stopped = false
        private set
    @Volatile private var exec: ScheduledExecutorService? = null

    init { log.open() }

    val count: Int get() = synchronized(lock) { pending.size }

    /** Records an event. False when the session is over or closing (nothing more is recorded then). */
    fun add(type: String, screen: String? = null, data: Map<String, Any?>? = null): Boolean = diskOk {
        synchronized(lock) {
            if (stopped || closing) return false
            val n = seq.next()
            val e = JSONObject().put("seq", n).put("t", (elapsed() - t0).coerceAtLeast(0)).put("type", type)
            if (screen != null) e.put("screen", screen.take(120))
            if (!data.isNullOrEmpty()) e.put("data", JSONObject(data.filterValues { it != null }))
            val line = e.toString()
            try { log.append(line) } catch (x: Exception) { say("couldn't save an event to disk (${x.javaClass.simpleName})") }
            pending.add(Pending(n, line))
            if (pending.size > MAX_PENDING && loaded) {   // offline for very long: keep the newest
                pending.subList(0, pending.size - MAX_PENDING).clear()
                rewrite()
            }
            true
        }
    }

    /** Starts the SDK's own thread: leftover events first, then a send every 10 s and a heartbeat every 60 s in front. */
    fun start(inForeground: () -> Boolean) {
        val e = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "qanary-flush").apply { isDaemon = true } }
        exec = e
        e.execute { loadLeftovers() }
        e.scheduleWithFixedDelay({ tick() }, TICK_S, TICK_S, TimeUnit.SECONDS)
        e.scheduleWithFixedDelay({ if (inForeground()) add("heartbeat") }, HEARTBEAT_S, HEARTBEAT_S, TimeUnit.SECONDS)
    }

    fun flushNow() { runCatching { exec?.takeIf { !it.isShutdown }?.execute { flush() } } }

    /** A new session replaced this one: what's left is sent (one pass), then the session's file goes and it stops. */
    fun finish() {
        synchronized(lock) { closing = true }   // an add in progress completes first; nothing new after this
        val e = exec
        if (e == null || e.isShutdown) return shutdown(deleteFile = true)
        runCatching { e.execute { flush(); shutdown(deleteFile = true) } }.onFailure { shutdown(deleteFile = true) }
    }

    /** Stop for good: nothing more is added or sent. */
    fun shutdown(deleteFile: Boolean) {
        diskOk {
            synchronized(lock) {
                stopped = true
                pending.clear()
                if (deleteFile) log.delete() else log.close()
            }
        }
        exec?.shutdownNow()
    }

    /** On the SDK's thread, before any send: events an earlier launch left in the file (a crash, a kill, no network). */
    internal fun loadLeftovers() {
        val old = runCatching { log.readLines() }.getOrDefault(emptyList())
            .mapNotNull { line -> seqOf(line)?.takeIf { it < seq.start }?.let { Pending(it, line) } }   // ≥ start: this launch's own
            .sortedBy { it.seq }
        synchronized(lock) {
            loaded = true
            if (!stopped) pending.addAll(0, old)
        }
    }

    /** Every 10 s: the 15-day limit, then send. */
    internal fun tick() {
        if (wall() > expiresAt) {
            say("session is over 15 days old; recording stopped")
            return finishedForGood("15 days")
        }
        flush()
    }

    /** Sends batch after batch until nothing is left, a send fails, or 10 batches went out. On the SDK's thread. */
    internal fun flush() {
        repeat(10) {
            val batch = synchronized(lock) { if (stopped) return; pending.take(MAX_BATCH) }
            if (batch.isEmpty()) return
            val code = transport.post("/v1/sdk/events", body(batch))
            when {
                code == 410 || code == 404 -> {   // over (410), or a session the server doesn't know (404: a newer link replaced it)
                    say("server: session finished ($code)")
                    return finishedForGood("server")
                }
                code in 200..499 -> {   // delivered, or refused for good (a malformed batch): either way it's done with
                    say(if (code < 300) "sent ${batch.size} events" else "batch refused ($code)")
                    val last = batch.last().seq
                    synchronized(lock) {
                        if (stopped) return
                        pending.removeAll { it.seq <= last }
                        rewrite()
                    }
                }
                else -> { say("send failed ($code); will retry"); return }   // no answer, or a server error: kept
            }
        }
    }

    private fun finishedForGood(why: String) {
        shutdown(deleteFile = true)
        onFinished?.invoke(why)
    }

    /** Under the lock: the file holds exactly what's still unsent. */
    private fun rewrite() {
        try { log.rewrite(pending.map { it.line }) } catch (x: Exception) { say("couldn't update the events file (${x.javaClass.simpleName})") }
    }

    private fun body(batch: List<Pending>): String {
        val head = JSONObject().put("sessionToken", token).put("sdkVersion", Qanary.VERSION)
        if (appId != null) head.put("appId", appId)
        return head.toString().dropLast(1) + ",\"events\":[" + batch.joinToString(",") { it.line } + "]}"
    }

    private fun seqOf(line: String): Long? = runCatching { JSONObject(line).getLong("seq") }.getOrNull()

    companion object {
        const val MAX_BATCH = 500
        const val MAX_PENDING = 5000
        const val TICK_S = 10L
        const val HEARTBEAT_S = 60L
    }
}

internal fun interface Transport {
    /** POSTs [json] to [path]: the HTTP status, or -1 when there was no answer (offline, timeout). */
    fun post(path: String, json: String): Int
}

internal class HttpTransport(private val endpoint: String) : Transport {
    override fun post(path: String, json: String): Int = runCatching {
        val c = URL(endpoint.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("user-agent", "qanary-sdk/${Qanary.VERSION}")
        c.outputStream.use { it.write(json.toByteArray()) }
        val code = c.responseCode
        c.disconnect()
        code
    }.getOrDefault(-1)
}
