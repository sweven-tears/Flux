package cn.luoluna.sweven.ai.ui.chat.plugin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.net.ThinkingDepth
import cn.luoluna.sweven.ai.ui.chat.MarkdownText
import cn.luoluna.sweven.ai.ui.chat.ReasoningSection

/**
 * 默认风格：现有实现原样，行为与插件化改造前一致。
 * 全部槽位 null → 回落 [ChatUiDefaults]。
 */
object DefaultChatUiPlugin : ChatUiPlugin {
    override val id = ChatUiRegistry.DEFAULT_ID
    override val displayName = "默认"
    override val description = "气泡式对话，与现有样式一致"
}

/**
 * 扁平风格（ChatGPT 式）：助手消息无气泡全宽 Markdown、用户消息浅色气泡右对齐、间距紧凑。
 * 只覆写 messageBubble / streamingBubble，其余回落默认。
 */
object FlatChatUiPlugin : ChatUiPlugin {
    override val id = "flat"
    override val displayName = "扁平"
    override val description = "助手回复全宽无气泡，用户消息浅色右对齐"

    @OptIn(ExperimentalFoundationApi::class)
    override val messageBubble: (@Composable (ChatUiEnv, MessageEntity) -> Unit) =
        { env, message ->
            if (message.role == Role.USER) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ChatUiDefaults.UserMessageHeader(env)
                    if (message.images.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .padding(bottom = 4.dp),
                        ) {
                            message.images.forEach { url ->
                                ChatUiDefaults.MessageImage(url = url, onClick = env.showImage)
                            }
                        }
                    }
                    if (message.content.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = { env.showDetail(message) },
                                ),
                        ) {
                            Text(
                                message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    ChatUiDefaults.MessageActionsRow(
                        alignEnd = true,
                        enabled = !env.streaming,
                        onCopy = { env.copyMessage(message) },
                        onRegenerate = { env.vm.regenerateFrom(message) },
                        onMore = { env.showMessageActions(message) },
                    )
                    ChatUiDefaults.MessageUsageRow(message, alignEnd = true)
                }
            } else {
                // 助手：全宽无气泡，直接 Markdown；长消息仍支持折叠；生图消息图片在文本上方
                Column(Modifier.fillMaxWidth()) {
                    ChatUiDefaults.AssistantMessageHeader(env, message)
                    if (message.images.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        ) {
                            message.images.forEach { url ->
                                ChatUiDefaults.MessageImage(url = url, onClick = env.showImage)
                            }
                        }
                    }
                    val isLong = remember(message.content) {
                        message.content.lines().size > 12 || message.content.length > 600
                    }
                    var collapsed by rememberSaveable(message.id) { mutableStateOf(false) }
                    if (message.content.isNotBlank() || !message.reasoning.isNullOrBlank()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .combinedClickable(
                                    onClick = { if (isLong) collapsed = !collapsed },
                                    onLongClick = { env.showDetail(message) },
                                )
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                        ) {
                            if (!message.reasoning.isNullOrBlank()) {
                                ReasoningSection(message.reasoning, inProgress = false)
                            }
                            ChatUiDefaults.CollapsibleMessageBody(message.content, isLong, collapsed) {
                                collapsed = !collapsed
                            }
                        }
                    }
                    ChatUiDefaults.MessageActionsRow(
                        alignEnd = false,
                        enabled = !env.streaming,
                        onCopy = { env.copyMessage(message) },
                        onRegenerate = { env.vm.regenerateFrom(message) },
                        onMore = { env.showMessageActions(message) },
                    )
                    ChatUiDefaults.MessageUsageRow(message, alignEnd = false)
                }
            }
        }

    override val streamingBubble: (@Composable (ChatUiEnv) -> Unit) = { env ->
        val reasoning by env.vm.streamingReasoning.collectAsStateWithLifecycle()
        val content by env.vm.streamingText.collectAsStateWithLifecycle()
        val generatingImage by env.vm.generatingImage.collectAsStateWithLifecycle()
        LaunchedEffect(content, reasoning, generatingImage) {
            val info = env.listState.layoutInfo
            val last = info.totalItemsCount - 1
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
            if (lastVisible >= last - 1) env.listState.scrollBy(1_000_000f)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            ChatUiDefaults.StreamingMessageHeader(generatingImage)
            if (generatingImage) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("正在生成图片，请稍候…", modifier = Modifier.padding(start = 8.dp))
                }
            } else {
                if (reasoning.isNotBlank()) {
                    ReasoningSection(reasoning, inProgress = content.isEmpty())
                }
                if (content.isNotEmpty()) {
                    MarkdownText(content + " ▍")
                }
            }
        }
    }
}

/**
 * 等宽风格：等宽字体、细边框气泡、紧凑输入栏。
 * 覆写 messageBubble / streamingBubble / inputBar。
 */
object MonoChatUiPlugin : ChatUiPlugin {
    override val id = "mono"
    override val displayName = "等宽"
    override val description = "等宽字体 + 细边框气泡，终端质感"

    @OptIn(ExperimentalFoundationApi::class)
    override val messageBubble: (@Composable (ChatUiEnv, MessageEntity) -> Unit) =
        { env, message ->
            val mono = FontFamily.Monospace
            if (message.role == Role.USER) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ChatUiDefaults.UserMessageHeader(env)
                    if (message.images.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .padding(bottom = 4.dp),
                        ) {
                            message.images.forEach { url ->
                                ChatUiDefaults.MessageImage(url = url, onClick = env.showImage)
                            }
                        }
                    }
                    if (message.content.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = { env.showDetail(message) },
                                ),
                        ) {
                            Text(
                                message.content,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = mono),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                    ChatUiDefaults.MessageActionsRow(
                        alignEnd = true,
                        enabled = !env.streaming,
                        onCopy = { env.copyMessage(message) },
                        onRegenerate = { env.vm.regenerateFrom(message) },
                        onMore = { env.showMessageActions(message) },
                    )
                    ChatUiDefaults.MessageUsageRow(message, alignEnd = true)
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    ChatUiDefaults.AssistantMessageHeader(env, message)
                    // 生图消息：图片在气泡上方
                    if (message.images.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            message.images.forEach { url ->
                                ChatUiDefaults.MessageImage(url = url, onClick = env.showImage)
                            }
                        }
                    }
                    if (message.content.isNotBlank() || !message.reasoning.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .widthIn(max = 340.dp)
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = { env.showDetail(message) },
                                ),
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                if (!message.reasoning.isNullOrBlank()) {
                                    ReasoningSection(message.reasoning, inProgress = false)
                                }
                                Text(
                                    message.content,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = mono),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    ChatUiDefaults.MessageActionsRow(
                        alignEnd = false,
                        enabled = !env.streaming,
                        onCopy = { env.copyMessage(message) },
                        onRegenerate = { env.vm.regenerateFrom(message) },
                        onMore = { env.showMessageActions(message) },
                    )
                    ChatUiDefaults.MessageUsageRow(message, alignEnd = false)
                }
            }
        }

    override val streamingBubble: (@Composable (ChatUiEnv) -> Unit) = { env ->
        val reasoning by env.vm.streamingReasoning.collectAsStateWithLifecycle()
        val content by env.vm.streamingText.collectAsStateWithLifecycle()
        val generatingImage by env.vm.generatingImage.collectAsStateWithLifecycle()
        LaunchedEffect(content, reasoning, generatingImage) {
            val info = env.listState.layoutInfo
            val last = info.totalItemsCount - 1
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
            if (lastVisible >= last - 1) env.listState.scrollBy(1_000_000f)
        }
        Column(Modifier.fillMaxWidth()) {
            ChatUiDefaults.StreamingMessageHeader(generatingImage)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    Modifier
                        .widthIn(max = 340.dp)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    if (generatingImage) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(
                                "正在生成图片，请稍候…",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    } else {
                        if (reasoning.isNotBlank()) {
                            ReasoningSection(reasoning, inProgress = content.isEmpty())
                        }
                        if (content.isNotEmpty()) {
                            Text(
                                content + " ▍",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }
    }

    override val inputBar: (@Composable (ChatUiEnv) -> Unit) = { env ->
        Column(Modifier.fillMaxWidth()) {
            env.contextEstimateText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = when (env.contextEstimateLevel) {
                        2 -> MaterialTheme.colorScheme.error
                        1 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            if (env.editingMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 10.dp),
                    ) {
                        Text(
                            "编辑中，发送将替换原消息",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
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
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                if (env.showReasoning) {
                    androidx.compose.foundation.layout.Box {
                        var thinkMenuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { thinkMenuOpen = true }) {
                            Icon(
                                Icons.Filled.Psychology,
                                contentDescription = "思考深度",
                                tint = if (env.effectiveThinkingDepth != ThinkingDepth.OFF) {
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
                                        if (env.effectiveThinkingDepth == value) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                modifier = Modifier.padding(end = 0.dp),
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
                if (env.showImageInput) {
                    IconButton(onClick = env.pickImages) {
                        Icon(Icons.Filled.Add, contentDescription = "添加图片")
                    }
                }
                OutlinedTextField(
                    value = env.input,
                    onValueChange = env.onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "输入消息…",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(4.dp),
                    maxLines = 5,
                )
                Spacer(Modifier.width(6.dp))
                    if (env.streaming) {
                        FilledIconButton(
                            onClick = { env.vm.onStopTap() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) { Icon(Icons.Filled.Close, contentDescription = "停止") }
                } else {
                    FilledIconButton(
                        onClick = env.send,
                        enabled = env.input.isNotBlank() || env.attachments.isNotEmpty() ||
                            env.editAttachments.isNotEmpty(),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送") }
                }
            }
        }
    }
}
