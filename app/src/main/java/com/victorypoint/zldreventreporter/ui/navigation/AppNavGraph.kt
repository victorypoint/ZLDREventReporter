package com.victorypoint.zldreventreporter.ui.navigation

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.victorypoint.zldreventreporter.ZldrReporterApplication
import com.victorypoint.zldreventreporter.ui.BatteryOptimizationDialog
import com.victorypoint.zldreventreporter.ui.login.LoginScreen
import com.victorypoint.zldreventreporter.ui.login.LoginViewModel
import com.victorypoint.zldreventreporter.ui.report.EventDetailScreen
import com.victorypoint.zldreventreporter.ui.report.EventDetailViewModel
import com.victorypoint.zldreventreporter.ui.report.EventPeriodDetailScreen
import com.victorypoint.zldreventreporter.ui.report.EventPeriodDetailViewModel
import com.victorypoint.zldreventreporter.ui.report.ParticipantDetailScreen
import com.victorypoint.zldreventreporter.ui.report.ParticipantDetailViewModel
import com.victorypoint.zldreventreporter.ui.report.ReportViewModel
import com.victorypoint.zldreventreporter.ui.report.ReportsScreen
import com.victorypoint.zldreventreporter.ui.settings.SettingsScreen
import com.victorypoint.zldreventreporter.ui.settings.SettingsViewModel

private const val ROUTE_LOGIN         = "login"
private const val ROUTE_REPORTS       = "reports"
private const val ROUTE_DETAIL        = "detail/{fromMillis}/{toMillis}/{sport}"
private const val ROUTE_EVENT_DETAIL  = "event_detail/{eventId}"
private const val ROUTE_PARTICIPANTS  = "participants/{eventId}"
private const val ROUTE_SETTINGS      = "settings"

@Composable
fun AppNavGraph(app: ZldrReporterApplication) {
    val navController = rememberNavController()
    val startDestination = if (app.tokenStore.hasRefreshToken()) ROUTE_REPORTS else ROUTE_LOGIN

    LaunchedEffect(Unit) {
        app.authRepository.sessionExpired.collect {
            navController.navigate(ROUTE_LOGIN) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {

        composable(ROUTE_LOGIN) {
            val vm: LoginViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return LoginViewModel(app.authRepository) as T
                }
            })
            LoginScreen(
                viewModel = vm,
                onLoginSuccess = {
                    navController.navigate(ROUTE_REPORTS) {
                        popUpTo(ROUTE_LOGIN) { inclusive = true }
                    }
                },
            )
        }

        composable(ROUTE_REPORTS) {
            val context = LocalContext.current
            val pm = context.getSystemService(PowerManager::class.java)
            var showBatteryDialog by remember {
                mutableStateOf(
                    !app.syncMetadataStore.batteryPromptShown &&
                    !pm.isIgnoringBatteryOptimizations(context.packageName)
                )
            }

            if (showBatteryDialog) {
                BatteryOptimizationDialog(
                    onConfirm = {
                        app.syncMetadataStore.batteryPromptShown = true
                        showBatteryDialog = false
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        )
                    },
                    onDismiss = {
                        app.syncMetadataStore.batteryPromptShown = true
                        showBatteryDialog = false
                    },
                )
            }

            val vm: ReportViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return ReportViewModel(
                        app.eventStatsRepository,
                        app.eventSyncRepository,
                        app.syncMetadataStore,
                    ) as T
                }
            })
            ReportsScreen(
                viewModel = vm,
                onSettingsClick    = { navController.navigate(ROUTE_SETTINGS) },
                onEventClick       = { eventId -> navController.navigate("event_detail/$eventId") },
                onEventDoubleClick = { eventId -> navController.navigate("participants/$eventId") },
            )
        }

        composable(
            route = ROUTE_DETAIL,
            arguments = listOf(
                navArgument("fromMillis") { type = NavType.LongType },
                navArgument("toMillis")   { type = NavType.LongType },
                navArgument("sport")      { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val fromMillis = backStackEntry.arguments!!.getLong("fromMillis")
            val toMillis   = backStackEntry.arguments!!.getLong("toMillis")
            val sport      = backStackEntry.arguments!!.getString("sport") ?: "ALL"
            val vm: EventPeriodDetailViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return EventPeriodDetailViewModel(app.eventStatsRepository, fromMillis, toMillis, sport) as T
                }
            })
            EventPeriodDetailScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(
            route = ROUTE_EVENT_DETAIL,
            arguments = listOf(navArgument("eventId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments!!.getLong("eventId")
            val vm: EventDetailViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return EventDetailViewModel(
                        app.eventsApi,
                        app.eventStatsRepository,
                        app.eventSyncRepository,
                        eventId,
                    ) as T
                }
            })
            EventDetailScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(
            route = ROUTE_PARTICIPANTS,
            arguments = listOf(navArgument("eventId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val eventId = backStackEntry.arguments!!.getLong("eventId")
            val vm: ParticipantDetailViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return ParticipantDetailViewModel(app.eventsApi, app.eventStatsRepository, eventId) as T
                }
            })
            ParticipantDetailScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(ROUTE_SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return SettingsViewModel(
                        app,
                        app.authRepository,
                        app.eventStatsRepository,
                        app.syncMetadataStore,
                    ) as T
                }
            })
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(ROUTE_LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
    }
}
