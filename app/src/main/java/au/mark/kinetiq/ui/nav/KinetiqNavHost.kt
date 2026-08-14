package au.mark.kinetiq.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import au.mark.kinetiq.MainViewModel
import au.mark.kinetiq.ui.screens.builder.BuilderScreen
import au.mark.kinetiq.ui.screens.debuganim.DebugAnimScreen
import au.mark.kinetiq.ui.screens.history.HistoryScreen
import au.mark.kinetiq.ui.screens.home.HomeScreen
import au.mark.kinetiq.ui.screens.library.ExerciseDetailScreen
import au.mark.kinetiq.ui.screens.library.LibraryScreen
import au.mark.kinetiq.ui.screens.onboarding.OnboardingScreen
import au.mark.kinetiq.ui.screens.plan.PlanScreen
import au.mark.kinetiq.ui.screens.player.PlayerScreen
import au.mark.kinetiq.ui.screens.settings.HealthDataScreen
import au.mark.kinetiq.ui.screens.settings.SettingsScreen
import au.mark.kinetiq.ui.screens.summary.SummaryScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val BUILDER = "builder"
    const val PLAYER = "player"
    const val SUMMARY = "summary"
    const val HISTORY = "history"
    const val LIBRARY = "library"
    const val LIBRARY_DETAIL = "library/{exerciseId}"
    const val PLAN = "plan"
    const val SETTINGS = "settings"
    const val HEALTH = "health"
    const val DEBUG_ANIM = "debug_anim"
}

private data class Tab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

/** Navigate to the summary once per finished session, wherever the user happens to be. */
internal fun shouldNavigateToSummary(summaryId: String?, consumedId: String?): Boolean =
    summaryId != null && summaryId != consumedId

@Composable
fun KinetiqApp(mainViewModel: MainViewModel) {
    val navController: NavHostController = rememberNavController()
    val settings by mainViewModel.settings.collectAsState()
    val playerState by mainViewModel.sessionStateHolder.state.collectAsState()
    val pendingLaunch by mainViewModel.pendingPlayerLaunch.collectAsState()

    // Widget "repeat last" launch: jump straight into the player once the service is up.
    LaunchedEffect(pendingLaunch, playerState) {
        if (pendingLaunch && playerState != null) {
            mainViewModel.pendingPlayerLaunch.value = false
            navController.navigate(Routes.PLAYER) { launchSingleTop = true }
        }
    }

    // A finished session always reaches its summary, no matter which screen is open —
    // and only once per sessionId, so leaving the summary via a tab doesn't yank the
    // user back and a republished summary doesn't re-navigate.
    val lastCompleted by mainViewModel.sessionStateHolder.lastCompleted.collectAsState()
    var consumedSummaryId by androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    LaunchedEffect(lastCompleted) {
        val completed = lastCompleted ?: return@LaunchedEffect
        if (shouldNavigateToSummary(completed.sessionId, consumedSummaryId)) {
            consumedSummaryId = completed.sessionId
            navController.navigate(Routes.SUMMARY) {
                launchSingleTop = true
                popUpTo(Routes.HOME)
            }
        }
    }

    val tabs = listOf(
        Tab(Routes.HOME, "Home", Icons.Filled.Home, Icons.Outlined.Home),
        Tab(Routes.HISTORY, "History", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
        Tab(Routes.LIBRARY, "Library", Icons.AutoMirrored.Filled.MenuBook, Icons.AutoMirrored.Outlined.MenuBook),
        Tab(Routes.PLAN, "Plan", Icons.Filled.Insights, Icons.Outlined.Insights),
        Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
    )

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // Nested routes highlight their parent tab instead of leaving the bar unselected.
    val parentTab = mapOf(
        Routes.BUILDER to Routes.HOME,
        Routes.PLAYER to Routes.HOME,
        Routes.SUMMARY to Routes.HOME,
        Routes.LIBRARY_DETAIL to Routes.LIBRARY,
        Routes.HEALTH to Routes.SETTINGS,
        Routes.DEBUG_ANIM to Routes.SETTINGS,
    )
    val selectedTab = parentTab[currentRoute] ?: currentRoute
    // The bar is permanent everywhere except onboarding, so navigation is always one tap away.
    val showBottomBar = currentRoute != Routes.ONBOARDING

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                KinetiqBottomBar(
                    tabs = tabs,
                    currentRoute = selectedTab,
                    onSelect = { route ->
                        navController.navigate(route) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (settings.onboardingComplete) Routes.HOME else Routes.ONBOARDING,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onDone = {
                    navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                })
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onStartBuilder = { navController.navigate(Routes.BUILDER) },
                    onOpenPlayer = { navController.navigate(Routes.PLAYER) },
                    onOpenHealth = { navController.navigate(Routes.HEALTH) },
                    onOpenSummary = { navController.navigate(Routes.SUMMARY) { launchSingleTop = true } },
                )
            }
            composable(Routes.BUILDER) {
                BuilderScreen(
                    onStarted = { navController.navigate(Routes.PLAYER) { popUpTo(Routes.HOME) } },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.PLAYER) {
                PlayerScreen(
                    keepScreenOnDefault = settings.keepScreenOn,
                    onExit = { navController.popBackStack(Routes.HOME, inclusive = false) },
                )
            }
            composable(Routes.SUMMARY) {
                SummaryScreen(
                    onDone = { navController.popBackStack(Routes.HOME, inclusive = false) },
                    onResume = { navController.navigate(Routes.PLAYER) { launchSingleTop = true; popUpTo(Routes.HOME) } },
                )
            }
            composable(Routes.HISTORY) { HistoryScreen() }
            composable(Routes.LIBRARY) {
                LibraryScreen(onOpen = { id -> navController.navigate("library/$id") })
            }
            composable(Routes.LIBRARY_DETAIL) { entry ->
                ExerciseDetailScreen(
                    exerciseId = entry.arguments?.getString("exerciseId") ?: "",
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.PLAN) { PlanScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenHealth = { navController.navigate(Routes.HEALTH) },
                    onOpenDebugAnim = { navController.navigate(Routes.DEBUG_ANIM) },
                )
            }
            composable(Routes.HEALTH) { HealthDataScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.DEBUG_ANIM) { DebugAnimScreen() }
        }
    }
}

/**
 * Bottom navigation with the Material 3 look (pill indicator, label under icon) but with the
 * ENTIRE cell — icon, label, and all the space around them, right to the screen edges — as the
 * touch target. Each cell is at least 72 dp tall and a fifth of the screen wide, so the edge
 * tabs (Home, Settings) are as easy to hit as the middle ones.
 */
@Composable
private fun KinetiqBottomBar(
    tabs: List<Tab>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(76.dp),
        ) {
            tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSelect(tab.route) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(width = 56.dp, height = 32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0f)
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label,
                                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
