package au.mark.kinetiq

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import au.mark.kinetiq.data.repo.SettingsRepository
import au.mark.kinetiq.reminders.ReminderScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class KinetiqApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        enableStrictModeInDebug()
        createNotificationChannels()
        reinstateReminders()
    }

    /**
     * The reminder chain is self-perpetuating: each firing enqueues the next. Re-arming on every
     * process start — which covers reboot, app update, force-stop and a chain broken by a transient
     * worker failure — makes it self-healing without a BOOT_COMPLETED or MY_PACKAGE_REPLACED
     * receiver. Uses the KEEP policy so it never cancels work that is already scheduled.
     */
    private fun reinstateReminders() {
        appScope.launch {
            runCatching {
                val settings = settingsRepository.current()
                reminderScheduler.ensureScheduled(
                    this@KinetiqApp, settings.reminderDays, settings.reminderHour, settings.reminderMinute,
                )
            }
        }
    }

    /**
     * Debug-only: surfaces main-thread disk I/O in logcat rather than letting it go unnoticed.
     * Reads the debuggable flag rather than BuildConfig.DEBUG so the project does not have to
     * enable the buildConfig build feature just for this.
     */
    private fun enableStrictModeInDebug() {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyLog()
                .build()
        )
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SESSION,
                getString(R.string.notification_channel_session),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notification_channel_session_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                getString(R.string.notification_channel_reminders),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = getString(R.string.notification_channel_reminders_desc) }
        )
    }

    companion object {
        const val CHANNEL_SESSION = "session"
        const val CHANNEL_REMINDERS = "reminders"
    }
}
