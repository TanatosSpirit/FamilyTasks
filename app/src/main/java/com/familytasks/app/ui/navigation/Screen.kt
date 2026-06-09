package com.familytasks.app.ui.navigation

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object QrCode : Screen("qr_code")
    data object TaskList : Screen("task_list")
    data object CreateTask : Screen("create_task")
    data object Settings : Screen("settings")
    data class TaskDetail(val taskId: String = "{taskId}") : Screen("task_detail/{taskId}") {
        fun createRoute(id: String) = "task_detail/$id"
    }
    data object Logs : Screen("logs")
}
