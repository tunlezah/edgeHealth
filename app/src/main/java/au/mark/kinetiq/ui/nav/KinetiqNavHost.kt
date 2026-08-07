package au.mark.kinetiq.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

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

    val tabs = listOf(
        Tab(Routes.HOME, "Home") { Icon(Icons.Filled.Home, contentDescription = null) },
        Tab(Routes.HISTORY, "History") { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
        Tab(Routes.LIBRARY, "Library") { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
        Tab(Routes.PLAN, "Plan") { Icon(Icons.Filled.Insights, contentDescription = null) },
        Tab(Routes.SETTINGS, "Settings") { Icon(Icons.Filled.Settings, contentDescription = null) },
    )

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = tab.icon,
                            label = { Text(tab.label) },
                        )
                    }
                }
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
                    onFinished = { navController.navigate(Routes.SUMMARY) { popUpTo(Routes.HOME) } },
                    onExit = { navController.popBackStack(Routes.HOME, inclusive = false) },
                )
            }
            composable(Routes.SUMMARY) {
                SummaryScreen(onDone = { navController.popBackStack(Routes.HOME, inclusive = false) })
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
