package au.mark.kinetiq.reminders

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import au.mark.kinetiq.KinetiqApp
import au.mark.kinetiq.MainActivity
import au.mark.kinetiq.R
import au.mark.kinetiq.data.repo.AppSettings
import au.mark.kinetiq.data.repo.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-scheduled workout reminders via WorkManager. Each firing posts the notification and
 * schedules the next occurrence, so reminders survive reboots without exact-alarm permissions.
 */
@Singleton
class ReminderScheduler @Inject constructor() {

    /** (Re)schedules a one-time work request for the next configured reminder slot. */
    fun schedule(context: Context, days: Set<Int>, hour: Int, minute: Int) {
        val wm = WorkManager.getInstance(context)
        if (days.isEmpty()) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val delay = delayToNext(LocalDateTime.now(), days, hour, minute)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay)
            .build()
        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Self-heal: enqueue the next occurrence only if nothing is already scheduled.
     *
     * KEEP (never REPLACE) is load-bearing. `Application.onCreate` also runs when WorkManager
     * starts the process to execute [ReminderWorker] itself, and REPLACE would cancel the very
     * worker that is about to post the notification.
     */
    fun ensureScheduled(context: Context, days: Set<Int>, hour: Int, minute: Int) {
        val wm = WorkManager.getInstance(context)
        if (days.isEmpty()) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayToNext(LocalDateTime.now(), days, hour, minute))
            .build()
        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "kinetiq_reminder"

        /** Duration until the next occurrence of (day-of-week in [days], time hh:mm). Pure for tests. */
        fun delayToNext(now: LocalDateTime, days: Set<Int>, hour: Int, minute: Int): Duration {
            require(days.isNotEmpty())
            val target = LocalTime.of(hour, minute)
            for (offset in 0..7) {
                val candidate = now.toLocalDate().plusDays(offset.toLong()).atTime(target)
                if (candidate.isAfter(now) && candidate.dayOfWeek.value in days) {
                    return Duration.between(now, candidate)
                }
            }
            return Duration.ofDays(7)
        }
    }
}

/** What a reminder firing should do, given the settings read and the attempt count. */
enum class ReminderOutcome { NOTIFY_AND_RESCHEDULE, DISABLED, RETRY, GIVE_UP }

internal fun reminderOutcome(settings: AppSettings?, runAttemptCount: Int): ReminderOutcome = when {
    settings == null && runAttemptCount < ReminderWorker.MAX_ATTEMPTS -> ReminderOutcome.RETRY
    settings == null -> ReminderOutcome.GIVE_UP
    settings.reminderDays.isEmpty() -> ReminderOutcome.DISABLED
    else -> ReminderOutcome.NOTIFY_AND_RESCHEDULE
}

@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val scheduler: ReminderScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // This worker is the only thing that enqueues the next occurrence, so an unguarded throw
        // from DataStore (IOException/CorruptionException — no ReplaceFileCorruptionHandler is
        // configured) would end reminders permanently, with the user's only recovery being to
        // re-toggle a setting they have no way of knowing is broken.
        val settings = runCatching { settingsRepository.current() }.getOrNull()
        return when (reminderOutcome(settings, runAttemptCount)) {
            ReminderOutcome.RETRY -> Result.retry()
            // Hand off to KinetiqApp's re-arm on the next process start rather than retrying forever.
            ReminderOutcome.GIVE_UP -> Result.success()
            ReminderOutcome.DISABLED -> Result.success()
            ReminderOutcome.NOTIFY_AND_RESCHEDULE -> {
                postNotification()
                scheduler.schedule(
                    applicationContext, settings!!.reminderDays, settings.reminderHour, settings.reminderMinute,
                )
                Result.success()
            }
        }
    }

    private fun postNotification() {
        val context = applicationContext
        val contentIntent = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, KinetiqApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(context.getString(R.string.reminder_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(7, notification)
        }
    }

    companion object {
        /** Backoff attempts before handing off to the app-start re-arm. */
        const val MAX_ATTEMPTS = 4
    }
}
