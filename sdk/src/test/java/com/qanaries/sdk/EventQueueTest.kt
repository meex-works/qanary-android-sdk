package com.qanaries.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Answers each post with the next status (200 once they run out) and keeps every body it was sent. */
class FakeTransport(vararg codes: Int) : Transport {
    private val statuses = kotlin.collections.ArrayDeque(codes.toList())
    val bodies = mutableListOf<JSONObject>()
    @Synchronized override fun post(path: String, json: String): Int {
        bodies.add(JSONObject(json))
        return statuses.removeFirstOrNull() ?: 200
    }
    @Synchronized fun events(i: Int): List<JSONObject> = bodies[i].getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    @Synchronized fun allSeqs(): List<Long> = bodies.indices.flatMap { i -> events(i).map { it.getLong("seq") } }
}

class EventQueueTest {
    @get:Rule val tmp = TemporaryFolder()
    private val token = "qs_" + "x".repeat(43)
    private var clock = 1_000L
    private var now = 5_000_000L
    private val reserved = mutableListOf<Long>()
    private val file get() = File(tmp.root, "events.jsonl")

    private fun queue(t: Transport, start: Long = 0, appId: String? = "com.example.mealpal", expiresAt: Long = Long.MAX_VALUE) =
        EventQueue(token, EventLog(file), SeqCounter(start) { reserved.add(it) }, t, appId, expiresAt, elapsed = { clock }, wall = { now }, say = {})

    private fun seqsOnDisk() = if (file.exists()) file.readLines().filter { it.isNotBlank() }.map { JSONObject(it).getLong("seq") } else emptyList()

    @Test fun anEventIsOnDiskWhenAddReturns() {
        val q = queue(FakeTransport())
        assertTrue(q.add("session_start", data = mapOf("device" to "Google Pixel 8")))
        q.add("screen_view", screen = "HomeActivity")
        // Nothing has been sent: this is what the next launch would find if the process died now.
        assertEquals(listOf(0L, 1L), seqsOnDisk())
    }

    @Test fun aKilledLaunchsEventsGoOutFirstNextTimeAndOnlyOnce() {
        val q1 = queue(FakeTransport())
        q1.add("session_start")
        q1.add("rage_tap", screen = "Cart", data = mapOf("taps" to 3, "region" to "top"))
        // Killed before any send. The next launch starts at its reservation (50) and appends to the same file.
        val t = FakeTransport()
        val q2 = queue(t, start = 50)
        q2.add("session_start")
        q2.loadLeftovers()
        q2.flush()
        assertEquals("the old ones first, each once", listOf(0L, 1L, 50L), t.allSeqs())
        assertEquals(listOf("session_start", "rage_tap", "session_start"), t.events(0).map { it.getString("type") })
        assertFalse("nothing left to keep", file.exists())
        val t3 = FakeTransport()
        queue(t3, start = 100).apply { loadLeftovers(); flush() }
        assertEquals("a third launch resends nothing", 0, t3.bodies.size)
    }

    @Test fun theWireFormat() {
        val t = FakeTransport()
        val q = queue(t)
        q.add("session_start", data = mapOf("device" to "Google Pixel 8", "sdkInt" to 35, "resumed" to false, "gone" to null))
        clock += 120
        q.add("screen_view", screen = "S".repeat(300))
        q.flush()
        val b = t.bodies[0]
        assertEquals(token, b.getString("sessionToken"))
        assertEquals("0.2.1", b.getString("sdkVersion"))
        assertEquals("the app it's in, for the verify check", "com.example.mealpal", b.getString("appId"))
        val ev = t.events(0)
        assertEquals(listOf(0L, 120L), ev.map { it.getLong("t") })
        assertEquals(setOf("device", "sdkInt", "resumed"), ev[0].getJSONObject("data").keys().asSequence().toSet())
        assertEquals(120, ev[1].getString("screen").length)
        assertFalse(ev[1].has("data"))
    }

    @Test fun noAppIdKeyWhenItIsUnknown() {
        val t = FakeTransport()
        queue(t, appId = null).apply { add("heartbeat"); flush() }
        assertFalse(t.bodies[0].has("appId"))
    }

    @Test fun noAnswerOrAServerErrorKeepsTheSameEventsForTheNextTry() {
        val t = FakeTransport(-1, 503, 200)
        val q = queue(t)
        q.add("heartbeat")
        q.flush()
        q.flush()
        assertEquals(1, q.count)
        assertEquals(listOf(0L), seqsOnDisk())
        q.flush()
        assertEquals("the same event, the same seq, three times; the server stores it once", listOf(0L, 0L, 0L), t.allSeqs())
        assertEquals(0, q.count)
        assertFalse(file.exists())
    }

    @Test fun aRefusedBatchIsDroppedNotRetriedForever() {
        val t = FakeTransport(400)
        val q = queue(t)
        q.add("heartbeat")
        q.flush()
        assertEquals(0, q.count)
        assertFalse(file.exists())
    }

    @Test fun goneOrUnknownSessionStopsForGood() {
        for (status in listOf(410, 404)) {
            file.delete()
            val q = queue(FakeTransport(status))
            var why: String? = null
            q.onFinished = { why = it }
            q.add("heartbeat")
            q.flush()
            assertEquals("$status", "server", why)
            assertTrue(q.stopped)
            assertFalse("nothing is recorded after the session is over", q.add("heartbeat"))
            assertFalse(file.exists())
        }
    }

    @Test fun theBacklogGoesOutInBatchesOf500() {
        val t = FakeTransport()
        val q = queue(t)
        repeat(1203) { q.add("heartbeat") }
        q.flush()
        assertEquals(listOf(500, 500, 203), t.bodies.indices.map { t.events(it).size })
        assertEquals(1202L, t.events(2).last().getLong("seq"))
        assertFalse(file.exists())
    }

    @Test fun eventsAddedWhileABatchIsInFlightStay() {
        lateinit var q: EventQueue
        val t = object : Transport {
            var n = 0
            override fun post(path: String, json: String): Int { if (n++ == 0) q.add("screen_view", screen = "Late"); return if (n == 1) 200 else -1 }
        }
        q = queue(t)
        q.add("heartbeat")
        q.flush()
        assertEquals("sent: seq 0; still waiting: the one added during the send", listOf(1L), seqsOnDisk())
        assertEquals(1, q.count)
    }

    @Test fun sequenceNumbersAreReservedBeforeUse() {
        val q = queue(FakeTransport(), start = 100)
        repeat(120) { q.add("heartbeat") }
        assertEquals(listOf(150L, 200L, 250L), reserved)
    }

    @Test fun fifteenDaysEndItOnTheNextTick() {
        val t = FakeTransport()
        val q = queue(t, expiresAt = now - 1)
        var why: String? = null
        q.onFinished = { why = it }
        q.add("heartbeat")
        q.tick()
        assertEquals("15 days", why)
        assertEquals(0, t.bodies.size)
        assertFalse(file.exists())
    }

    @Test fun aLineHalfWrittenWhenTheAppDiedDoesntSpoilTheNextEvent() {
        file.writeText("""{"seq":0,"t":0,"type":"session_start"}""" + "\n" + """{"seq":1,"t":5,"ty""")
        val t = FakeTransport()
        val q = queue(t, start = 50)
        q.add("session_start")
        // If this launch dies too, the next one must still find the new event intact on its own line.
        val readable = file.readLines().mapNotNull { runCatching { JSONObject(it).getLong("seq") }.getOrNull() }
        assertEquals(listOf(0L, 50L), readable)
        q.loadLeftovers()
        q.flush()
        assertEquals(listOf(0L, 50L), t.allSeqs())
    }

    @Test fun keepsTheNewest5000WhenOfflineForVeryLong() {
        val q = queue(FakeTransport())
        q.loadLeftovers()
        repeat(5003) { q.add("heartbeat") }
        assertEquals(5000, q.count)
        assertEquals(3L, seqsOnDisk().first())
    }

    @Test fun finishSendsWhatIsLeftThenStops() {
        val t = FakeTransport()
        val q = queue(t)
        q.start { true }
        q.add("screen_view", screen = "Checkout")
        q.finish()
        assertFalse("closing: nothing new", q.add("heartbeat"))
        val until = System.currentTimeMillis() + 5000
        while (!q.stopped && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(q.stopped)
        assertEquals(listOf("screen_view"), t.events(0).map { it.getString("type") })
        assertFalse(file.exists())
    }

    @Test fun shutdownForgetsEverything() {
        val q = queue(FakeTransport())
        q.add("heartbeat")
        q.shutdown(deleteFile = true)
        assertFalse(file.exists())
        assertFalse(q.add("heartbeat"))
        assertEquals(0, q.count)
    }
}
