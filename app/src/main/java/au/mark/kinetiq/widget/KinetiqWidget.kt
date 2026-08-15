package au.mark.kinetiq.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.dp
import au.mark.kinetiq.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.DayOfWeek

/**
 * Home-screen widget (Glance): one-tap "repeat last workout" + current streak.
 * Tapping launches MainActivity with ACTION_REPEAT_LAST, which starts the last session directly.
 */
class KinetiqWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun workoutRepository(): au.mark.kinetiq.data.repo.WorkoutRepository
        fun settingsRepository(): au.mark.kinetiq.data.repo.SettingsRepository
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        // Projections only: the widget renders one name and a streak, so it has no reason to
        // deserialize a stored session per row inside a broadcast receiver's time budget.
        val lastName = runCatching { entryPoint.workoutRepository().lastSessionName() }.getOrNull()
        val settings = runCatching { entryPoint.settingsRepository().current() }.getOrNull()
        val startTimes = runCatching { entryPoint.workoutRepository().historyStartTimes() }
            .getOrDefault(emptyList())
        val restDays = settings?.restDays?.mapNotNull { runCatching { DayOfWeek.of(it) }.getOrNull() }?.toSet()
            ?: setOf(DayOfWeek.SUNDAY)
        val streak = au.mark.kinetiq.domain.plan.StreakCalculator.currentStreak(startTimes, restDays)

        provideContent {
            GlanceTheme {
                // Carries an origin token only this app can mint; Glance passes our Intent through
                // verbatim (extras included) into an immutable PendingIntent it creates from our
                // context, so the token survives to MainActivity on both cold and warm launches.
                val launchIntent = MainActivity.repeatLastIntent(context)
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.widgetBackground)
                        .padding(12.dp)
                        .clickable(actionStartActivity(launchIntent)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (lastName != null) "▶  Repeat: $lastName" else "▶  Start your first workout",
                        style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    Row {
                        Text(
                            text = if (streak > 0) "🔥 $streak day streak" else "No streak yet — today's a good day",
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

class KinetiqWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KinetiqWidget()
}
