package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import com.headswap.desk.CarLifeSession
import com.headswap.desk.SessionConfig
import com.headswap.desk.aa.AaBridgeCore
import com.headswap.desk.dex.DelegatedDesktopAccess
import com.headswap.desk.dex.DesktopMusicPlayback
import com.headswap.desk.dex.DexPrefs
import com.headswap.desk.link.StreamTransport
import com.headswap.desk.link.TransportCaps
import java.util.concurrent.atomic.AtomicBoolean

/** Android player commands are real. A local fd models the accessory, with the detach notification delivered late. */
internal object UsbMusicLossProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        try {
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only" }
            val context = test.targetContext
            val fixture = test.context.packageName
            val fixtureUid = test.context.applicationInfo.uid
            check(fixtureUid != Process.myUid()) { "Player must run in a different UID" }
            result.putInt("playerUid", fixtureUid)
            val ui = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui.adoptShellPermissionIdentity(DelegatedDesktopAccess.ADD_TRUSTED_DISPLAY, DelegatedDesktopAccess.INJECT_EVENTS,
                DelegatedDesktopAccess.REAL_GET_TASKS, DelegatedDesktopAccess.MEDIA_CONTENT_CONTROL)
            val prefs = DexPrefs(context)
            val previous = prefs.accessMode
            val lease = DelegatedDesktopAccess.begin {}
            var player: MediaController? = null
            try {
                prefs.useDelegated = true
                check(DelegatedDesktopAccess.isReady(context)) { "Missing media/session grants" }
                val manager = context.getSystemService(MediaSessionManager::class.java)
                check(manager.getActiveSessions(null).none {
                    it.packageName != fixture && it.playbackState?.state == PlaybackState.STATE_PLAYING
                }) { "Another player is active on the emulator" }
                val cmd = "am start -W -f 0x10008000 -n $fixture/com.headswap.desk.qa.SilentPlayerActivity"
                val launch = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).bufferedReader().use { it.readText() }
                check("Status: ok" in launch) { "Fixture launch failed: $launch" }
                await(6_000, "Fixture was not Android's selected playing session") {
                    player = manager.getActiveSessions(null).singleOrNull {
                        it.packageName == fixture && it.sessionToken == manager.mediaKeyEventSession &&
                            it.playbackState?.state == PlaybackState.STATE_PLAYING
                    }
                    player != null
                }
                exercise(test, checkNotNull(player), result)
            } finally {
                test.runOnMainSync { DesktopMusicPlayback.cancel("QA finished") }
                player?.transportControls?.stop()
                prefs.accessMode = previous
                DelegatedDesktopAccess.end(lease)
                ui.dropShellPermissionIdentity()
            }
        } catch (error: Throwable) { result.putString("error", android.util.Log.getStackTraceString(error)) }
        test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
    }

    private fun exercise(test: Instrumentation, player: MediaController, result: Bundle) {
        val service = Any()
        val (fd, peer) = ParcelFileDescriptor.createSocketPair()
        val transport = StreamTransport.fromPfd(fd, TransportCaps.USB_AOA, "QA accessory")
        lateinit var session: CarLifeSession
        session = CarLifeSession(transport, SessionConfig(), AaBridgeCore(log = { _, _ -> }),
            onUsbOutputLost = { owner, picture -> DesktopMusicPlayback.usbOutputLost(service, owner, picture) },
            onClosed = { DesktopMusicPlayback.linkClosed(session) })
        try {
            test.runOnMainSync { DesktopMusicPlayback.attach(test.targetContext, service, session, true, { true }, { true }) }
            session.start()
            val lostAt = SystemClock.elapsedRealtime()
            peer.close() // Real EOF enters StreamTransport -> CarLifeSession -> DesktopMusicPlayback.
            await(1_000, "Player did not pause on fd loss before accessory detach") {
                player.playbackState?.state == PlaybackState.STATE_PAUSED && player.extras?.getInt("pauses") == 1
            }
            val pauseMs = checkNotNull(player.extras).getLong("pausedAt") - lostAt
            check(pauseMs in 0L until 1_000L) { "Player received PAUSE after ${pauseMs} ms" }
            result.putLong("pauseAfterFdLossMs", pauseMs)

            val video = AtomicBoolean(false)
            val audio = AtomicBoolean(false)
            // Samsung can reopen AOA before its old detach notification, without delivering new video.
            test.runOnMainSync { DesktopMusicPlayback.attach(test.targetContext, service, Any(), true, video::get, audio::get) }
            SystemClock.sleep((lostAt + 2_100 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            test.runOnMainSync { DesktopMusicPlayback.physicalDetach(service) }
            result.putLong("lateDetachAfterFdLossMs", SystemClock.elapsedRealtime() - lostAt)
            check(player.extras?.getInt("pauses") == 1 && player.extras?.getInt("plays") == 0)

            test.runOnMainSync { DesktopMusicPlayback.attach(test.targetContext, service, Any(), true, video::get, audio::get) }
            video.set(true)
            SystemClock.sleep(600) // More than two production readiness polls with no audio.
            check(player.playbackState?.state == PlaybackState.STATE_PAUSED && player.extras?.getInt("plays") == 0)
            audio.set(true)
            await(3_000, "Same player did not resume when both video and audio were ready") {
                player.playbackState?.state == PlaybackState.STATE_PLAYING && player.extras?.getInt("plays") == 1
            }
            check(player.extras?.getInt("pauses") == 1) { "Duplicate PAUSE" }
            result.putString("commands", "1 PAUSE, 1 PLAY")
        } finally {
            peer.close()
            session.close("QA finished")
        }
    }

    private fun await(timeoutMs: Long, failure: String, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!ready()) {
            check(SystemClock.elapsedRealtime() < deadline) { failure }
            SystemClock.sleep(20)
        }
    }
}
