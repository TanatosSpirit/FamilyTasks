package com.familytasks.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.familytasks.app.core.PreferencesManager
import com.familytasks.app.ui.onboarding.OnboardingScreen
import com.familytasks.app.ui.onboarding.QrCodeScreen
import com.familytasks.app.ui.logs.LogsScreen
import com.familytasks.app.ui.settings.SettingsScreen
import com.familytasks.app.ui.tasks.CreateTaskScreen
import com.familytasks.app.ui.tasks.TaskDetailScreen
import com.familytasks.app.ui.tasks.TaskListScreen

@Composable
fun NavGraph(prefs: PreferencesManager) {
    val navController = rememberNavController()
    val start = if (prefs.isOnboardingComplete) Screen.TaskList.route else Screen.Onboarding.route

    NavHost(navController = navController, startDestination = start) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Screen.QrCode.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.QrCode.route) {
            QrCodeScreen(
                onDone = {
                    navController.navigate(Screen.TaskList.route) {
                        popUpTo(Screen.QrCode.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Screen.TaskList.route) {
            TaskListScreen(
                onTaskClick = { taskId ->
                    navController.navigate(Screen.TaskDetail().createRoute(taskId))
                },
                onCreateTask = { navController.navigate(Screen.CreateTask.route) },
                onSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(
            route = Screen.TaskDetail().route,
            arguments = listOf(navArgument("taskId") { type = NavType.StringType })
        ) { backStack ->
            val taskId = backStack.arguments?.getString("taskId") ?: return@composable
            TaskDetailScreen(
                taskId = taskId,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.CreateTask.route) {
            CreateTaskScreen(onBack = { navController.popBackStack() })
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onPairDevice = { navController.navigate(Screen.QrCode.route) },
                onLogs = { navController.navigate(Screen.Logs.route) }
            )
        }
        composable(Screen.Logs.route) {
            LogsScreen(onBack = { navController.popBackStack() })
        }
    }
}
