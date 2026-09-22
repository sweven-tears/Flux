package cn.luoluna.sweven.ai.ui.chat

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.server.Notifications
import cn.luoluna.sweven.ai.data.db.ConversationEntity
import cn.luoluna.sweven.ai.data.db.FolderEntity
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.PresetEntity
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.data.repo.RouteGroupTarget
import cn.luoluna.sweven.ai.data.repo.SmartAi
import cn.luoluna.sweven.ai.net.ThinkingDepth
import cn.luoluna.sweven.ai.ui.chat.plugin.ChatUiDefaults
import cn.luoluna.sweven.ai.ui.chat.plugin.ChatUiEnv
import cn.luoluna.sweven.ai.ui.chat.plugin.ChatUiRegistry
import cn.luoluna.sweven.ai.ui.chat.plugin.PluginPickerDialog
import cn.luoluna.sweven.ai.ui.chat.plugin.activePlugin
import cn.luoluna.sweven.ai.ui.common.ConfirmDialog
import cn.luoluna.sweven.ai.ui.common.InAppBrowserPayload
import cn.luoluna.sweven.ai.ui.common.LocalInAppBrowser
import cn.luoluna.sweven.ai.ui.common.TextFieldDialog
import cn.luoluna.sweven.ai.ui.common.formatTokens
import cn.luoluna.sweven.ai.ui.common.openImageExternally
import cn.luoluna.sweven.ai.ui.common.openVideoExternally
import kotlinx.coroutines.launch

/**
 * 聊天页宿主骨架：统一持有状态、发送逻辑与全部对话框；
 * 渲染点按激活插件的槽位（未覆写回落 [ChatUiDefaults]）。
 * 插件只负责绘制，发送/停止/流式逻辑在任何插件下零风险。
 */
@Composable
fun ChatScreen(
    onGoProviders: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFreeCopy: (Long) -> Unit,
    /** 打开当前会话的聊天记录页（按会话 id） */
    onOpenChatHistory: (Long) -> Unit,
    /** 打开工具中心（工具选择半窗右上角「管理」） */
    onOpenToolsCenter: () -> Unit = {},
    pendingConversationId: Long? = null,
    onConsumePendingConversation: () -> Unit = {},
    vm: ChatViewModel = viewModel(),
) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    // 内置浏览器打开入口（CompositionLocal 由 PhoneRouterApp 提供），消息网页视图渲染等用
    val openInAppBrowser = LocalInAppBrowser.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val current by vm.current.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val pickerModels by vm.pickerModels.collectAsStateWithLifecycle()
    val allPickerModels by vm.allPickerModels.collectAsStateWithLifecycle()
    val currentTarget by vm.currentTarget.collectAsStateWithLifecycle()
    val smartAiAvailable by vm.smartAiAvailable.collectAsStateWithLifecycle()
    val currentPicker = (currentTarget as? ChatTarget.Single)?.picker
    // 消息模型解析表（含禁用模型）：历史气泡按消息记录的 providerId+modelName 显示当时回复的模型
    val modelsByKey = allPickerModels.associate { (it.provider.id to it.model.name) to it }
    val smartAiCapability by vm.smartAiCapability.collectAsStateWithLifecycle()
    // 路由分组：可选用列表（附声明能力，供选择器类型筛选）+ 当前分组能力（附件/思考按钮显隐依据）
    val pickerRouteGroups by vm.pickerRouteGroups.collectAsStateWithLifecycle()
    val currentGroup = (currentTarget as? ChatTarget.Group)?.group
    val currentGroupCapability by vm.currentGroupCapability.collectAsStateWithLifecycle()
    // SmartAi/分组 聚合目标没有单个模型可看，按钮可见性按启用候选/成员的能力并集判断
    val isSmartAi = currentTarget is ChatTarget.SmartAi
    val showReasoning = currentPicker?.model?.capabilityReasoning == true ||
        (isSmartAi && smartAiCapability.reasoning) ||
        (currentGroup != null && currentGroupCapability?.reasoning == true)
    val showImageInput = currentPicker?.model?.inputImage == true ||
        (isSmartAi && smartAiCapability.inputImage) ||
        (currentGroup != null && currentGroupCapability?.inputImage == true)
    // 文件打开方式（设置项）：媒体点击是否直接交系统应用查看
    val imageOpenExternal by app.settings.imageOpenExternal.collectAsStateWithLifecycle()
    val videoOpenExternal by app.settings.videoOpenExternal.collectAsStateWithLifecycle()
    // 会话记录的模型/目标已不可用、当前展示的是回退目标时为 true（顶栏提示）
    val modelFallback = current?.providerId?.let { pid ->
        val single = currentTarget as? ChatTarget.Single
        when {
            currentTarget == null -> true
            pid == SmartAi.PROVIDER_ID -> currentTarget !is ChatTarget.SmartAi
            pid == RouteGroupTarget.PROVIDER_ID ->
                (currentTarget as? ChatTarget.Group)?.group?.groupKey != current?.modelName
            single != null -> pid != single.picker.provider.id ||
                current?.modelName != single.picker.model.name
            else -> false
        }
    } ?: false
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val toolProgress by vm.toolProgress.collectAsStateWithLifecycle()
    val liveToolCalls by vm.liveToolCalls.collectAsStateWithLifecycle()
    val stopHint by vm.stopHint.collectAsStateWithLifecycle()
    val activeVideoJobs by vm.activeVideoJobs.collectAsStateWithLifecycle()
    val contextEstimate by vm.contextEstimate.collectAsStateWithLifecycle()
    val drawer by vm.drawerState.collectAsStateWithLifecycle()
    val thinkingDepth by vm.thinkingDepth.collectAsStateWithLifecycle()
    // 未设置(null) 视为自动挡；菜单勾选与图标状态都按生效档位判断
    val effectiveThinkingDepth = thinkingDepth ?: ThinkingDepth.AUTO
    // 本地工具：当前会话勾选集合（半窗勾选/角标）与当前目标是否支持工具调用
    val selectedToolNames by vm.selectedToolNames.collectAsStateWithLifecycle()
    val showTools = currentPicker?.let {
        it.model.capabilityTools && it.provider.protocol == cn.luoluna.sweven.ai.net.ProviderProtocol.OPENAI
    } == true

    var pickerOpen by remember { mutableStateOf(false) }
    var pluginPickerOpen by remember { mutableStateOf(false) }
    var toolPickerOpen by remember { mutableStateOf(false) }

    // 通知点击带回的会话跳转：切到目标会话并消费
    LaunchedEffect(pendingConversationId) {
        pendingConversationId?.let {
            vm.selectConversation(it)
            onConsumePendingConversation()
        }
    }

    // 页面可见性上报：离开页面且生成中 15 秒 → 通知提醒（ChatViewModel.syncAwayNotify）
    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.pageVisible.value = true
                Lifecycle.Event.ON_PAUSE -> vm.pageVisible.value = false
                else -> Unit
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    // 首次发消息时申请通知权限（回复提醒/视频完成通知依赖，每进程只问一次）
    var notifPermissionAsked by rememberSaveable { mutableStateOf(false) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    fun ensureNotifPermission() {
        if (notifPermissionAsked) return
        notifPermissionAsked = true
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.hasPermission(context)) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var attachments by remember { mutableStateOf(listOf<android.net.Uri>()) }
    var input by rememberSaveable { mutableStateOf("") }
    var detailMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var fullImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameFolderTarget by remember { mutableStateOf<FolderEntity?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<FolderEntity?>(null) }
    var presetDialog by remember { mutableStateOf<Pair<Long, PresetEntity?>?>(null) }
    var moveTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    // 顶栏点击标题 → 重命名当前会话
    var renameConversationOpen by remember { mutableStateOf(false) }
    // 抽屉当前选中的分组 tab：null = 「聊天」（默认分组）
    var selectedFolderTab by remember { mutableStateOf<Long?>(null) }
    // 消息「更多」相关弹层
    var actionMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var deleteMessageTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var systemPromptOpen by remember { mutableStateOf(false) }
    // 消息编辑态：正在编辑的原消息 + 保留的原图（已是 data URL，不再压缩）
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editAttachments by remember { mutableStateOf(listOf<String>()) }
    // 待发送的 data URL 附件（助手生图「添加到对话」等，已是 data URL，发送时不再压缩）
    var attachmentDataUrls by remember { mutableStateOf(listOf<String>()) }

    // 保存图片到相册（内部处理 API ≤ 28 的存储权限申请）
    val saveToGallery = rememberGallerySaver()

    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()

    // 界面风格插件：选择持久化在 AppSettings，未注册（外部插件被移除）回退默认
    val chatPluginId by app.settings.chatPluginId.collectAsStateWithLifecycle()
    val plugin = ChatUiRegistry.resolve(chatPluginId ?: ChatUiRegistry.DEFAULT_ID)

    // 用户资料（抽屉左上角头像/名字，消息气泡用户头像）
    val userName by app.settings.userName.collectAsStateWithLifecycle()
    val userAvatar by app.settings.userAvatar.collectAsStateWithLifecycle()
    var userProfileOpen by remember { mutableStateOf(false) }

    // 切换会话时退出编辑态，避免把编辑内容/待发附件发到别的会话
    LaunchedEffect(current?.id) {
        editingMessage = null
        editAttachments = emptyList()
        attachmentDataUrls = emptyList()
    }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris ->
        if (uris.isNotEmpty()) attachments = uris
    }

    // 用户头像选择：选图后压缩为 data URL 持久化
    var pendingAvatar by remember { mutableStateOf<String?>(null) }
    val pickAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                pendingAvatar = ChatImages.uriToDataUrl(context, uri, maxDim = 256, quality = 85)
            }
        }
    }

    val listState = rememberLazyListState()
    val extraItems = (if (streaming) 1 else 0) + (if (error != null) 1 else 0) +
        (if (notice != null) 1 else 0)
    // "是否贴底"只在用户自己滚动时采样：程序滚动（自动跟随）结束时 canScrollForward=false，
    // 会自动落回"贴底"。这样"用户上翻看历史"与"系统跟随输出"互不打架。
    var stickToBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (scrolling) stickToBottom = !canScrollForward
            }
    }
    // 新消息/错误/提示出现：只在贴底时跟随（平滑滚动，不瞬跳）；用户上翻时绝不抢滚动
    LaunchedEffect(messages.size, streaming, error, notice) {
        val count = messages.size + extraItems
        if (count > 0 && stickToBottom) listState.animateScrollToItem(index = count - 1)
    }

    // 上下文用量指示文本与级别（0 正常 / 1 接近 / 2 超出）
    val contextEstimateText = contextEstimate?.let { est ->
        if (est.usedTokens > 0) {
            est.window?.let { w ->
                "上下文 ≈${formatTokens(est.usedTokens.toLong())} / ${formatTokens(w.toLong())}"
            } ?: "上下文 ≈${formatTokens(est.usedTokens.toLong())}"
        } else null
    }
    val contextEstimateLevel = contextEstimate?.let { est ->
        val ratio = est.window?.let { w -> est.usedTokens.toFloat() / w }
        when {
            ratio != null && ratio >= 1f -> 2
            ratio != null && ratio >= 0.8f -> 1
            else -> 0
        }
    } ?: -1

    val env = ChatUiEnv(
        current = current,
        messages = messages,
        currentTarget = currentTarget,
        currentPicker = currentPicker,
        modelsByKey = modelsByKey,
        isSmartAi = isSmartAi,
        smartAiCapability = smartAiCapability,
        currentGroup = currentGroup,
        modelFallback = modelFallback,
        streaming = streaming,
        error = error,
        notice = notice,
        toolProgress = toolProgress,
        liveToolCalls = liveToolCalls,
        contextEstimateText = contextEstimateText,
        contextEstimateLevel = contextEstimateLevel,
        drawer = drawer,
        searchQuery = searchQuery,
        searchResults = searchResults,
        effectiveThinkingDepth = effectiveThinkingDepth,
        showReasoning = showReasoning,
        showImageInput = showImageInput,
        showTools = showTools,
        selectedToolCount = selectedToolNames.size,
        imageOpenExternal = imageOpenExternal,
        videoOpenExternal = videoOpenExternal,
        userName = userName,
        userAvatar = userAvatar,
        input = input,
        onInputChange = { input = it },
        attachments = attachments,
        editAttachments = editAttachments,
        attachmentDataUrls = attachmentDataUrls,
        editingMessage = editingMessage,
        selectedFolderTab = selectedFolderTab,
        onSelectFolderTab = { selectedFolderTab = it },
        vm = vm,
        drawerState = drawerState,
        scope = scope,
        listState = listState,
        stickToBottom = stickToBottom,
        onJumpToLatest = {
            stickToBottom = true
            scope.launch {
                listState.animateScrollToItem((messages.size + extraItems - 1).coerceAtLeast(0))
            }
        },
        pickImages = {
            pickImages.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        send = {
            ensureNotifPermission()
            focusManager.clearFocus()
            val editing = editingMessage
            val text = input
            val newUris = attachments
            val dataUrls = attachmentDataUrls
            input = ""
            attachments = emptyList()
            attachmentDataUrls = emptyList()
            if (editing != null) {
                val keepImages = editAttachments + dataUrls
                editAttachments = emptyList()
                editingMessage = null
                vm.editAndResend(editing, text, keepImages, newUris)
            } else {
                vm.send(text, newUris, dataUrls)
            }
        },
        removeAttachment = { attachments = attachments - it },
        removeEditAttachment = { editAttachments = editAttachments - it },
        removeAttachmentDataUrl = { attachmentDataUrls = attachmentDataUrls - it },
        cancelEditing = {
            editingMessage = null
            editAttachments = emptyList()
            input = ""
        },
        showDetail = { detailMessage = it },
        showImage = { fullImage = it },
        openModelPicker = { pickerOpen = true },
        openSystemPrompt = { systemPromptOpen = true },
        openPluginPicker = { pluginPickerOpen = true },
        openToolPicker = { toolPickerOpen = true },
        showMessageActions = { actionMessage = it },
        copyMessage = { msg ->
            ChatUiDefaults.copyText(context, msg.content)
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        },
        editConversationTitle = { renameConversationOpen = true },
        showNewFolder = { showNewFolder = true },
        renameFolder = { renameFolderTarget = it },
        deleteFolder = { deleteFolderTarget = it },
        editPreset = { folderId, existing -> presetDialog = folderId to existing },
        moveConversation = { moveTarget = it },
        editUserProfile = { userProfileOpen = true },
        onGoProviders = onGoProviders,
        onOpenSettings = onOpenSettings,
        onOpenHistory = onOpenChatHistory,
    )
    // 供默认槽位内部互调时回落到插件覆写（messageList 里的气泡、inputBar 里的附件行）
    env.activePlugin = plugin

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            (plugin.drawer ?: { ChatUiDefaults.Drawer(env) })(env)
        },
    ) {
        Scaffold(
            topBar = {
                (plugin.topBar ?: { ChatUiDefaults.TopBar(env) })(env)
            },
            // ime 由应用层 Scaffold 统一处理（内容底 = max(bottomBar, 键盘)），
            // 内层不再叠加，否则键盘上方多出 bottomBar 高度的空白
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // 双击空白处移除输入框焦点并收起键盘；气泡等子级已消费点击的区域不触发
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                keyboard?.hide()
                                focusManager.clearFocus()
                            })
                        },
                ) {
                    if (messages.isEmpty() && !streaming) {
                        Column(Modifier.fillMaxSize()) {
                            (plugin.emptyState ?: { ChatUiDefaults.EmptyState(env) })(env)
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            (plugin.messageList ?: { e, _ -> ChatUiDefaults.MessageList(e, padding) })(
                                env, padding,
                            )
                        }
                    }
                    // 停止按钮首次点击的居中提示，1.8 秒后自动消失（vm.stopHint 复位）
                    StopHintOverlay(
                        visible = stopHint,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    // 视频生成任务进行中：顶部常驻小角标（结果会以新消息+通知呈现）
                    androidx.compose.animation.AnimatedVisibility(
                        visible = activeVideoJobs > 0,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(
                                "🎬 视频生成中 · $activeVideoJobs",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                (plugin.inputBar ?: { ChatUiDefaults.InputBar(env) })(env)
            }
        }
    }

    if (pickerOpen) {
        ModelPickerSheet(
            onDismiss = { pickerOpen = false },
            models = pickerModels,
            selectedProviderId = currentPicker?.provider?.id,
            selectedModelName = currentPicker?.model?.name,
            onSelect = {
                vm.selectModel(it.provider.id, it.model.name)
                pickerOpen = false
            },
            onGoProviders = onGoProviders,
            smartAiAvailable = smartAiAvailable,
            smartAiSelected = currentTarget is ChatTarget.SmartAi,
            smartAiCapability = smartAiCapability,
            onSelectSmartAi = {
                vm.selectModel(SmartAi.PROVIDER_ID, SmartAi.MODEL_NAME)
                pickerOpen = false
            },
            routeGroups = pickerRouteGroups,
            selectedGroupKey = currentGroup?.groupKey,
            onSelectGroup = { group ->
                vm.selectModel(RouteGroupTarget.PROVIDER_ID, group.groupKey)
                pickerOpen = false
            },
        )
    }

    if (pluginPickerOpen) {
        PluginPickerDialog(
            currentId = plugin.id,
            onSelect = { app.settings.setChatPluginId(it) },
            onDismiss = { pluginPickerOpen = false },
        )
    }

    if (toolPickerOpen) {
        ToolPickerSheet(
            selected = selectedToolNames,
            onToggle = { name, on ->
                vm.setTools(if (on) selectedToolNames + name else selectedToolNames - name)
            },
            onManage = {
                toolPickerOpen = false
                onOpenToolsCenter()
            },
            onDismiss = { toolPickerOpen = false },
        )
    }

    if (renameConversationOpen) {
        current?.let { conv ->
            TextFieldDialog(
                title = "重命名对话",
                initial = conv.title,
                onDismiss = { renameConversationOpen = false },
                onConfirm = { name ->
                    vm.renameConversation(conv.id, name)
                    renameConversationOpen = false
                },
            )
        }
    }

    detailMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { detailMessage = null },
            confirmButton = {
                TextButton(onClick = {
                    ChatUiDefaults.copyText(context, msg.content)
                    detailMessage = null
                }) { Text("复制全文") }
            },
            dismissButton = {
                TextButton(onClick = { detailMessage = null }) { Text("关闭") }
            },
            title = { Text(if (msg.role == Role.USER) "用户消息" else "助手消息") },
            text = { Text(msg.content, maxLines = 12) },
        )
    }

    if (showNewFolder) {
        TextFieldDialog(
            title = "新建文件夹",
            initial = "",
            onDismiss = { showNewFolder = false },
            onConfirm = { name ->
                vm.createFolder(name)
                showNewFolder = false
            },
        )
    }
    renameFolderTarget?.let { folder ->
        TextFieldDialog(
            title = "重命名文件夹",
            initial = folder.name,
            onDismiss = { renameFolderTarget = null },
            onConfirm = { name ->
                vm.renameFolder(folder.id, name)
                renameFolderTarget = null
            },
        )
    }
    deleteFolderTarget?.let { folder ->
        ConfirmDialog(
            title = "删除文件夹",
            text = "「${folder.name}」内的对话将移回「聊天」，其中的预设对话将被删除。",
            onDismiss = { deleteFolderTarget = null },
            onConfirm = {
                vm.deleteFolder(folder.id)
                deleteFolderTarget = null
            },
        )
    }
    presetDialog?.let { (folderId, existing) ->
        PresetDialog(
            existing = existing,
            models = allPickerModels,
            onDismiss = { presetDialog = null },
            onSave = { name, sys, pid, mn ->
                vm.savePreset(folderId, existing, name, sys, pid, mn)
                presetDialog = null
            },
            onDelete = existing?.let { p -> ({ vm.deletePreset(p); presetDialog = null }) },
        )
    }
    if (systemPromptOpen) {
        SystemPromptDialog(
            initial = current?.systemPrompt.orEmpty(),
            onDismiss = { systemPromptOpen = false },
            onSave = { prompt ->
                vm.setSystemPrompt(prompt)
                systemPromptOpen = false
            },
        )
    }
    moveTarget?.let { conv ->
        var sel by remember(conv.id) { mutableStateOf(conv.folderId) }
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    vm.moveConversation(conv.id, sel)
                    moveTarget = null
                }) { Text("移动") }
            },
            dismissButton = { TextButton(onClick = { moveTarget = null }) { Text("取消") } },
            title = { Text("移动「${conv.title}」到…", maxLines = 2) },
            text = {
                Column {
                    MoveRadioRow("聊天", null, sel) { sel = null }
                    drawer.folders.forEach { f ->
                        MoveRadioRow(f.name, f.id, sel) { sel = f.id }
                    }
                }
            },
        )
    }

    fullImage?.let { bmp ->
        ImagePreviewDialog(bitmap = bmp, onDismiss = { fullImage = null })
    }

    actionMessage?.let { msg ->
        MessageActionSheet(
            message = msg,
            onDismiss = { actionMessage = null },
            actions = MessageActions(
                onFreeCopy = { onOpenFreeCopy(msg.id) },
                onWebRender = {
                    openInAppBrowser(InAppBrowserPayload(content = buildMessageHtml(msg.content)))
                },
                onEdit = if (msg.role == Role.USER) {
                    {
                        input = msg.content
                        editAttachments = msg.images
                        editingMessage = msg
                    }
                } else {
                    null
                },
                onBranch = { vm.branchConversation(msg) },
                onDelete = { deleteMessageTarget = msg },
                onDownload = { saveToGallery(msg.images) },
                // 打开方式…：视频走私有目录文件，图片走第一条 data URL；手动入口不受「文件打开方式」设置影响
                onOpenExternally = {
                    val videoPath = msg.images.firstOrNull { MessageEntity.isVideo(it) }
                        ?.removePrefix(MessageEntity.VIDEO_PREFIX)
                    if (videoPath != null) {
                        openVideoExternally(context, java.io.File(videoPath))
                    } else {
                        msg.images.firstOrNull { it.startsWith("data:") }
                            ?.let { openImageExternally(context, it) }
                    }
                },
                // 添加到对话：作为待发附件挂到输入区；编辑态下并入编辑附件
                onAttach = {
                    val real = msg.images.filter { it.startsWith("data:") }
                    if (real.isNotEmpty()) {
                        if (editingMessage != null) {
                            editAttachments = editAttachments + real
                        } else {
                            attachmentDataUrls = attachmentDataUrls + real
                        }
                        Toast.makeText(context, "已添加到输入区", Toast.LENGTH_SHORT).show()
                    }
                },
                attachEnabled = showImageInput,
            ),
        )
    }
    deleteMessageTarget?.let { msg ->
        DeleteMessageDialog(
            onDismiss = { deleteMessageTarget = null },
            onConfirm = {
                vm.deleteMessage(msg)
                deleteMessageTarget = null
            },
        )
    }

    if (userProfileOpen) {
        UserProfileDialog(
            initialName = userName.orEmpty(),
            currentAvatar = userAvatar,
            pendingAvatar = pendingAvatar,
            onPickAvatar = {
                pickAvatar.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onClearAvatar = { pendingAvatar = "" },
            onDismiss = {
                userProfileOpen = false
                pendingAvatar = null
            },
            onSave = { name ->
                app.settings.setUserName(name.ifBlank { null })
                // pendingAvatar：null=未改动；""=清除；否则为新 data URL
                pendingAvatar?.let { app.settings.setUserAvatar(it.ifBlank { null }) }
                userProfileOpen = false
                pendingAvatar = null
            },
        )
    }
}

/** 停止按钮首次点击的居中提示胶囊（独立函数以避开 Column 作用域的 AnimatedVisibility 重载） */
@Composable
private fun StopHintOverlay(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            shadowElevation = 6.dp,
        ) {
            Text(
                "再点一次停止输出",
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
    }
}

@Composable
private fun MoveRadioRow(label: String, folderId: Long?, selected: Long?, onSelect: (Long?) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(folderId) },
    ) {
        RadioButton(selected = selected == folderId, onClick = { onSelect(folderId) })
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
    }
}

/** 用户资料编辑：头像（选图/清除）+ 显示名 */
@Composable
private fun UserProfileDialog(
    initialName: String,
    currentAvatar: String?,
    pendingAvatar: String?,
    onPickAvatar: () -> Unit,
    onClearAvatar: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    // 展示头像：优先未保存的新选图，其次已持久化的，空串表示已清除
    val displayAvatar = when {
        pendingAvatar == "" -> null
        pendingAvatar != null -> pendingAvatar
        else -> currentAvatar
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(name) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("我的资料") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                UserAvatar(
                    name = name.ifBlank { null },
                    avatarDataUrl = displayAvatar,
                    size = 72.dp,
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onPickAvatar) { Text("更换头像") }
                    if (displayAvatar != null) {
                        TextButton(onClick = onClearAvatar) { Text("清除") }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("显示名") },
                    placeholder = { Text("未设置时显示「我」") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
    )
}

/** 当前会话的系统提示词（人设）查看与编辑：怀疑预设没生效时可在此确认 */
@Composable
private fun SystemPromptDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("系统提示词（人设）") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("为空表示没有人设；预设创建的会话会自动带上") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "怀疑预设没生效时，在此确认当前会话是否真的带了人设",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
    )
}
