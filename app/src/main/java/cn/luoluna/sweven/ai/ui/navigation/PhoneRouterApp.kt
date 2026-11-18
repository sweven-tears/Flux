package cn.luoluna.sweven.ai.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.luoluna.sweven.ai.ui.chat.ChatViewModel
import cn.luoluna.sweven.ai.tools.ToolApproval
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.luoluna.sweven.ai.ui.chat.ChatHistoryScreen
import cn.luoluna.sweven.ai.ui.chat.ChatScreen
import cn.luoluna.sweven.ai.ui.chat.FreeCopyScreen
import cn.luoluna.sweven.ai.ui.chat.style.ChatStyleScreen
import cn.luoluna.sweven.ai.ui.common.InAppBrowserPayload
import cn.luoluna.sweven.ai.ui.common.InAppBrowserScreen
import cn.luoluna.sweven.ai.ui.common.LocalInAppBrowser
import cn.luoluna.sweven.ai.ui.lan.AuthDevicesScreen
import cn.luoluna.sweven.ai.ui.lan.LanScreen
import cn.luoluna.sweven.ai.ui.lan.ModelAbnormalScreen
import cn.luoluna.sweven.ai.ui.lan.RequestLogScreen
import cn.luoluna.sweven.ai.ui.provider.AddProviderScreen
import cn.luoluna.sweven.ai.ui.provider.BalanceScreen
import cn.luoluna.sweven.ai.ui.provider.ProviderDetailScreen
import cn.luoluna.sweven.ai.ui.provider.ProviderListScreen
import cn.luoluna.sweven.ai.ui.provider.SmartRouteScreen
import cn.luoluna.sweven.ai.ui.settings.CreationsScreen
import cn.luoluna.sweven.ai.ui.settings.NetworkLogDetailScreen
import cn.luoluna.sweven.ai.ui.settings.NetworkLogScreen
import cn.luoluna.sweven.ai.ui.settings.SettingsScreen
import cn.luoluna.sweven.ai.ui.tools.CustomToolEditorScreen
import cn.luoluna.sweven.ai.ui.tools.NEW_TOOL_ID
import cn.luoluna.sweven.ai.ui.tools.ToolsCenterScreen

object Routes {
    const val CHAT = "chat"
    const val PROVIDERS = "providers"
    const val ADD_PROVIDER = "add_provider"
    const val PROVIDER_DETAIL = "provider/{id}"
    const val PROVIDER_BALANCE = "provider_balance/{id}"
    const val SMART_ROUTE = "smart_route"
    const val REQUEST_LOG = "request_log"
    const val AUTH_DEVICES = "auth_devices"
    const val MODEL_ABNORMAL = "model_abnormal"
    const val LAN = "lan"
    const val SETTINGS = "settings"
    const val CREATIONS = "creations"
    const val NETWORK_LOG = "network_log"
    const val NETWORK_LOG_DETAIL = "network_log_detail/{id}"
    const val FREE_COPY = "free_copy/{id}"
    const val CHAT_HISTORY = "chat_history/{conversationId}"
    const val CHAT_STYLE = "chat_style"
    const val IN_APP_BROWSER = "in_app_browser"
    const val TOOLS_CENTER = "tools_center"
    const val CUSTOM_TOOL_EDITOR = "custom_tool_editor/{toolId}"
}

private val TOP_ROUTES = setOf(Routes.CHAT, Routes.PROVIDERS, Routes.LAN)

@Composable
fun PhoneRouterApp(
    openLanTick: Int,
    openChatTick: Int = 0,
    openChatConversation: Long? = null,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 通知点击要打开的会话，ChatScreen 消费后置空
    var pendingChatConversation by remember { mutableStateOf<Long?>(null) }
    // 内置浏览器页载荷：HTML/URL 体积大且不可序列化，不能进路由参数，导航前暂存于此
    var browserPayload by remember { mutableStateOf<InAppBrowserPayload?>(null) }

    // remember 保住引用稳定，避免 LocalInAppBrowser 值变化引发全树重组
    val openInAppBrowser = remember<(InAppBrowserPayload) -> Unit> {
        { payload ->
            browserPayload = payload
            navController.navigate(Routes.IN_APP_BROWSER) { launchSingleTop = true }
        }
    }

    LaunchedEffect(openLanTick) {
        if (openLanTick > 0) {
            navController.navigate(Routes.LAN) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    /**
     * 打开指定会话的聊天页：先注入 pending id，ChatScreen 消费后选中该会话。
     *
     * 注意不能用 saveState/restoreState：popUpTo(起点) 会把起点之上的页面（设置、我的创作）
     * 一起存进 saved state，restoreState 又把它们整套恢复回来，结果「后台已跳转、界面还停在原页」。
     * 这里直接清空起点之上的页面，保证界面一定回到聊天页。
     */
    fun openChat(conversationId: Long?) {
        pendingChatConversation = conversationId
        navController.navigate(Routes.CHAT) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = false
                inclusive = false
            }
            launchSingleTop = true
            restoreState = false
        }
    }

    LaunchedEffect(openChatTick) {
        if (openChatTick > 0) openChat(openChatConversation)
    }

    fun gotoTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    CompositionLocalProvider(LocalInAppBrowser provides openInAppBrowser) {
        Scaffold(
            // 顶栏 insets 由各页面自己的 Scaffold/TopAppBar 处理，外层不再叠加，避免顶部双重空白；
            // bottom union ime 覆盖无 bottomBar 的路由（设置/添加厂商等）键盘弹出时内容贴键盘顶
            contentWindowInsets = WindowInsets(0, 0, 0, 0).union(WindowInsets.ime),
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
                // padding 后消费已用掉的 insets：下游 imePadding 自动扣除 bottomBar 部分，
                // 键盘弹出时输入面板精确贴键盘顶（否则 bottomBar 高度成为键盘上方空白）
                modifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding),
            ) {
                composable(Routes.CHAT) {
                    val chatVm: ChatViewModel = viewModel()
                    // 监听真实导航事件：离开时同步封闭 owner，后台延迟调用也不能再入队。
                    // Compose 临时销毁只移除监听器；配置重建不误拒绝审批。
                    DisposableEffect(navController, chatVm) {
                        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
                            if (destination.route == Routes.CHAT) ToolApproval.activateOwner(chatVm.toolApprovalOwner)
                            else ToolApproval.deactivateOwner(chatVm.toolApprovalOwner)
                        }
                        navController.addOnDestinationChangedListener(listener)
                        onDispose { navController.removeOnDestinationChangedListener(listener) }
                    }
                    ChatScreen(
                        vm = chatVm,
                        onGoProviders = { gotoTab(Routes.PROVIDERS) },
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                        onOpenFreeCopy = { id -> navController.navigate("free_copy/$id") },
                        onOpenChatHistory = { id -> navController.navigate("chat_history/$id") },
                        onOpenToolsCenter = { navController.navigate(Routes.TOOLS_CENTER) },
                        onOpenChatStyle = { navController.navigate(Routes.CHAT_STYLE) },
                        pendingConversationId = pendingChatConversation,
                        onConsumePendingConversation = { pendingChatConversation = null },
                        isChatRouteActive = currentRoute == Routes.CHAT,
                    )
                }
                composable(Routes.CHAT_STYLE) {
                    ChatStyleScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.TOOLS_CENTER) {
                    ToolsCenterScreen(
                        onBack = { navController.popBackStack() },
                        onEditTool = { id -> navController.navigate("custom_tool_editor/$id") },
                    )
                }
                composable(
                    Routes.CUSTOM_TOOL_EDITOR,
                    arguments = listOf(navArgument("toolId") { type = NavType.LongType }),
                ) { entry ->
                    CustomToolEditorScreen(
                        toolId = entry.arguments?.getLong("toolId") ?: NEW_TOOL_ID,
                        onBack = { navController.popBackStack() },
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
                        onOpenBalance = { navController.navigate("provider_balance/$id") },
                    )
                }
                composable(
                    Routes.PROVIDER_BALANCE,
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    BalanceScreen(
                        id = id,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.LAN) {
                    LanScreen(
                        onSmartRoute = { navController.navigate(Routes.SMART_ROUTE) },
                        onModelAbnormal = { navController.navigate(Routes.MODEL_ABNORMAL) },
                        onAuthDevices = { navController.navigate(Routes.AUTH_DEVICES) },
                    )
                }
                composable(Routes.REQUEST_LOG) {
                    RequestLogScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.AUTH_DEVICES) {
                    AuthDevicesScreen(
                        onBack = { navController.popBackStack() },
                        onRequestLog = { navController.navigate(Routes.REQUEST_LOG) },
                    )
                }
                composable(Routes.MODEL_ABNORMAL) {
                    ModelAbnormalScreen(
                        onBack = { navController.popBackStack() },
                        onOpenProvider = { id -> navController.navigate("provider/$id") },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenCreations = { navController.navigate(Routes.CREATIONS) },
                        onOpenNetworkLog = { navController.navigate(Routes.NETWORK_LOG) },
                    )
                }
                composable(Routes.NETWORK_LOG) {
                    NetworkLogScreen(
                        onBack = { navController.popBackStack() },
                        onOpenDetail = { id -> navController.navigate("network_log_detail/$id") },
                    )
                }
                composable(
                    Routes.NETWORK_LOG_DETAIL,
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    NetworkLogDetailScreen(
                        id = id,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    Routes.FREE_COPY,
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    FreeCopyScreen(
                        id = id,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    Routes.CHAT_HISTORY,
                    arguments = listOf(navArgument("conversationId") { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong("conversationId") ?: 0L
                    ChatHistoryScreen(
                        conversationId = id,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.IN_APP_BROWSER) {
                    InAppBrowserScreen(
                        payload = browserPayload,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.CREATIONS) {
                    CreationsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenChat = { openChat(it) },
                    )
                }
            }
        }
    }
}
