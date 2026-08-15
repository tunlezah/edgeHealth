package au.mark.kinetiq

import au.mark.kinetiq.data.repo.AppSettings
import au.mark.kinetiq.reminders.ReminderOutcome
import au.mark.kinetiq.reminders.ReminderWorker
import au.mark.kinetiq.reminders.reminderOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The reminder worker is the only thing that enqueues the next occurrence, so a single transient
 * failure used to end reminders permanently — silently, and self-reinforcingly, since no reminder
 * means no app open means no reschedule.
 */
class ReminderChainTest {

    private val enabled = AppSettings(reminderDays = setOf(1, 5), reminderHour = 7)
    private val off = AppSettings(reminderDays = emptySet())

    @Test
    fun `a settings read failure retries rather than ending the chain`() {
        assertThat(reminderOutcome(null, runAttemptCount = 0)).isEqualTo(ReminderOutcome.RETRY)
        assertThat(reminderOutcome(null, runAttemptCount = ReminderWorker.MAX_ATTEMPTS - 1))
            .isEqualTo(ReminderOutcome.RETRY)
    }

    @Test
    fun `retries are bounded and hand off to the app-start re-arm`() {
        assertThat(reminderOutcome(null, ReminderWorker.MAX_ATTEMPTS)).isEqualTo(ReminderOutcome.GIVE_UP)
    }

    @Test
    fun `reminders turned off stop the chain cleanly`() {
        assertThat(reminderOutcome(off, 0)).isEqualTo(ReminderOutcome.DISABLED)
    }

    @Test
    fun `a normal firing notifies and reschedules`() {
        assertThat(reminderOutcome(enabled, 0)).isEqualTo(ReminderOutcome.NOTIFY_AND_RESCHEDULE)
    }
}
