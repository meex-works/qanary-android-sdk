package com.qanaries.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The session lifecycle on a temporary store: dormant until a token, resume, replace, stop, 15 days, old storage. */
class SessionsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val a = "qs_" + "a".repeat(43)
    private val b = "qc_" + "b".repeat(32)
    private var now = 10_000_000_000L
    private val store by lazy { Store(File(tmp.root, "com.qanaries.sdk")) }

    /** A Sessions whose queues share [t]; with [run], each queue gets its real thread (needed for a replacement's send). */
    private fun sessions(t: Transport, run: Boolean = false) = Sessions(
        store,
        newQueue = { st -> EventQueue(st.token, EventLog(store.eventsFile(st.token)), SeqCounter(st.seqNext) { store.save(st.copy(seqNext = it)) }, t, "com.example.mealpal", st.since + Sessions.SESSION_TTL_MS, elapsed = { 0 }, wall = { now }, say = {}) },
        device = { mapOf("device" to "Google Pixel 8", "sdkInt" to 35) },
        startQueue = { q -> if (run) q.start { true } },
        wall = { now }, say = {},
    )

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(what())
    }

    @Test fun dormantUntilATokenArrives() {
        val s = sessions(FakeTransport())
        s.restore()
        assertNull(s.current)
        assertNull(store.load())
        assertFalse("nothing written for an app that was never opened from Qanary", store.dir.exists())
    }

    @Test fun aTokenStartsASessionAndTheNextLaunchResumesIt() {
        val t = FakeTransport()
        val s1 = sessions(t)
        s1.take(a)
        s1.take(a)   // the same token again: still one session
        val q1 = s1.current!!.queue
        q1.add("screen_view", screen = "Home")
        q1.flush()
        assertEquals(listOf("session_start", "screen_view"), t.events(0).map { it.getString("type") })
        assertEquals(false, t.events(0)[0].getJSONObject("data").getBoolean("resumed"))
        assertEquals(a, store.load()?.token)
        // The next launch (the process died without a word): the same session, resumed, numbered above everything used.
        val t2 = FakeTransport()
        val s2 = sessions(t2)
        s2.restore()
        assertEquals(a, s2.current?.token)
        s2.current!!.queue.flush()
        val first = t2.events(0)[0]
        assertEquals("session_start", first.getString("type"))
        assertTrue(first.getJSONObject("data").getBoolean("resumed"))
        assertTrue(first.getLong("seq") > t.allSeqs().max())
    }

    @Test fun aNewTokenReplacesTheOldSessionAndTheOldOnesEventsAreSentFirst() {
        val t = FakeTransport()
        val s = sessions(t, run = true)
        s.take(a)
        val old = s.current!!.queue
        old.add("screen_view", screen = "Checkout")   // not sent yet
        s.take(b)
        assertEquals(b, s.current?.token)
        assertEquals(b, store.load()?.token)
        waitFor { old.stopped }
        val sentWithA = t.bodies.filter { it.getString("sessionToken") == a }.flatMap { body -> body.getJSONArray("events").let { e -> (0 until e.length()).map { e.getJSONObject(it).getString("type") } } }
        assertEquals("the old session's events went out under its own token", listOf("session_start", "screen_view"), sentWithA)
        assertFalse(store.eventsFile(a).exists())
        s.current!!.queue.shutdown(deleteFile = false)
    }

    @Test fun theServerEndingItLeavesNothingBehind() {
        val t = FakeTransport(410)
        val s = sessions(t)
        s.take(a)
        s.current!!.queue.flush()
        assertNull(s.current)
        assertNull(store.load())
        assertFalse(store.eventsFile(a).exists())
    }

    @Test fun aSessionOverFifteenDaysOldIsDroppedAtLaunch() {
        store.save(Store.State(a, now - 16L * 86_400_000, 10))
        File(store.dir, "events-old.jsonl").writeText("{}\n")
        val s = sessions(FakeTransport())
        s.restore()
        assertNull(s.current)
        assertNull(store.load())
        assertFalse(File(store.dir, "events-old.jsonl").exists())
    }

    @Test fun fifteenDaysAreCheckedWhileTheAppRuns() {
        val t = FakeTransport()
        val s = sessions(t)
        s.take(a)
        val q = s.current!!.queue
        now += 16L * 86_400_000
        q.tick()
        assertNull(s.current)
        assertNull(store.load())
        assertEquals(0, t.bodies.size)
    }

    @Test fun eventFilesOfOtherSessionsAreRemovedAtLaunch() {
        store.save(Store.State(a, now, 0))
        File(store.dir, "events-stale.jsonl").writeText("{}\n")
        store.eventsFile(a).writeText("""{"seq":0,"t":0,"type":"heartbeat"}""" + "\n")
        val s = sessions(FakeTransport())
        s.restore()
        assertFalse(File(store.dir, "events-stale.jsonl").exists())
        assertTrue(store.eventsFile(a).exists())
    }

    @Test fun tokensAreCheckedLikeTheIPhoneSdk() {
        assertTrue(Sessions.isToken(a))
        assertTrue(Sessions.isToken(b))
        assertFalse(Sessions.isToken("qs_short"))
        assertFalse(Sessions.isToken("xx_" + "a".repeat(40)))
        assertFalse(Sessions.isToken("qs_" + "a".repeat(30) + " ;rm"))
        assertFalse(Sessions.isToken("qs_" + "a".repeat(200)))
    }
}

class StoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun stateRoundTripsAndOnlyItsOwnTokenClearsIt() {
        val s = Store(File(tmp.root, "d"))
        assertNull(s.load())
        s.save(Store.State("qs_" + "a".repeat(43), 123, 50))
        assertEquals(Store.State("qs_" + "a".repeat(43), 123, 50), s.load())
        s.save(Store.State("qs_" + "a".repeat(43), 123, 100))
        assertEquals(100L, s.load()?.seqNext)
        assertFalse("no temporary file left", File(s.dir, "state.json.tmp").exists())
        s.clearState(ifToken = "qs_other")
        assertNotNull(s.load())
        s.clearState(ifToken = "qs_" + "a".repeat(43))
        assertNull(s.load())
    }

    @Test fun aDamagedStateFileIsNoSession() {
        val s = Store(File(tmp.root, "d"))
        s.dir.mkdirs()
        File(s.dir, "state.json").writeText("{\"tok")
        assertNull(s.load())
    }

    @Test fun eventsFilesAreNamedSafely() {
        val s = Store(File(tmp.root, "d"))
        assertEquals("events-abcdefghijklmn-_.jsonl", s.eventsFile("qs_zzzzabcdefghijklmn-_").name)
        assertEquals("events-ab.jsonl", s.eventsFile("../../ab").name)
    }
}

/** SDK 0.2.0 kept the token in SharedPreferences (included in Auto Backup): it moves once into the no-backup store. */
class LegacyTest {
    @get:Rule val tmp = TemporaryFolder()
    private val token = "qs_" + "k".repeat(43)

    @Test fun theSessionAndItsUnsentEventsMoveAndTheOldFilesGo() {
        val store = Store(File(tmp.root, "no_backup/com.qanaries.sdk"))
        val files = File(tmp.root, "files").apply { mkdirs() }
        val mine = File(files, "qanary_events_${token.takeLast(12)}.jsonl").apply {
            writeText((0..4).joinToString("") { """{"seq":$it,"t":0,"type":"heartbeat"}""" + "\n" })
        }
        val other = File(files, "qanary_events_somethingelse.jsonl").apply { writeText("{}\n") }
        Legacy.move(store, token, since = 777, seq = 3, oldEvents = listOf(mine, other))
        val st = store.load()!!
        assertEquals(token, st.token)
        assertEquals(777L, st.since)
        assertEquals("above every seq 0.2.0 used, with room for any it saved too late", 5 + Legacy.SEQ_MARGIN, st.seqNext)
        assertEquals((0L..4L).toList(), EventLog(store.eventsFile(token)).readLines().map { JSONObject(it).getLong("seq") })
        assertFalse(mine.exists())
        assertFalse(other.exists())
    }

    @Test fun aSessionAlreadyInTheNewStoreWins() {
        val store = Store(File(tmp.root, "nb"))
        store.save(Store.State("qc_" + "n".repeat(32), 1, 0))
        Legacy.move(store, token, since = 777, seq = 3, oldEvents = emptyList())
        assertEquals("qc_" + "n".repeat(32), store.load()?.token)
    }

    @Test fun noTokenNoSession() {
        val store = Store(File(tmp.root, "nb"))
        val f = File(tmp.root, "qanary_events_x.jsonl").apply { writeText("{}\n") }
        Legacy.move(store, null, 0, 0, listOf(f))
        assertNull(store.load())
        assertFalse(f.exists())
    }
}

class FreezeDetectorTest {
    @Test fun aLongFreezeIsReportedOnce() {
        val d = FreezeDetector()
        d.pinged(0)
        assertNull(d.check(4_999, inFront = true))
        assertEquals(5_000L, d.check(5_000, inFront = true))
        assertNull("the same freeze, 5 s later: not again", d.check(10_000, inFront = true))
        assertNull(d.check(60_000, inFront = true))
        d.answered()
        d.pinged(61_000)
        assertEquals("a new freeze is a new report", 5_500L, d.check(66_500, inFront = true))
    }

    @Test fun onlyInFront() {
        val d = FreezeDetector()
        d.pinged(0)
        assertNull(d.check(6_000, inFront = false))
    }

    @Test fun aPausedWatcherIsNotAFreeze() {
        val d = FreezeDetector()
        d.pinged(0)
        d.paused(60_000)   // the whole app was frozen in the background for a minute
        assertNull(d.check(60_500, inFront = true))
        assertEquals(5_000L, d.check(65_000, inFront = true))
    }
}

class EndpointTest {
    @Test fun httpsOrLocalOnly() {
        assertTrue(Qanary.endpointAllowed("https://api.qanaries.com"))
        assertTrue(Qanary.endpointAllowed("http://10.0.2.2:8799"))
        assertTrue(Qanary.endpointAllowed("http://localhost:8799"))
        assertTrue(Qanary.endpointAllowed("http://127.0.0.1:8799"))
        assertFalse(Qanary.endpointAllowed("http://api.qanaries.com"))
        assertFalse(Qanary.endpointAllowed("ftp://localhost"))
        assertFalse(Qanary.endpointAllowed("api.qanaries.com"))
        assertFalse(Qanary.endpointAllowed("https://"))
        assertFalse(Qanary.endpointAllowed(""))
    }
}
