package cn.luoluna.sweven.ai.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.luoluna.sweven.ai.ui.chat.ChatScreen
import cn.luoluna.sweven.ai.ui.lan.LanScreen
import cn.luoluna.sweven.ai.ui.lan.RequestLogScreen
import cn.luoluna.sweven.ai.ui.provider.AddProviderScreen
import cn.luoluna.sweven.ai.ui.provider.ProviderDetailScreen
import cn.luoluna.sweven.ai.ui.provider.ProviderListScreen
import cn.luoluna.sweven.ai.ui.provider.SmartRouteScreen
import cn.luoluna.sweven.ai.ui.settings.SettingsScreen

object Routes {
    const val CHAT = "chat"
    const val PROVIDERS = "providers"
    const val ADD_PROVIDER = "add_provider"
    const val PROVIDER_DETAIL = "provider/{id}"
    const val SMART_ROUTE = "smart_route"
    const val REQUEST_LOG = "request_log"
    const val LAN = "lan"
    const val SETTINGS = "settings"
}

private val TOP_ROUTES = setOf(Routes.CHAT, Routes.PROVIDERS, Routes.LAN)

@Composable
fun PhoneRouterApp(openLanTick: Int) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    LaunchedEffect(openLanTick) {
        if (openLanTick > 0) {
            navController.navigate(Routes.LAN) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    fun gotoTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        // 顶栏 insets 由各页面自己的 Scaffold/TopAppBar 处理，外层不再叠加，避免顶部双重空白
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (currentRoute in TOP_ROUTES) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.CHAT,
                        onClick = { gotoTab(Routes.CHAT) },
                        icon = { Icon(Icons.Filled.Face, contentDescription = null) },
                        label = { Text("聊天") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.PROVIDERS,
                        onClick = { gotoTab(Routes.PROVIDERS) },
                        icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                        label = { Text("厂商") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.LAN,
                        onClick = { gotoTab(Routes.LAN) },
                        icon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        label = { Text("局域网") },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CHAT,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.CHAT) {
                ChatScreen(
                    onGoProviders = { gotoTab(Routes.PROVIDERS) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.PROVIDERS) {
                ProviderListScreen(
                    onAdd = { navController.navigate(Routes.ADD_PROVIDER) },
                    onOpen = { id -> navController.navigate("provider/$id") },
                )
            }
            composable(Routes.SMART_ROUTE) {
                SmartRouteScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.ADD_PROVIDER) {
                AddProviderScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { id ->
                        navController.navigate("provider/$id") {
                            popUpTo(Routes.PROVIDERS)
                        }
                    },
                )
            }
            composable(
                Routes.PROVIDER_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                ProviderDetailScreen(
                    id = id,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.LAN) {
                LanScreen(
                    onSmartRoute = { navController.navigate(Routes.SMART_ROUTE) },
                    onRequestLog = { navController.navigate(Routes.REQUEST_LOG) },
                )
            }
            composable(Routes.REQUEST_LOG) {
                RequestLogScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
