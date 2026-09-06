package cn.luoluna.sweven.ai.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.luoluna.sweven.ai.data.db.ConversationEntity
import cn.luoluna.sweven.ai.data.db.FolderEntity
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.PresetEntity
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.net.ThinkingDepth
import cn.luoluna.sweven.ai.ui.common.ConfirmDialog
import cn.luoluna.sweven.ai.ui.common.InAppBrowser
import cn.luoluna.sweven.ai.ui.common.TextFieldDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    onGoProviders: () -> Unit,
    vm: ChatViewModel = viewModel(),
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val current by vm.current.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val pickerModels by vm.pickerModels.collectAsStateWithLifecycle()
    val currentModel by vm.currentModel.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val drawer by vm.drawerState.collectAsStateWithLifecycle()
    val thinkingDepth by vm.thinkingDepth.collectAsStateWithLifecycle()

    var pickerOpen by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(listOf<android.net.Uri>()) }
    var input by remember { mutableStateOf("") }
    var detailMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var fullImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameFolderTarget by remember { mutableStateOf<FolderEntity?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<FolderEntity?>(null) }
    var presetDialog by remember { mutableStateOf<Pair<Long, PresetEntity?>?>(null) }
    var moveTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    // 抽屉当前选中的分组 tab：null = 「聊天」（默认分组）
    var selectedFolderTab by remember { mutableStateOf<Long?>(null) }
    // 消息「更多」相关弹层
    var actionMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var freeCopyMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var webRenderMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var deleteMessageTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var thinkMenuOpen by remember { mutableStateOf(false) }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris ->
        if (uris.isNotEmpty()) attachments = uris
    }

    val listState = rememberLazyListState()
    val extraItems = (if (streaming) 1 else 0) + (if (error != null) 1 else 0)
    // 仅在新消息/错误出现时滚动一次；流式内容的跟随滚动在 StreamingItem 内部处理，
    // 避免流式文本每次变化都触发整个页面重组
    LaunchedEffect(messages.size, streaming, error) {
        val count = messages.size + extraItems
        if (count > 0) listState.scrollToItem(index = count - 1)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp),
                ) {
                    Text("对话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showNewFolder = true }) {
                        Text("新建文件夹", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = {
                        vm.newConversation()
                        scope.launch { drawerState.close() }
                    }) { Icon(Icons.Filled.Add, contentDescription = "新建对话") }
                }
                // 分组横向 tab：「聊天」（默认分组）+ 各文件夹
                LaunchedEffect(drawer.folders, selectedFolderTab) {
                    if (selectedFolderTab != null && drawer.folders.none { it.id == selectedFolderTab }) {
                        selectedFolderTab = null
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    val defaultConvs = drawer.conversationsByFolder[null].orEmpty()
                    FolderTab(
                        label = "聊天",
                        icon = Icons.AutoMirrored.Filled.Chat,
                        count = defaultConvs.size,
                        selected = selectedFolderTab == null,
                        onClick = { selectedFolderTab = null },
                    )
                    drawer.folders.forEach { folder ->
                        FolderTab(
                            label = folder.name,
                            icon = Icons.Filled.Folder,
                            count = drawer.conversationsByFolder[folder.id].orEmpty().size +
                                drawer.presetsByFolder[folder.id].orEmpty().size,
                            selected = selectedFolderTab == folder.id,
                            onClick = { selectedFolderTab = folder.id },
                        )
                    }
                }
                HorizontalDivider()
                // 当前分组内容：文件夹 tab 先列预设对话，再列普通会话
                var folderMenuOpen by remember { mutableStateOf(false) }
                selectedFolderTab?.let { folderId ->
                    drawer.folders.firstOrNull { it.id == folderId }?.let { folder ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp),
                        ) {
                            Text(
                                folder.name,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            TextButton(onClick = { presetDialog = folder.id to null }) {
                                Text("新建预设", style = MaterialTheme.typography.labelMedium)
                            }
                            Box {
                                IconButton(onClick = { folderMenuOpen = true }) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = "文件夹菜单",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                DropdownMenu(
                                    expanded = folderMenuOpen,
                                    onDismissRequest = { folderMenuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("重命名文件夹") },
                                        onClick = {
                                            folderMenuOpen = false
                                            renameFolderTarget = folder
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除文件夹") },
                                        onClick = {
                                            folderMenuOpen = false
                                            deleteFolderTarget = folder
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    val tabId = selectedFolderTab
                    val tabConvs = drawer.conversationsByFolder[tabId].orEmpty()
                    if (tabId != null) {
                        items(drawer.presetsByFolder[tabId].orEmpty(), key = { "preset-${it.id}" }) { preset ->
                            PresetDrawerItem(
                                preset = preset,
                                onUse = {
                                    vm.startFromPreset(preset)
                                    scope.launch { drawerState.close() }
                                },
                                onEdit = { presetDialog = tabId to preset },
                            )
                        }
                    }
                    items(tabConvs, key = { it.id }) { conv ->
                        ConversationDrawerItem(
                            conv = conv,
                            selected = conv.id == current?.id,
                            onSelect = {
                                vm.selectConversation(conv.id)
                                scope.launch { drawerState.close() }
                            },
                            onMove = { moveTarget = conv },
                            onDelete = { vm.deleteConversation(conv.id) },
                        )
                    }
                    if (tabConvs.isEmpty() && selectedFolderTab == null) {
                        item(key = "empty-hint") {
                            Text(
                                "暂无会话，点击右上角 + 新建",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                    item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "会话列表")
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = { pickerOpen = true }),
                        ) {
                            Text(
                                currentModel?.displayId ?: "选择模型",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                            )
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换模型")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.newConversation() }) {
                            Icon(Icons.Filled.Add, contentDescription = "新对话")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding(),
            ) {
                if (messages.isEmpty() && !streaming) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.Face,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "开始与模型对话",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            "先在「厂商」页添加 AI 厂商并选择模型，\n然后点击顶部模型名进行选择",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                        )
                        TextButton(onClick = onGoProviders) { Text("去添加厂商") }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp, vertical = 8.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(messages, key = { it.id }) { msg ->
                                MessageBubble(
                                    message = msg,
                                    actionsEnabled = !streaming,
                                    onLongPress = { detailMessage = msg },
                                    onImageClick = { fullImage = it },
                                    onCopy = {
                                        copyText(context, msg.content)
                                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                    },
                                    onRegenerate = { vm.regenerateFrom(msg) },
                                    onMore = { actionMessage = msg },
                                )
                            }
                            if (streaming) {
                                item(key = "streaming") {
                                    StreamingItem(vm, listState)
                                }
                            }
                            error?.let { err ->
                                item(key = "error") {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(start = 12.dp),
                                        ) {
                                            Text(
                                                err,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.weight(1f),
                                            )
                                            IconButton(onClick = { vm.dismissError() }) {
                                                Icon(Icons.Filled.Close, contentDescription = "关闭")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        MessageQuickNav(
                            listState = listState,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 6.dp),
                        )
                    }
                }

                // 待发送附件
                if (attachments.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        attachments.forEach { uri ->
                            AttachmentThumb(uri = uri, onRemove = {
                                attachments = attachments - uri
                            })
                        }
                    }
                }

                // 输入栏
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    // 思考深度：仅具备推理能力的模型显示
                    if (currentModel?.model?.capabilityReasoning == true) {
                        Box {
                            IconButton(onClick = { thinkMenuOpen = true }) {
                                Icon(
                                    Icons.Filled.Psychology,
                                    contentDescription = "思考深度",
                                    tint = if (thinkingDepth != null && thinkingDepth != ThinkingDepth.OFF) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            DropdownMenu(
                                expanded = thinkMenuOpen,
                                onDismissRequest = { thinkMenuOpen = false },
                            ) {
                                ThinkingDepth.LABELS.forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        leadingIcon = {
                                            if (thinkingDepth == value) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        },
                                        onClick = {
                                            vm.setThinkingDepth(value)
                                            thinkMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (currentModel?.model?.inputImage == true) {
                        IconButton(onClick = {
                            pickImages.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }) { Icon(Icons.Filled.Add, contentDescription = "添加图片") }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入消息…") },
                        maxLines = 5,
                    )
                    Spacer(Modifier.width(6.dp))
                    if (streaming) {
                        FilledIconButton(
                            onClick = { vm.stopStreaming() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) { Icon(Icons.Filled.Close, contentDescription = "停止") }
                    } else {
                        FilledIconButton(
                            onClick = {
                                val text = input
                                val images = attachments
                                input = ""
                                attachments = emptyList()
                                vm.send(text, images)
                            },
                            enabled = input.isNotBlank() || attachments.isNotEmpty(),
                        ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送") }
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        ModelPickerSheet(
            onDismiss = { pickerOpen = false },
            models = pickerModels,
            selectedProviderId = currentModel?.provider?.id,
            selectedModelName = currentModel?.model?.name,
            onSelect = {
                vm.selectModel(it.provider.id, it.model.name)
                pickerOpen = false
            },
            onGoProviders = onGoProviders,
        )
    }

    detailMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { detailMessage = null },
            confirmButton = {
                TextButton(onClick = {
                    copyText(context, msg.content)
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
            models = pickerModels,
            onDismiss = { presetDialog = null },
            onSave = { name, sys, pid, mn ->
                vm.savePreset(folderId, existing, name, sys, pid, mn)
                presetDialog = null
            },
            onDelete = existing?.let { p -> ({ vm.deletePreset(p); presetDialog = null }) },
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    actionsEnabled: Boolean,
    onLongPress: () -> Unit,
    onImageClick: (androidx.compose.ui.graphics.ImageBitmap) -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onMore: () -> Unit,
) {
    if (message.role == Role.USER) {
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (message.images.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .padding(bottom = 4.dp),
                ) {
                    message.images.forEach { url ->
                        MessageImage(url = url, onClick = onImageClick)
                    }
                }
            }
            if (message.content.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .combinedClickable(onClick = {}, onLongClick = onLongPress),
                ) {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            MessageActionsRow(
                alignEnd = true,
                enabled = actionsEnabled,
                onCopy = onCopy,
                onRegenerate = onRegenerate,
                onMore = onMore,
            )
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.widthIn(max = 320.dp)) {
                val isLong = remember(message.content) {
                    message.content.lines().size > 12 || message.content.length > 600
                }
                var collapsed by rememberSaveable(message.id) { mutableStateOf(false) }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                    modifier = Modifier.combinedClickable(
                        onClick = { if (isLong) collapsed = !collapsed },
                        onLongClick = onLongPress,
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (!message.reasoning.isNullOrBlank()) {
                            ReasoningSection(message.reasoning, inProgress = false)
                        }
                        CollapsibleMessageBody(message.content, isLong, collapsed) {
                            collapsed = !collapsed
                        }
                    }
                }
            }
            MessageActionsRow(
                alignEnd = false,
                enabled = actionsEnabled,
                onCopy = onCopy,
                onRegenerate = onRegenerate,
                onMore = onMore,
            )
        }
    }
}

/** 气泡下方操作行：复制 / 重新生成 / 更多 */
@Composable
private fun MessageActionsRow(
    alignEnd: Boolean,
    enabled: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SmallMessageAction(Icons.Filled.ContentCopy, "复制", enabled, onCopy)
        SmallMessageAction(Icons.Filled.Refresh, "重新生成", enabled, onRegenerate)
        SmallMessageAction(Icons.Filled.MoreVert, "更多", enabled, onMore)
    }
}

@Composable
private fun SmallMessageAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(28.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 助手消息正文：长消息支持整体折叠，减少长回复刷屏。
 * 点击气泡任意位置或「收起」折叠；折叠后仅保留首行摘要，点击摘要或气泡展开。
 */
@Composable
private fun CollapsibleMessageBody(
    content: String,
    isLong: Boolean,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
) {
    if (collapsed) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggleCollapse)
                .padding(vertical = 2.dp),
        ) {
            Text(
                content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "展开",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "展开消息",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    } else {
        MarkdownText(content)
        if (isLong) {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleCollapse)
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    "收起",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = "收起消息",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(16.dp),
                )
            }
        }
    }
}

/**
 * 消息区右侧快速导航（垂直居中，从上到下）：
 * 顶 = 回到列表顶部；上 = 当前消息顶部（已在其顶部则跳上一条消息）；
 * 下 = 露出当前消息最下面（底部已可见则跳到下一条消息）；底 = 回到底部（已在底部则不响应）。
 * 「当前消息」取视口顶部可见的消息。滑动列表或按钮触发滚动时显示，停止滚动 3 秒后淡出隐藏。
 */
@Composable
private fun MessageQuickNav(listState: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // 判定"已贴着当前消息顶部/底部"的容差
    val tolerancePx = with(LocalDensity.current) { 16.dp.toPx() }

    val scrolling = listState.isScrollInProgress
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling) {
        if (scrolling) {
            visible = true
        } else {
            delay(3000)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QuickNavButton("顶", "回到顶部", filled = true) {
                scope.launch { listState.animateScrollToItem(0) }
            }
            QuickNavButton("上", "跳到当前消息顶部，已在顶部则跳上一条消息", filled = false) {
                scope.launch {
                    val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                        ?: return@launch
                    val target = if (first.offset >= -tolerancePx) first.index - 1 else first.index
                    if (target >= 0) listState.animateScrollToItem(target)
                }
            }
            QuickNavButton("下", "跳到当前消息最下面，底部已可见则跳下一条消息", filled = false) {
                scope.launch {
                    val layout = listState.layoutInfo
                    val first = layout.visibleItemsInfo.firstOrNull() ?: return@launch
                    // 当前消息底边与视口底边的距离；还有距离则只滚到消息末尾
                    val distance = first.offset + first.size - layout.viewportEndOffset
                    if (distance > tolerancePx) {
                        listState.animateScrollBy(distance.toFloat())
                    } else if (first.index < layout.totalItemsCount - 1) {
                        listState.animateScrollToItem(first.index + 1)
                    }
                }
            }
            QuickNavButton("底", "回到底部", filled = true) {
                scope.launch {
                    // 已在底部则不响应
                    if (!listState.canScrollForward) return@launch
                    listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                    // 最后一条消息可能高于视口；大步长滚动，LazyList 会在真正的底部停下
                    listState.animateScrollBy(1_000_000f)
                }
            }
        }
    }
}

@Composable
private fun QuickNavButton(label: String, desc: String, filled: Boolean, onClick: () -> Unit) {
    val buttonModifier = Modifier
        .size(38.dp)
        .semantics { contentDescription = desc }
    if (filled) {
        FilledIconButton(onClick = onClick, modifier = buttonModifier) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        FilledTonalIconButton(onClick = onClick, modifier = buttonModifier) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 流式气泡容器：在列表项内部收集流式文本，
 * 使状态变化只重组这一个列表项，而不是整个 ChatScreen。
 */
@Composable
private fun StreamingItem(vm: ChatViewModel, listState: LazyListState) {
    val reasoning by vm.streamingReasoning.collectAsStateWithLifecycle()
    val content by vm.streamingText.collectAsStateWithLifecycle()

    // 跟随滚动：视口已接近底部时才自动下滚到最新内容，避免打断用户上翻
    LaunchedEffect(content, reasoning) {
        val info = listState.layoutInfo
        val last = info.totalItemsCount - 1
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (lastVisible >= last - 1) listState.scrollBy(1_000_000f)
    }

    StreamingBubble(reasoning = reasoning, content = content)
}

@Composable
private fun StreamingBubble(reasoning: String, content: String) {
    Row(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
        ) {
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (reasoning.isNotBlank()) {
                    ReasoningSection(reasoning, inProgress = content.isEmpty())
                }
                if (content.isNotEmpty()) {
                    Text(content + " ▍", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun MessageImage(url: String, onClick: (androidx.compose.ui.graphics.ImageBitmap) -> Unit) {
    val bitmap = rememberDataUrlBitmap(url).value
    Surface(shape = RoundedCornerShape(10.dp)) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onClick(bitmap) },
            )
        } else {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(140.dp)) {}
        }
    }
}

@Composable
private fun AttachmentThumb(uri: android.net.Uri, onRemove: () -> Unit) {
    val context = LocalContext.current
    val bitmap = produceUriBitmap(context, uri).value
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(8.dp)) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp),
                )
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(72.dp)) {}
            }
        }
        TextButton(onClick = onRemove) { Text("移除", style = MaterialTheme.typography.labelSmall) }
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("phoneRouter", text))
}

/** 抽屉分组横向 tab 的胶囊按钮 */
@Composable
private fun FolderTab(
    label: String,
    icon: ImageVector,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp),
            )
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun PresetDrawerItem(
    preset: PresetEntity,
    onUse: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onUse)
            .padding(start = 24.dp, end = 8.dp),
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp, top = 8.dp, bottom = 8.dp)) {
            Text(preset.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                if (preset.systemPrompt != null) "预设 · 含系统提示词" else "预设",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "编辑预设",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun ConversationDrawerItem(
    conv: ConversationEntity,
    selected: Boolean,
    onSelect: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    NavigationDrawerItem(
        label = { Text(conv.title, maxLines = 1) },
        selected = selected,
        onClick = onSelect,
        badge = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "会话菜单",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("移动到文件夹…") },
                        onClick = { menuOpen = false; onMove() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除对话") },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        },
    )
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
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
