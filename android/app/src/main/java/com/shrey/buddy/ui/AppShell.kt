package com.shrey.buddy.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.BuddyApp
import com.shrey.buddy.ui.health.HealthViewModel
import com.shrey.buddy.ui.life.LifeModule
import com.shrey.buddy.ui.life.LifeModuleScreen
import com.shrey.buddy.ui.life.LifeViewModel
import com.shrey.buddy.ui.screens.AccountsScreen
import com.shrey.buddy.ui.screens.BuddyScreen
import com.shrey.buddy.ui.screens.ChatScreen
import com.shrey.buddy.ui.screens.HealthScreen
import com.shrey.buddy.ui.screens.MoneyScreen
import com.shrey.buddy.ui.screens.PlanBoardScreen
import com.shrey.buddy.ui.screens.RunScreen
import com.shrey.buddy.ui.screens.TasksScreen
import com.shrey.buddy.ui.screens.TodayScreen
import com.shrey.buddy.ui.theme.BuddyColors
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable data object TodayRoute
@Serializable data object MoneyRoute
@Serializable data object HealthRoute
@Serializable data object TasksRoute
@Serializable data object BuddyRoute
@Serializable data object AccountsRoute
@Serializable data object ChatRoute
@Serializable data object RunRoute
@Serializable data object ScreenTimeRoute
@Serializable data object BodyRoute
@Serializable data object RunsRoute
@Serializable data object WorkoutsRoute
@Serializable data object CoachRoute
@Serializable data class WellbeingRoute(val kind: String)
@Serializable data object WorkoutLoggerRoute
@Serializable data class WorkoutDetailRoute(val id: String)
@Serializable data class RunDetailRoute(val id: String)
@Serializable data class StepsRoute(val epochDay: Long)
@Serializable data class PlanRoute(val planId: String)
@Serializable data class LifeRoute(val module: String)

private data class Tab(val route: Any, val type: KClass<*>, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(TodayRoute, TodayRoute::class, "Today", Icons.Rounded.Home),
    Tab(MoneyRoute, MoneyRoute::class, "Money", Icons.Rounded.AccountBalanceWallet),
    Tab(TasksRoute, TasksRoute::class, "Tasks", Icons.Rounded.CheckCircle),
    Tab(HealthRoute, HealthRoute::class, "Health", Icons.Rounded.FavoriteBorder),
    Tab(BuddyRoute, BuddyRoute::class, "Buddy", Icons.Rounded.GraphicEq),
)

private fun NavHostController.switchTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun AppShell(vm: AppViewModel, startAt: String? = null) {
    val container = (LocalContext.current.applicationContext as BuddyApp).container
    val hvm: HealthViewModel = viewModel { HealthViewModel(container) }
    val lvm: LifeViewModel = viewModel { LifeViewModel(container) }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()

    // Look for app updates whenever Buddy comes back to the foreground, not just at launch.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.checkForUpdate(quiet = true) }

    // Deep links from widgets / Quick Settings tile / notifications.
    LaunchedEffect(startAt) {
        when (startAt) {
            "chat" -> nav.navigate(ChatRoute)
            "tasks" -> nav.switchTab(TasksRoute)
            "money" -> nav.switchTab(MoneyRoute)
            "health" -> nav.switchTab(HealthRoute)
            "run" -> nav.navigate(RunRoute)
            "coach" -> nav.navigate(CoachRoute)
            "workout" -> nav.navigate(if (hvm.session.state.value != null) WorkoutLoggerRoute else WorkoutsRoute)
            "insights" -> nav.navigate(LifeRoute(LifeModule.INSIGHTS.name))
            else -> if (startAt?.startsWith("txn:") == true) {
                nav.switchTab(MoneyRoute)
                vm.openTransaction(startAt.removePrefix("txn:"))
            }
        }
    }

    Scaffold(
        containerColor = BuddyColors.Background,
        bottomBar = {
            NavigationBar(containerColor = BuddyColors.Surface, tonalElevation = 0.dp) {
                tabs.forEach { tab ->
                    val selected = backStack?.destination?.hierarchy?.any { it.hasRoute(tab.type) } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { nav.switchTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BuddyColors.OnLime,
                            indicatorColor = BuddyColors.Lime,
                            selectedTextColor = BuddyColors.TextPrimary,
                            unselectedIconColor = BuddyColors.TextSecondary,
                            unselectedTextColor = BuddyColors.TextSecondary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = TodayRoute,
            modifier = Modifier.padding(padding),
            // Short slide + fade (no zoom): forward slides in from the right, back slides out to the right.
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 10 } },
            exitTransition = { fadeOut(tween(150)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(150)) + slideOutHorizontally(tween(260)) { it / 10 } },
        ) {
            composable<TodayRoute> {
                TodayScreen(
                    vm, hvm, lvm,
                    onOpenMoney = { nav.switchTab(MoneyRoute) },
                    onOpenBuddy = { nav.switchTab(BuddyRoute) },
                    onOpenChat = { nav.navigate(ChatRoute) },
                    onOpenTasks = { nav.switchTab(TasksRoute) },
                    onOpenHealth = { nav.switchTab(HealthRoute) },
                    onOpenModule = { nav.navigate(LifeRoute(it.name)) },
                )
            }
            composable<ChatRoute> { ChatScreen(vm, onBack = { nav.popBackStack() }) }
            composable<MoneyRoute> {
                MoneyScreen(vm, lvm, onOpenAccounts = { nav.navigate(AccountsRoute) }, onOpenModule = { nav.navigate(LifeRoute(it.name)) })
            }
            composable<AccountsRoute> { AccountsScreen(vm, onBack = { nav.popBackStack() }) }
            composable<HealthRoute> {
                HealthScreen(
                    vm, hvm,
                    onStartRun = { nav.navigate(RunRoute) },
                    onOpenScreenTime = { nav.navigate(ScreenTimeRoute) },
                    onOpenSteps = { nav.navigate(StepsRoute(it.toEpochDay())) },
                    onOpenBody = { nav.navigate(BodyRoute) },
                    onOpenRuns = { nav.navigate(RunsRoute) },
                    onOpenRun = { nav.navigate(RunDetailRoute(it)) },
                    onOpenWorkouts = { nav.navigate(WorkoutsRoute) },
                    onOpenCoach = { nav.navigate(CoachRoute) },
                    onOpenWellbeing = { nav.navigate(WellbeingRoute(it)) },
                    onStartWorkout = { nav.navigate(WorkoutLoggerRoute) { launchSingleTop = true } },
                    onOpenWorkout = { nav.navigate(WorkoutDetailRoute(it)) },
                )
            }
            composable<BodyRoute> { com.shrey.buddy.ui.health.BodyScreen(hvm, onBack = { nav.popBackStack() }) }
            composable<StepsRoute> { entry ->
                val kg by hvm.latestKg.collectAsStateWithLifecycle()
                val profile by hvm.profile.collectAsStateWithLifecycle()
                com.shrey.buddy.ui.health.StepsScreen(
                    vm, java.time.LocalDate.ofEpochDay(entry.toRoute<StepsRoute>().epochDay), onBack = { nav.popBackStack() },
                    weightKg = kg, heightCm = profile.heightCm,
                )
            }
            composable<ScreenTimeRoute> { com.shrey.buddy.ui.health.ScreenTimeScreen(onBack = { nav.popBackStack() }) }
            composable<RunRoute> {
                RunScreen(
                    hvm,
                    onDone = { nav.popBackStack() },
                    // After saving, replace the recorder with the run's detail page.
                    onSaved = { id -> nav.navigate(RunDetailRoute(id)) { popUpTo<RunRoute> { inclusive = true } } },
                )
            }
            composable<RunDetailRoute> { entry ->
                com.shrey.buddy.ui.health.RunDetailScreen(hvm, entry.toRoute<RunDetailRoute>().id, onBack = { nav.popBackStack() })
            }
            composable<WellbeingRoute> { entry ->
                com.shrey.buddy.ui.health.WellbeingScreen(hvm, entry.toRoute<WellbeingRoute>().kind, onBack = { nav.popBackStack() })
            }
            composable<CoachRoute> {
                val goal by vm.stepGoal.collectAsStateWithLifecycle()
                com.shrey.buddy.ui.health.CoachScreen(
                    hvm, goal,
                    onBack = { nav.popBackStack() },
                    onStartWorkout = { nav.navigate(WorkoutLoggerRoute) { launchSingleTop = true } },
                    onStartRun = { nav.navigate(RunRoute) },
                )
            }
            composable<WorkoutsRoute> {
                com.shrey.buddy.ui.health.WorkoutsScreen(
                    hvm,
                    onBack = { nav.popBackStack() },
                    onStartLogger = { nav.navigate(WorkoutLoggerRoute) { launchSingleTop = true } },
                    onOpen = { nav.navigate(WorkoutDetailRoute(it)) },
                )
            }
            composable<WorkoutLoggerRoute> {
                com.shrey.buddy.ui.health.WorkoutLoggerScreen(
                    hvm,
                    onBack = { nav.popBackStack() },
                    onSaved = { id -> nav.navigate(WorkoutDetailRoute(id)) { popUpTo<WorkoutLoggerRoute> { inclusive = true } } },
                )
            }
            composable<WorkoutDetailRoute> { entry ->
                com.shrey.buddy.ui.health.WorkoutDetailScreen(hvm, entry.toRoute<WorkoutDetailRoute>().id, onBack = { nav.popBackStack() })
            }
            composable<RunsRoute> {
                com.shrey.buddy.ui.health.RunsScreen(
                    hvm,
                    onBack = { nav.popBackStack() },
                    onStart = { nav.navigate(RunRoute) },
                    onOpen = { nav.navigate(RunDetailRoute(it)) },
                )
            }
            composable<TasksRoute> { TasksScreen(vm, onOpenPlan = { nav.navigate(PlanRoute(it)) }) }
            composable<PlanRoute> { entry ->
                PlanBoardScreen(vm, entry.toRoute<PlanRoute>().planId, onBack = { nav.popBackStack() })
            }
            composable<BuddyRoute> {
                BuddyScreen(
                    vm,
                    onOpenChat = { nav.navigate(ChatRoute) },
                    onOpenModule = { nav.navigate(LifeRoute(it.name)) },
                    onOpenAccounts = { nav.navigate(AccountsRoute) },
                )
            }
            composable<LifeRoute> { entry ->
                val module = runCatching { LifeModule.valueOf(entry.toRoute<LifeRoute>().module) }.getOrDefault(LifeModule.NOTES)
                LifeModuleScreen(module, lvm, vm, onBack = { nav.popBackStack() })
            }
        }
    }
}
