package cn.luoluna.sweven.ai.ui.chat.plugin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.data.db.ConversationEntity
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.PresetEntity
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.net.ThinkingDepth
import cn.luoluna.sweven.ai.ui.chat.ChatError
import cn.luoluna.sweven.ai.ui.chat.ConversationHit
import cn.luoluna.sweven.ai.ui.chat.ErrorDetailSheet
import cn.luoluna.sweven.ai.ui.chat.MarkdownText
import cn.luoluna.sweven.ai.ui.chat.ReasoningSection
import cn.luoluna.sweven.ai.ui.chat.produceUriBitmap
import cn.luoluna.sweven.ai.ui.chat.rememberDataUrlBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 默认槽位实现：现有 ChatScreen 各区域原样抽出的公共实现。
 * default 插件全部使用这里；其他插件覆写个别槽位时，未覆写区域也回落到这里。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
object ChatUiDefaults {

    // ---------------- 抽屉 ----------------

    @Composable
    fun Drawer(env: ChatUiEnv) {
        ModalDrawerSheet {
            // 用户资料区：头像 + 名字，点击编辑
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = env.editUserProfile)
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            ) {
                cn.luoluna.sweven.ai.ui.chat.UserAvatar(
                    name = env.userName,
                    avatarDataUrl = env.userAvatar,
                    size = 40.dp,
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        env.userName?.ifBlank { null } ?: "我",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "点击编辑资料",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp),
            ) {
                Text("对话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = env.showNewFolder) {
                    Text("新建文件夹", style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = {
                    env.vm.newConversation(env.selectedFolderTab)
                    env.scope.launch { env.drawerState.close() }
                }) { Icon(Icons.Filled.Add, contentDescription = "新建对话") }
            }
            OutlinedTextField(
                value = env.searchQuery,
                onValueChange = { env.vm.setSearchQuery(it) },
                placeholder = {
                    Text("搜索对话与消息…", style = MaterialTheme.typography.bodySmall)
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = {
                    if (env.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { env.vm.setSearchQuery("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "清空搜索",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            // 分组横向 tab：「聊天」（默认分组）+ 各文件夹
            LaunchedEffect(env.drawer.folders, env.selectedFolderTab) {
                if (env.selectedFolderTab != null && env.drawer.folders.none { it.id == env.selectedFolderTab }) {
                    env.onSelectFolderTab(null)
                }
            }
            if (env.searchQuery.isBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    val defaultConvs = env.drawer.conversationsByFolder[null].orEmpty()
                    FolderTab(
                        label = "聊天",
                        icon = Icons.AutoMirrored.Filled.Chat,
                        count = defaultConvs.size,
                        selected = env.selectedFolderTab == null,
                        onClick = { env.onSelectFolderTab(null) },
                    )
                    env.drawer.folders.forEach { folder ->
                        FolderTab(
                            label = folder.name,
                            icon = Icons.Filled.Folder,
                            count = env.drawer.conversationsByFolder[folder.id].orEmpty().size +
                                env.drawer.presetsByFolder[folder.id].orEmpty().size,
                            selected = env.selectedFolderTab == folder.id,
                            onClick = { env.onSelectFolderTab(folder.id) },
                        )
                    }
                }
                HorizontalDivider()
                // 当前分组内容：文件夹 tab 先列预设对话，再列普通会话
                var folderMenuOpen by remember { mutableStateOf(false) }
                env.selectedFolderTab?.let { folderId ->
                    env.drawer.folders.firstOrNull { it.id == folderId }?.let { folder ->
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
                            TextButton(onClick = { env.editPreset(folder.id, null) }) {
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
                                            env.renameFolder(folder)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除文件夹") },
                                        onClick = {
                                            folderMenuOpen = false
                                            env.deleteFolder(folder)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    val tabId = env.selectedFolderTab
                    val tabConvs = env.drawer.conversationsByFolder[tabId].orEmpty()
                    if (tabId != null) {
                        items(env.drawer.presetsByFolder[tabId].orEmpty(), key = { "preset-${it.id}" }) { preset ->
                            PresetDrawerItem(
                                preset = preset,
                                onUse = {
                                    env.vm.startFromPreset(preset)
                                    env.scope.launch { env.drawerState.close() }
                                },
                                onEdit = { env.editPreset(tabId, preset) },
                            )
                        }
                    }
                    items(tabConvs, key = { it.id }) { conv ->
                        ConversationDrawerItem(
                            conv = conv,
                            selected = conv.id == env.current?.id,
                            onSelect = {
                                env.vm.selectConversation(conv.id)
                                env.scope.launch { env.drawerState.close() }
                            },
                            onMove = { env.moveConversation(conv) },
                            onDelete = { env.vm.deleteConversation(conv.id) },
                        )
                    }
                    if (tabConvs.isEmpty() && env.selectedFolderTab == null) {
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
            } else {
                // 搜索模式：全局命中结果（标题或消息内容），忽略文件夹分组
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(env.searchResults, key = { it.conversation.id }) { hit ->
                        SearchHitItem(
                            hit = hit,
                            folderName = env.drawer.folders
                                .firstOrNull { it.id == hit.conversation.folderId }?.name,
                            selected = hit.conversation.id == env.current?.id,
                            onClick = {
                                env.vm.selectConversation(hit.conversation.id)
                                env.scope.launch { env.drawerState.close() }
                            },
                        )
                    }
                    if (env.searchResults.isEmpty()) {
                        item(key = "no-result") {
                            Text(
                                "没有匹配的对话",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                    item(key = "search-bottom-space") { Spacer(Modifier.height(24.dp)) }
                }
            }
            HorizontalDivider()
            NavigationDrawerItem(
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                label = { Text("设置") },
                selected = false,
                onClick = {
                    env.scope.launch { env.drawerState.close() }
                    env.onOpenSettings()
                },
            )
        }
    }

    // ---------------- 顶栏 ----------------

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    fun TopBar(env: ChatUiEnv) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = { env.scope.launch { env.drawerState.open() } }) {
                    Icon(Icons.Filled.Menu, contentDescription = "会话列表")
                }
            },
            title = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 第一行：会话标题（大、深色），点击重命名会话
                    Text(
                        env.current?.title ?: "新对话",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.combinedClickable(onClick = env.editConversationTitle),
                    )
                    // 第二行：模型 id（厂商名字）（小、浅色），点击切换模型
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.combinedClickable(onClick = env.openModelPicker),
                    ) {
                        Text(
                            when {
                                env.isSmartAi -> "smartAi"
                                env.currentPicker != null -> env.currentPicker.displayId
                                else -> "选择模型"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "切换模型",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (env.modelFallback) {
                        Text(
                            "会话原模型不可用，已回退",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                        )
                    }
                }
            },
            actions = {
                if (env.current != null) {
                    IconButton(onClick = env.openSystemPrompt) {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = "系统提示词",
                            tint = if (env.current.systemPrompt != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                IconButton(onClick = { env.vm.newConversation(env.selectedFolderTab) }) {
                    Icon(Icons.Filled.Add, contentDescription = "新对话")
                }
                Box {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("界面风格") },
                            onClick = {
                                menuOpen = false
                                env.openPluginPicker()
                            },
                        )
                    }
                }
            },
        )
    }

    // ---------------- 空态 ----------------

    @Composable
    fun EmptyState(env: ChatUiEnv) {
        Column(
            Modifier
                .fillMaxSize()
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
            TextButton(onClick = env.onGoProviders) { Text("去添加厂商") }
        }
    }

    // ---------------- 消息列表 ----------------

    @Composable
    fun MessageList(env: ChatUiEnv, contentPadding: PaddingValues) {
        var showErrorDetail by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = env.listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(env.messages, key = { it.id }) { msg ->
                    env.renderMessageBubble(msg)
                }
                if (env.streaming) {
                    item(key = "streaming") {
                        env.renderStreamingBubble()
                    }
                }
                env.error?.let { err ->
                    item(key = "error") {
                        ErrorBar(
                            error = err,
                            onShowDetail = { showErrorDetail = true },
                            onDismiss = { env.vm.dismissError() },
                        )
                    }
                }
                env.notice?.let { note ->
                    item(key = "notice") {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp),
                            ) {
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { env.vm.dismissNotice() }) {
                                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                                }
                            }
                        }
                    }
                }
            }
            MessageQuickNav(
                listState = env.listState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp),
            )
        }
        if (showErrorDetail) {
            env.error?.let { err ->
                ErrorDetailSheet(error = err, onDismiss = { showErrorDetail = false })
            }
        }
    }

    /** 错误条：整条可点击打开详情半窗；行内按钮复制完整错误文本 */
    @Composable
    private fun ErrorBar(
        error: ChatError,
        onShowDetail: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val context = LocalContext.current
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onShowDetail),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Text(
                    error.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                )
                IconButton(onClick = {
                    copyText(context, error.detailText())
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "复制错误信息",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
        }
    }

    // ---------------- 单条消息气泡 ----------------

    @Composable
    fun MessageBubble(env: ChatUiEnv, message: MessageEntity) {
        if (message.role == Role.USER) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 头像在上方：用户 = 名字 + 头像（右对齐）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    Text(
                        env.userName?.ifBlank { null } ?: "我",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    cn.luoluna.sweven.ai.ui.chat.UserAvatar(
                        name = env.userName,
                        avatarDataUrl = env.userAvatar,
                        size = 24.dp,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                if (message.images.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .padding(bottom = 4.dp),
                    ) {
                        message.images.forEach { url ->
                            MessageImage(url = url, onClick = env.showImage)
                        }
                    }
                }
                if (message.content.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .combinedClickable(onClick = {}, onLongClick = { env.showDetail(message) }),
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
                    enabled = !env.streaming,
                    onCopy = { env.copyMessage(message) },
                    onRegenerate = { env.vm.regenerateFrom(message) },
                    onMore = { env.showMessageActions(message) },
                )
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                // 头像在上方：助手 = 头像 + 名字（按消息记录的模型显示当时回复者，而非当前选中模型）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    val recorded = message.providerId?.let { pid ->
                        message.modelName?.let { name -> env.modelsByKey[pid to name] }
                    }
                    if (recorded != null) {
                        cn.luoluna.sweven.ai.ui.chat.InitialAvatar(
                            name = recorded.displayName,
                            size = 24.dp,
                        )
                    } else {
                        cn.luoluna.sweven.ai.ui.chat.AssistantAvatar(size = 24.dp)
                    }
                    Text(
                        when {
                            recorded != null -> recorded.displayName
                            // 模型已删除：保留原始模型名，头像无法匹配回落机器人
                            message.modelName != null -> message.modelName
                            // 旧数据未记录模型：诚实显示"助手"，不按会话当前模型猜测
                            else -> "助手"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
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
                            onLongClick = { env.showDetail(message) },
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
                    enabled = !env.streaming,
                    onCopy = { env.copyMessage(message) },
                    onRegenerate = { env.vm.regenerateFrom(message) },
                    onMore = { env.showMessageActions(message) },
                )
            }
        }
    }

    // ---------------- 流式气泡 ----------------

    /**
     * 流式气泡容器：在列表项内部收集流式文本，
     * 使状态变化只重组这一个列表项，而不是整个页面。
     */
    @Composable
    fun StreamingBubble(env: ChatUiEnv) {
        val reasoning by env.vm.streamingReasoning.collectAsStateWithLifecycle()
        val content by env.vm.streamingText.collectAsStateWithLifecycle()

        // 跟随滚动：视口已接近底部时才自动下滚到最新内容，避免打断用户上翻
        LaunchedEffect(content, reasoning) {
            val info = env.listState.layoutInfo
            val last = info.totalItemsCount - 1
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
            if (lastVisible >= last - 1) env.listState.scrollBy(1_000_000f)
        }

        StreamingBubbleContent(reasoning = reasoning, content = content)
    }

    @Composable
    fun StreamingBubbleContent(reasoning: String, content: String) {
        Column(Modifier.fillMaxWidth()) {
            // 头像在上方：助手 = 头像 + 名字
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                cn.luoluna.sweven.ai.ui.chat.AssistantAvatar(size = 24.dp)
                Text(
                    "回复中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
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

    // ---------------- 附件行 ----------------

    @Composable
    fun AttachmentRow(env: ChatUiEnv) {
        if (env.editAttachments.isEmpty() && env.attachments.isEmpty()) return
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            env.editAttachments.forEach { url ->
                DataUrlThumb(url = url, onRemove = { env.removeEditAttachment(url) })
            }
            env.attachments.forEach { uri ->
                AttachmentThumb(uri = uri, onRemove = { env.removeAttachment(uri) })
            }
        }
    }

    // ---------------- 输入栏（底部输入面板） ----------------

    @Composable
    fun InputBar(env: ChatUiEnv) {
        var expandOpen by remember { mutableStateOf(false) }
        val panelBg = MaterialTheme.colorScheme.surfaceContainerLow
        val onPanel = MaterialTheme.colorScheme.onSurfaceVariant

        Column(
            Modifier
                .fillMaxWidth()
                .background(panelBg, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(top = 4.dp),
        ) {
            // 瞬态行：上下文用量 / 编辑提示 / 待发送附件
            env.contextEstimateText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (env.contextEstimateLevel) {
                        2 -> MaterialTheme.colorScheme.error
                        1 -> MaterialTheme.colorScheme.tertiary
                        else -> onPanel
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (env.editingMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp),
                    ) {
                        Text(
                            "正在编辑消息，发送后将替换原消息并重发",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = env.cancelEditing) {
                            Icon(Icons.Filled.Close, contentDescription = "取消编辑")
                        }
                    }
                }
            }
            env.renderAttachmentRow()

            // TODO 快捷提示词功能未开发：后续根据当前对话内容动态生成建议提示词，
            //  在此渲染横向胶囊列表（LazyRow），为空时整行隐藏

            // 主输入框：面板色上的圆角 Surface 包无边框 TextField
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                TextField(
                    value = env.input,
                    onValueChange = env.onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入消息与AI聊天", color = onPanel) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    maxLines = 5,
                    trailingIcon = {
                        IconButton(onClick = { expandOpen = true }) {
                            Icon(
                                Icons.Filled.CloseFullscreen,
                                contentDescription = "展开输入",
                                tint = onPanel,
                            )
                        }
                    },
                )
            }

            // 底部工具栏：左（模型/思考）右（加号/发送）
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 模型切换按钮（模型头像占位，后续按模型 id 匹配真实 logo）
                    IconButton(onClick = env.openModelPicker) {
                        cn.luoluna.sweven.ai.ui.chat.AssistantAvatar(size = 26.dp)
                    }
                    // TODO 搜索功能未开发，先隐藏（原跳转抽屉搜索框，等独立搜索功能做好再放出）
                    // IconButton(onClick = { env.scope.launch { env.drawerState.open() } }) {
                    //     Icon(Icons.Filled.Search, contentDescription = "搜索", tint = onPanel)
                    // }
                    // 思考模式：仅具备推理能力的模型显示（smartAi 按候选并集判断）
                    if (env.showReasoning) {
                        Box {
                            var thinkMenuOpen by remember { mutableStateOf(false) }
                            val thinkingOn = env.effectiveThinkingDepth != ThinkingDepth.OFF
                            IconButton(onClick = { thinkMenuOpen = true }) {
                                Box(Modifier.size(26.dp)) {
                                    Icon(
                                        Icons.Filled.Lightbulb,
                                        contentDescription = "思考模式",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    if (thinkingOn) {
                                        Box(
                                            Modifier
                                                .size(7.dp)
                                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                                .align(Alignment.TopEnd),
                                        )
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = thinkMenuOpen,
                                onDismissRequest = { thinkMenuOpen = false },
                            ) {
                                ThinkingDepth.LABELS.forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        leadingIcon = {
                                            if (env.effectiveThinkingDepth == value) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        },
                                        onClick = {
                                            env.vm.setThinkingDepth(value)
                                            thinkMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 加号（添加图片等）：仅支持图片输入的模型显示（smartAi 按候选并集判断）
                    if (env.showImageInput) {
                        IconButton(onClick = env.pickImages) {
                            Icon(Icons.Filled.Add, contentDescription = "添加图片", tint = onPanel)
                        }
                    }
                    if (env.streaming) {
                        // 流式中变为停止按钮；二次点击确认逻辑在 vm.onStopTap，首点居中提示
                        IconButton(onClick = { env.vm.onStopTap() }) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .background(MaterialTheme.colorScheme.error, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "停止",
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    } else {
                        val canSend = env.input.isNotBlank() || env.attachments.isNotEmpty() ||
                            env.editAttachments.isNotEmpty()
                        IconButton(onClick = env.send, enabled = canSend) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .background(
                                        if (canSend) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.ArrowUpward,
                                    contentDescription = "发送",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 上拉展开的大输入框
        if (expandOpen) {
            ModalBottomSheet(
                onDismissRequest = { expandOpen = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    OutlinedTextField(
                        value = env.input,
                        onValueChange = env.onInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        placeholder = { Text("输入消息与AI聊天") },
                    )
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        TextButton(onClick = { expandOpen = false }) { Text("收起") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                expandOpen = false
                                env.send()
                            },
                            enabled = env.input.isNotBlank() || env.attachments.isNotEmpty() ||
                                env.editAttachments.isNotEmpty(),
                        ) { Text("发送") }
                    }
                }
            }
        }
    }

    // ---------------- 可复用组件 ----------------

    /** 气泡下方操作行：复制 / 重新生成 / 更多 */
    @Composable
    fun MessageActionsRow(
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
    fun SmallMessageAction(
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
    fun CollapsibleMessageBody(
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
     * 「当前消息」取视口顶部可见的消息。滑动列表时隐藏；停止滚动且不在顶部/底部时显示，3 秒后淡出隐藏，再次滑动立即隐藏。
     */
    @Composable
    fun MessageQuickNav(listState: LazyListState, modifier: Modifier = Modifier) {
        val scope = rememberCoroutineScope()
        // 判定"已贴着当前消息顶部/底部"的容差
        val tolerancePx = with(LocalDensity.current) { 16.dp.toPx() }

        val scrolling = listState.isScrollInProgress
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(scrolling) {
            if (scrolling) {
                visible = false
            } else if (listState.canScrollBackward && listState.canScrollForward) {
                // 停止滚动且不在顶部/底部：显示，3 秒后淡出；期间再次滑动会走上面分支立即隐藏
                visible = true
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
    fun QuickNavButton(label: String, desc: String, filled: Boolean, onClick: () -> Unit) {
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

    @Composable
    fun MessageImage(url: String, onClick: (androidx.compose.ui.graphics.ImageBitmap) -> Unit) {
        val bitmap = rememberDataUrlBitmap(url).value
        Surface(shape = RoundedCornerShape(10.dp)) {
            if (bitmap != null) {
                Image(
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
    fun AttachmentThumb(uri: android.net.Uri, onRemove: () -> Unit) {
        val context = LocalContext.current
        val bitmap = produceUriBitmap(context, uri).value
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(8.dp)) {
                if (bitmap != null) {
                    Image(
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

    /** 编辑态保留的原图附件（已是压缩过的 data URL，直接解码展示） */
    @Composable
    fun DataUrlThumb(url: String, onRemove: () -> Unit) {
        val bitmap = rememberDataUrlBitmap(url).value
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(8.dp)) {
                if (bitmap != null) {
                    Image(
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

    /** 抽屉分组横向 tab 的胶囊按钮 */
    @Composable
    fun FolderTab(
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
    fun PresetDrawerItem(
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

    /** 搜索结果项：会话标题 + 命中片段/所在文件夹 */
    @Composable
    fun SearchHitItem(
        hit: ConversationHit,
        folderName: String?,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        val snippet = hit.snippet
        val subtitle = when {
            snippet != null -> snippet.replace('\n', ' ').take(60) +
                (folderName?.let { "  ·  $it" } ?: "")
            folderName != null -> "「$folderName」中的对话"
            else -> "标题命中"
        }
        Surface(
            color = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                androidx.compose.ui.graphics.Color.Transparent
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    hit.conversation.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    fun ConversationDrawerItem(
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

    fun copyText(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Flux", text))
    }
}

/**
 * 插件间槽位互调的内部桥：messageList 里渲染单条气泡时回落到插件覆写或默认。
 * 宿主在构建 Env 后注入当前激活插件，供默认实现内部调用。
 * 扩展属性无 backing field，实际存储在 [activePluginStore]。
 */
private val activePluginStore = java.util.WeakHashMap<ChatUiEnv, ChatUiPlugin>()

internal var ChatUiEnv.activePlugin: ChatUiPlugin?
    get() = activePluginStore[this]
    set(value) {
        if (value != null) activePluginStore[this] = value else activePluginStore.remove(this)
    }

@Composable
internal fun ChatUiEnv.renderMessageBubble(message: MessageEntity) {
    val plugin = activePlugin
    val slot = plugin?.messageBubble
    if (slot != null) slot(this, message) else ChatUiDefaults.MessageBubble(this, message)
}

@Composable
internal fun ChatUiEnv.renderStreamingBubble() {
    val plugin = activePlugin
    val slot = plugin?.streamingBubble
    if (slot != null) slot(this) else ChatUiDefaults.StreamingBubble(this)
}

@Composable
internal fun ChatUiEnv.renderAttachmentRow() {
    val plugin = activePlugin
    val slot = plugin?.attachmentRow
    if (slot != null) slot(this) else ChatUiDefaults.AttachmentRow(this)
}
