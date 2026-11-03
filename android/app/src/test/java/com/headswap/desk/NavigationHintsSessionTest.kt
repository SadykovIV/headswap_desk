package com.headswap.desk

import com.headswap.desk.aa.AaBridgeCore
import com.headswap.desk.aa.NaviField6
import com.headswap.desk.cluster.ClusterNavigationHint
import com.headswap.desk.cluster.ForegroundGuidance
import com.headswap.desk.cluster.ForegroundGuidancePoller
import com.headswap.desk.cluster.GuidanceInterest
import com.headswap.desk.cluster.RouteGuidanceCoordinator
import com.headswap.desk.link.CarTransport
import com.headswap.desk.link.TransportCaps
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NavigationHintsSessionTest {
    private class WirePeer : CarTransport {
        override val caps = TransportCaps.USB_AOA
        override val label = "test CarLife HU"
        override val rxBytes = 0L
        override val queuedVideoBytes = 0L
        override val queuedVideoFrames = 0
        override val drainBytesPerSec = 0.0
        override fun queueSize(chan: Int) = 0
        override fun close() {}
        lateinit var sink: (Int, ByteArray) -> Unit
        val sent = LinkedBlockingQueue<CmdMsg>()

        override fun start(sink: (Int, ByteArray) -> Unit, onWritten: (Int, ByteArray) -> Unit,
                           onClosed: (String) -> Unit) { this.sink = sink }

        override fun send(chan: Int, inner: ByteArray) {
            if (chan == Ch.CMD) Wire.parseCmd(inner)?.let(sent::add)
        }

        fun subscription(message: Int, id: Int) {
            val payload = PbWriter().int32(1, 1).bytes(2, PbWriter().int32(1, id).toByteArray()).toByteArray()
            sink(Ch.CMD, Wire.cmdInner(message, payload))
        }

        fun until(type: Int, forbidden: Int? = null): CmdMsg {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            do {
                val event = sent.poll(end-System.nanoTime(), TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("Missing command ${type.toString(16)}")
                assertNotEquals("Unexpected command while navigation was disabled", forbidden, event.type)
                if (event.type == type) return event
            } while (System.nanoTime() < end)
            throw AssertionError("Missing command ${type.toString(16)}")
        }

        fun expectSupport(id: Int, supported: Int) {
            val done = PbReader.parse(until(Msg.CARLIFE_DATA_SUBSCRIBE_DONE).body)!!
            val entry = PbReader.parse(PbReader.all(done, 2).single().data!!)!!
            assertEquals(id, PbReader.int(entry, 1))
            assertEquals(supported, PbReader.int(entry, 2))
        }
    }

    @Test fun onlyNavigationInterestArmsForegroundSamplingAndExplicitStopRetiresItsEpoch() {
        val peer = WirePeer()
        val states = mutableListOf<GuidanceInterest>()
        lateinit var session: CarLifeSession
        session = CarLifeSession(peer, SessionConfig(naviField6 = NaviField6.TIME), AaBridgeCore(log = { _, _ -> }),
            onNavigationInterestChanged = { states += session.guidanceInterest }, onClosed = {})
        try {
            session.start()
            assertEquals(GuidanceInterest.Mode.NONE, session.guidanceInterest.mode)
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE, 2)
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE_START, 2)
            assertTrue(states.isEmpty())
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE, 0)
            assertEquals(GuidanceInterest.Mode.REQUESTED, states.last().mode)
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE_START, 0)
            val first = states.last()
            assertEquals(GuidanceInterest.Mode.ACTIVE, first.mode)
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE_STOP, 0)
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE, 0)
            assertEquals(GuidanceInterest.Mode.NONE, states.last().mode)
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE_START, 0)
            assertEquals(GuidanceInterest.Mode.ACTIVE, states.last().mode)
            assertNotSame(first, states.last())
            session.setNavigationHints(false)
            assertEquals(GuidanceInterest.Mode.NONE, states.last().mode)
            assertTrue(session.sendMediaProgress(500))
        } finally { session.close("test finished") }
    }

    @Test fun hondaWaitsForNaviStatusWhenTheForegroundRouteStartsAfterTheSubscriptionRequest() {
        val peer = WirePeer()
        val bridge = AaBridgeCore(log = { _, _ -> })
        lateinit var session: CarLifeSession
        var visible: ForegroundGuidance = ForegroundGuidance.Unavailable
        var reads = 0
        val coordinator = RouteGuidanceCoordinator(
            requestForeground = { reply -> reads++; reply(visible) },
            onWorker = { it() }, after = { _, _ -> }, emit = { session.onClusterNavigation(it) },
        )
        var tick: (() -> Unit)? = null
        var interval = 0L
        val polling = ForegroundGuidancePoller(
            schedule = { delay, action -> interval = delay; tick = action; { tick = null } },
            canRead = { it === session.guidanceInterest }, poll = coordinator::poll,
        )
        session = CarLifeSession(peer, SessionConfig(naviField6 = NaviField6.TIME), bridge,
            onNavigationInterestChanged = {
                val token = session.guidanceInterest
                coordinator.notifications(null, token.mode != GuidanceInterest.Mode.NONE)
                polling.update(token)
            }, onClosed = {})
        try {
            session.start()
            bridge.onHuStatistic("20832125")
            bridge.onCarlifeStart()
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE, 0)
            peer.expectSupport(0, 1)
            assertEquals(1, reads)
            assertEquals(5_000L, interval)
            val requestedTick = checkNotNull(tick)
            requestedTick() // still no route when the first interval expires
            assertEquals(2, reads)

            visible = ForegroundGuidance.Maneuver(ClusterNavigationHint(3, "", 180))
            requestedTick() // a maneuver now exists, but Yandex did not post a notification
            val status = PbReader.parse(peer.until(Msg.MODULE_STATUS, forbidden = Msg.NAVI_NEXT_TURN_INFO).body)!!
            val module = PbReader.parse(PbReader.all(status, 2).single().data!!)!!
            assertEquals(2, PbReader.int(module, 1)) // MODULE_STATUS NAVI = 2; subscription NAVI = 0
            assertEquals(1, PbReader.int(module, 2))
            assertTrue(peer.sent.none { it.type == Msg.NAVI_NEXT_TURN_INFO })
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE_START, 0)
            val show = PbReader.parse(peer.until(Msg.NAVI_NEXT_TURN_INFO).body)!!
            assertEquals(1, PbReader.int(show, 1))
            assertEquals(3, PbReader.int(show, 2))
            assertEquals(180, PbReader.int(show, 5))
            assertEquals(2_000L, interval)
            val activeTick = checkNotNull(tick)
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE_STOP, 0)
            assertNull(tick)
            val previous = reads
            requestedTick()
            activeTick()
            assertEquals(previous, reads)
        } finally {
            session.close("test finished")
        }
    }

    @Test fun liveToggleClearsShownTurnBeforeDonePreservesMusicAndRearmsNavigation() {
        val peer = WirePeer()
        val bridge = AaBridgeCore(log = { _, _ -> })
        val session = CarLifeSession(peer, SessionConfig(naviField6 = NaviField6.TIME), bridge, onClosed = {})
        val hint = ClusterNavigationHint(3, "Road", 180)
        try {
            session.start()
            bridge.onCarlifeStart()
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE, 0)
            peer.expectSupport(0, 1)
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE_START, 0)
            session.onClusterNavigation(hint)
            assertEquals(1, PbReader.int(PbReader.parse(peer.until(Msg.NAVI_NEXT_TURN_INFO).body)!!, 1))
            // Some HUs ask about music in a separate message after navigation.
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE, 2)
            peer.expectSupport(2, 1)
            session.setNavigationHints(false)
            assertEquals(3, PbReader.int(PbReader.parse(peer.until(Msg.NAVI_NEXT_TURN_INFO).body)!!, 1))
            peer.expectSupport(0, 0)
            session.onClusterNavigation(hint)
            assertTrue(session.sendMediaProgress(1250))
            peer.until(Msg.MEDIA_PROGRESS_BAR, forbidden = Msg.NAVI_NEXT_TURN_INFO)
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE_STOP, 0)
            session.setNavigationHints(true)
            peer.expectSupport(0, 1)
            peer.subscription(Msg.CARLIFE_DATA_SUBSCRIBE_START, 0)
            session.onClusterNavigation(hint)
            assertEquals(1, PbReader.int(PbReader.parse(peer.until(Msg.NAVI_NEXT_TURN_INFO).body)!!, 1))
        } finally {
            session.close("test finished")
        }
    }
}
