package com.roomscan.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugReplayTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun releaseAndOrdinaryLaunchesCannotRequestReplay() {
        assertNull(DebugReplay.requestedMode(false, "sparse_tracking"))
        assertNull(DebugReplay.requestedMode(false, "arbitrary input"))
        assertNull(DebugReplay.requestedMode(true, null))
        assertEquals(CaptureMode.SPARSE_TRACKING, DebugReplay.requestedMode(true, "sparse_tracking"))
        assertEquals(CaptureMode.DEPTH, DebugReplay.requestedMode(true, "depth"))
        assertThrows(IllegalArgumentException::class.java) { DebugReplay.requestedMode(true, "../../outside.mp4") }
    }

    @Test fun missingOrEmptyFixtureFailsWithoutChoosingAnotherSource() {
        val unrelated = File(temporary.root, "other.mp4").apply { writeBytes(byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { DebugReplay.fixture(temporary.root) }
        val fixture = File(temporary.root, "public-tests/replay.mp4")
        fixture.parentFile!!.mkdirs()
        fixture.writeBytes(byteArrayOf())
        assertThrows(IllegalArgumentException::class.java) { DebugReplay.fixture(temporary.root) }
        fixture.writeBytes(byteArrayOf(1))
        assertEquals(fixture.canonicalFile, DebugReplay.fixture(temporary.root))
        assertTrue(unrelated.exists())
    }

    @Test fun finishedRunCannotRestartOrBecomeInterruptedOnBackground() {
        val run = ReplayRun()
        assertTrue(run.start())
        assertFalse(run.start())
        assertTrue(run.stop(ReplayRun.State.FINISHED))
        assertFalse(run.stop(ReplayRun.State.INTERRUPTED))
        assertFalse(run.start())
        assertEquals(ReplayRun.State.FINISHED, run.state)
    }

    @Test fun interruptedFailedAndUserStoppedRunsRequireANewRun() {
        for (reason in listOf(ReplayRun.State.INTERRUPTED, ReplayRun.State.FAILED, ReplayRun.State.STOPPED)) {
            val run = ReplayRun()
            run.start()
            assertTrue(run.stop(reason))
            assertTrue(run.terminal)
            assertFalse(run.start())
            assertEquals(reason, run.state)
        }
        val restored = ReplayRun(restored = true)
        assertTrue(restored.terminal)
        assertFalse(restored.start())
        assertEquals(ReplayRun.State.INTERRUPTED, restored.state)
    }
}
