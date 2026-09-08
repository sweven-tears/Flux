package cn.luoluna.sweven.ai.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.ConversationEntity
import cn.luoluna.sweven.ai.data.db.FolderEntity
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.PresetEntity
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.data.repo.SmartAi
import cn.luoluna.sweven.ai.net.ThinkingDepth
import cn.luoluna.sweven.ai.ui.chat.plugin.ChatUiDefaults
import cn.luoluna.sweven.ai.ui.chat.plugin.ChatUiEnv
import cn.luoluna.sweven.ai.ui.chat.plugin.ChatUiRegistry
import cn.luoluna.sweven.ai.ui.chat.plugin.PluginPickerDialog
import cn.luoluna.sweven.ai.ui.chat.plugin.activePlugin
import cn.luoluna.sweven.ai.ui.common.ConfirmDialog
import cn.luoluna.sweven.ai.ui.common.InAppBrowser
import cn.luoluna.sweven.ai.ui.common.TextFieldDialog
import cn.luoluna.sweven.ai.ui.common.formatTokens
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
    vm: ChatViewModel = viewModel(),
) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val current by vm.current.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val pickerModels by vm.pickerModels.collectAsStateWithLifecycle()
    val allPickerModels by vm.allPickerModels.collectAsStateWithLifecycle()
    val currentTarget by vm.currentTarget.collectAsStateWithLifecycle()
    val smartAiAvailable by vm.smartAiAvailable.collectAsStateWithLifecycle()
    val currentPicker = (currentTarget as? ChatTarget.Single)?.picker
    val smartAiCapability by vm.smartAiCapability.collectAsStateWithLifecycle()
    // SmartAi 聚合目标没有单个模型可看，按钮可见性按启用候选的能力并集判断
    val isSmartAi = currentTarget is ChatTarget.SmartAi
    val showReasoning = currentPicker?.model?.capabilityReasoning == true ||
        (isSmartAi && smartAiCapability.reasoning)
    val showImageInput = currentPicker?.model?.inputImage == true ||
        (isSmartAi && smartAiCapability.inputImage)
    // 会话记录的模型/目标已不可用、当前展示的是回退目标时为 true（顶栏提示）
    val modelFallback = current?.providerId?.let { pid ->
        val single = currentTarget as? ChatTarget.Single
        when {
            currentTarget == null -> true
            pid == SmartAi.PROVIDER_ID -> currentTarget !is ChatTarget.SmartAi
            single != null -> pid != single.picker.provider.id ||
                current?.modelName != single.picker.model.name
            else -> false
        }
    } ?: false
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val contextEstimate by vm.contextEstimate.collectAsStateWithLifecycle()
    val drawer by vm.drawerState.collectAsStateWithLifecycle()
    val thinkingDepth by vm.thinkingDepth.collectAsStateWithLifecycle()
    // 未设置(null) 视为自动挡；菜单勾选与图标状态都按生效档位判断
    val effectiveThinkingDepth = thinkingDepth ?: ThinkingDepth.AUTO

    var pickerOpen by remember { mutableStateOf(false) }
    var pluginPickerOpen by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(listOf<android.net.Uri>()) }
    var input by remember { mutableStateOf("") }
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
    var freeCopyMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var webRenderMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var deleteMessageTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var systemPromptOpen by remember { mutableStateOf(false) }
    // 消息编辑态：正在编辑的原消息 + 保留的原图（已是 data URL，不再压缩）
    var editingMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var editAttachments by remember { mutableStateOf(listOf<String>()) }

    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()

    // 界面风格插件：选择持久化在 AppSettings，未注册（外部插件被移除）回退默认
    val chatPluginId by app.settings.chatPluginId.collectAsStateWithLifecycle()
    val plugin = ChatUiRegistry.resolve(chatPluginId ?: ChatUiRegistry.DEFAULT_ID)

    // 用户资料（抽屉左上角头像/名字，消息气泡用户头像）
    val userName by app.settings.userName.collectAsStateWithLifecycle()
    val userAvatar by app.settings.userAvatar.collectAsStateWithLifecycle()
    var userProfileOpen by remember { mutableStateOf(false) }

    // 切换会话时退出编辑态，避免把编辑内容发到别的会话
    LaunchedEffect(current?.id) {
        editingMessage = null
        editAttachments = emptyList()
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
    // 仅在新消息/错误/提示出现时滚动一次；流式内容的跟随滚动在流式气泡内部处理，
    // 避免流式文本每次变化都触发整个页面重组
    LaunchedEffect(messages.size, streaming, error, notice) {
        val count = messages.size + extraItems
        if (count > 0) listState.scrollToItem(index = count - 1)
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
        isSmartAi = isSmartAi,
        smartAiCapability = smartAiCapability,
        modelFallback = modelFallback,
        streaming = streaming,
        error = error,
        notice = notice,
        contextEstimateText = contextEstimateText,
        contextEstimateLevel = contextEstimateLevel,
        drawer = drawer,
        searchQuery = searchQuery,
        searchResults = searchResults,
        effectiveThinkingDepth = effectiveThinkingDepth,
        showReasoning = showReasoning,
        showImageInput = showImageInput,
        userName = userName,
        userAvatar = userAvatar,
        input = input,
        onInputChange = { input = it },
        attachments = attachments,
        editAttachments = editAttachments,
        editingMessage = editingMessage,
        selectedFolderTab = selectedFolderTab,
        onSelectFolderTab = { selectedFolderTab = it },
        vm = vm,
        drawerState = drawerState,
        scope = scope,
        listState = listState,
        pickImages = {
            pickImages.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        send = {
            focusManager.clearFocus()
            val editing = editingMessage
            val text = input
            val newUris = attachments
            input = ""
            attachments = emptyList()
            if (editing != null) {
                val keepImages = editAttachments
                editAttachments = emptyList()
                editingMessage = null
                vm.editAndResend(editing, text, keepImages, newUris)
            } else {
                vm.send(text, newUris)
            }
        },
        removeAttachment = { attachments = attachments - it },
        removeEditAttachment = { editAttachments = editAttachments - it },
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
                    .padding(padding),
            ) {
                if (messages.isEmpty() && !streaming) {
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        (plugin.emptyState ?: { ChatUiDefaults.EmptyState(env) })(env)
                    }
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth()) {
                        (plugin.messageList ?: { e, _ -> ChatUiDefaults.MessageList(e, padding) })(
                            env, padding,
                        )
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
        )
    }

    if (pluginPickerOpen) {
        PluginPickerDialog(
            currentId = plugin.id,
            onSelect = { app.settings.setChatPluginId(it) },
            onDismiss = { pluginPickerOpen = false },
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
                onFreeCopy = { freeCopyMessage = msg },
                onWebRender = { webRenderMessage = msg },
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
            ),
        )
    }
    freeCopyMessage?.let { msg ->
        FullScreenCopyDialog(message = msg, onDismiss = { freeCopyMessage = null })
    }
    webRenderMessage?.let { msg ->
        InAppBrowser(content = buildMessageHtml(msg.content), onDismiss = { webRenderMessage = null })
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
