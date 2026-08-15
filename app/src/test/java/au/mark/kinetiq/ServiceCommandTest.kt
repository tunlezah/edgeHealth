package au.mark.kinetiq

import au.mark.kinetiq.service.WorkoutSessionService
import au.mark.kinetiq.service.isForegroundEntry
import au.mark.kinetiq.service.shouldHoldWakeLock
import au.mark.kinetiq.service.shouldIgnoreStart
import au.mark.kinetiq.service.shouldTearDown
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The service's command-dispatch decisions, extracted so they can be asserted without an Android
 * runtime — the same shape as [au.mark.kinetiq.service.stopArmDecision] in SessionEngineTest.
 */
class ServiceCommandTest {

    @Test
    fun `only the three startForegroundService actions arm the foreground watchdog`() {
        // Each of these must reach startForeground or stop; the control actions arrive via
        // startService and arm nothing.
        assertThat(isForegroundEntry(WorkoutSessionService.ACTION_START)).isTrue()
        assertThat(isForegroundEntry(WorkoutSessionService.ACTION_RESUME_SNAPSHOT)).isTrue()
        assertThat(isForegroundEntry(WorkoutSessionService.ACTION_RESUME_STOPPED)).isTrue()

        assertThat(isForegroundEntry(WorkoutSessionService.ACTION_PAUSE)).isFalse()
        assertThat(isForegroundEntry(WorkoutSessionService.ACTION_RESUME)).isFalse()
        assertThat(isForegroundEntry(WorkoutSessionService.ACTION_SKIP)).isFalse()
        assertThat(isForegroundEntry(WorkoutSessionService.ACTION_STOP)).isFalse()
        assertThat(isForegroundEntry(WorkoutSessionService.ACTION_STOP_CONFIRMED)).isFalse()
        assertThat(isForegroundEntry(null)).isFalse()
    }

    @Test
    fun `a finish coroutine only tears down its own run`() {
        // The Health Connect write can take seconds, and a new session may be accepted meanwhile.
        assertThat(shouldTearDown(finishingGen = 3, currentGen = 3)).isTrue()
        assertThat(shouldTearDown(finishingGen = 3, currentGen = 4)).isFalse()
    }

    @Test
    fun `a start is ignored only while a live unfinished session exists`() {
        assertThat(shouldIgnoreStart(false)).isTrue()  // live session — never clobber
        assertThat(shouldIgnoreStart(true)).isFalse()  // finished — a new run may be accepted
        assertThat(shouldIgnoreStart(null)).isFalse()  // no session at all
    }

    @Test
    fun `the wake lock is held only while a step clock is running`() {
        assertThat(shouldHoldWakeLock(paused = false, finished = false)).isTrue()
        assertThat(shouldHoldWakeLock(paused = true, finished = false)).isFalse()
        assertThat(shouldHoldWakeLock(paused = false, finished = true)).isFalse()
    }
}
