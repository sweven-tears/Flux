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
                                ChatUiDefaults.MessageImage(url = url, onClick = env.showImage, openExternal = env.imageOpenExternal)
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
                                ChatUiDefaults.MessageImage(url = url, onClick = env.showImage, openExternal = env.imageOpenExternal)
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
                        copyEnabled = message.images.isEmpty(),
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
                                ChatUiDefaults.MessageImage(url = url, onClick = env.showImage, openExternal = env.imageOpenExternal)
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
                                ChatUiDefaults.MessageImage(url = url, onClick = env.showImage, openExternal = env.imageOpenExternal)
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
                                MarkdownText(
                                    message.content,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = mono),
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
                        copyEnabled = message.images.isEmpty(),
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
                            MarkdownText(
                                content + " ▍",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
        }
    }

}
