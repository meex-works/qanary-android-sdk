package com.qanaries.sdk

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Where the SDK keeps its few files: `<no-backup files dir>/com.qanaries.sdk` (Context.getNoBackupFilesDir), which
 * Android never puts in a cloud backup or a device-to-device transfer, so a session token can't follow a backup onto
 * another phone. `state.json` holds the session token, when it was opened from Qanary (the 15-day limit counts from
 * there) and the reserved sequence number; one `events-….jsonl` per session holds its unsent events. No preferences.
 */
internal class Store(val dir: File) {
    data class State(val token: String, val since: Long, val seqNext: Long)

    private val stateFile get() = File(dir, "state.json")

    fun eventsFile(token: String): File =
        File(dir, "events-" + token.takeLast(16).filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' } + ".jsonl")

    fun load(): State? = runCatching {
        if (!stateFile.exists()) return null
        val o = JSONObject(stateFile.readText())
        State(o.getString("token"), o.getLong("since"), o.getLong("seqNext"))
    }.getOrNull()

    /** Atomic: written to a temporary file, then renamed over the old one, so a kill leaves the old or the new state. */
    fun save(s: State) {
        dir.mkdirs()
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(JSONObject().put("token", s.token).put("since", s.since).put("seqNext", s.seqNext).toString())
        if (!tmp.renameTo(stateFile)) { stateFile.delete(); tmp.renameTo(stateFile) }
    }

    /** The session ended: its state goes (only if it's still the saved one). */
    fun clearState(ifToken: String) {
        if (load()?.token == ifToken) stateFile.delete()
    }

    /** Event files of sessions that are no longer current (e.g. the app died while one session replaced another). */
    fun removeEventFiles(except: File?) {
        dir.listFiles()?.filter { it.name.startsWith("events-") && it.name != except?.name }?.forEach { it.delete() }
    }
}

/**
 * One session's unsent events, one JSON event per line. Appends go straight to the file (no buffer in between), so an
 * event is on disk, in the kernel's hands, when [append] returns: killing the process can't lose it. Not thread-safe:
 * [EventQueue] calls it under its lock.
 */
internal class EventLog(val file: File) {
    private var out: FileOutputStream? = null

    /** Before the first append: if the last run died halfway through writing a line, end that line first. */
    fun open() {
        if (file.length() == 0L) return
        RandomAccessFile(file, "rw").use { f ->
            f.seek(f.length() - 1)
            if (f.read() != '\n'.code) f.write('\n'.code)
        }
    }

    fun append(line: String) {
        val o = out ?: run { file.parentFile?.mkdirs(); FileOutputStream(file, true).also { out = it } }
        o.write((line + "\n").toByteArray(Charsets.UTF_8))
    }

    fun readLines(): List<String> = if (file.exists()) file.readLines(Charsets.UTF_8).filter { it.isNotBlank() } else emptyList()

    /** Replaces the file with [lines] (atomic rename), or removes it when nothing is left. */
    fun rewrite(lines: List<String>) {
        close()
        if (lines.isEmpty()) { file.delete(); return }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(lines.joinToString("") { it + "\n" }, Charsets.UTF_8)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun close() {
        runCatching { out?.close() }
        out = null
    }

    fun delete() {
        close()
        file.delete()
        File(file.path + ".tmp").delete()
    }
}

/**
 * Hands out `seq` numbers. Each block of [BLOCK] is reserved on disk before its first number is used, so a later launch
 * starts above every number this one used, even after a kill (the API keeps the first event with a given seq).
 */
internal class SeqCounter(val start: Long, private val reserve: (upTo: Long) -> Unit) {
    private var next = start
    private var reservedUpTo = start

    fun next(): Long {
        if (next >= reservedUpTo) {
            reservedUpTo = next + BLOCK
            reserve(reservedUpTo)
        }
        return next++
    }

    companion object { const val BLOCK = 50L }
}

/**
 * SDK 0.2.0 kept the session in SharedPreferences "qanary_sdk" (which Auto Backup includes) and unsent events in the
 * files folder. Moved once into [Store], then removed.
 */
internal object Legacy {
    private const val PREFS = "qanary_sdk"
    /** 0.2.0 saved its next seq asynchronously, so a kill could lose the last few: start well above them. */
    const val SEQ_MARGIN = 1000L

    fun migrate(app: Application, store: Store) {
        val prefsFile = File(app.filesDir.parentFile, "shared_prefs/$PREFS.xml")
        val oldEvents = app.filesDir.listFiles()?.filter { it.name.startsWith("qanary_events_") }.orEmpty()
        if (!prefsFile.exists() && oldEvents.isEmpty()) return
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = p.getString("token", null)
        move(store, token, p.getLong("since", 0), token?.let { p.getLong("seq_$it", 0) } ?: 0, oldEvents)
        p.edit().clear().commit()
        if (Build.VERSION.SDK_INT >= 24) app.deleteSharedPreferences(PREFS) else prefsFile.delete()
        Qanary.log("moved the saved session out of SDK 0.2.0's preferences")
    }

    /** The saved session and its unsent events into [store] (unless it already has one); the old files are removed. */
    fun move(store: Store, token: String?, since: Long, seq: Long, oldEvents: List<File>) {
        if (token != null && store.load() == null) {
            val mine = oldEvents.firstOrNull { it.name == "qanary_events_${token.takeLast(12)}.jsonl" }
            val lines = mine?.let { EventLog(it).readLines() }.orEmpty()
            val maxSeq = lines.mapNotNull { runCatching { JSONObject(it).getLong("seq") }.getOrNull() }.maxOrNull() ?: -1
            store.save(Store.State(token, since, maxOf(seq, maxSeq + 1) + SEQ_MARGIN))
            if (lines.isNotEmpty()) EventLog(store.eventsFile(token)).rewrite(lines)
        }
        oldEvents.forEach { it.delete() }
    }
}

/**
 * The SDK writes a line to disk on the caller's thread (that's what makes an event safe when it's added), which can be
 * the main thread. It's a few hundred bytes, so it's allowed past a host app's StrictMode disk policy for that moment.
 */
internal inline fun <T> diskOk(block: () -> T): T {
    val policy = StrictMode.allowThreadDiskWrites()
    try {
        return block()
    } finally {
        if (policy != null) StrictMode.setThreadPolicy(policy)
    }
}
