package cn.luoluna.sweven.ai.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.ui.common.FullPageScaffold

/**
 * 聊天记录页：把当前会话全部消息按对话顺序排成一列，每条只占一行摘要，点开才展开正文；
 * 风琴式展开——任意时刻最多展开一条（点另一条会自动收起上一条）。
 * 顶部搜索框对消息正文做全文检索，输入后列表只留命中的消息，清空恢复全部。
 * 消息直连 DAO 实时读库（与自由复制页同范式）：流式中的会话打开时新消息会自动出现。
 */
@Composable
fun ChatHistoryScreen(conversationId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val messages by remember(conversationId) {
        app.database.messageDao().observeForConversation(conversationId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val userName by app.settings.userName.collectAsStateWithLifecycle()
    val userAvatar by app.settings.userAvatar.collectAsStateWithLifecycle()

    // 会话标题只作副标题展示，取一次即可（改名不影响本页阅读）
    var conversationTitle by remember(conversationId) { mutableStateOf<String?>(null) }
    LaunchedEffect(conversationId) {
        conversationTitle = app.database.conversationDao().byId(conversationId)?.title
    }

    var query by rememberSaveable(conversationId) { mutableStateOf("") }
    var expandedId by rememberSaveable(conversationId) { mutableStateOf<Long?>(null) }
    val keyword = query.trim()
    val shown = remember(messages, keyword) {
        if (keyword.isEmpty()) messages
        else messages.filter { it.content.contains(keyword, ignoreCase = true) }
    }

    FullPageScaffold(
        onBack = onBack,
        title = {
            Column {
                Text("聊天记录", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                conversationTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索本会话消息…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (messages.isNotEmpty()) {
                Text(
                    if (keyword.isEmpty()) "共 ${messages.size} 条消息" else "命中 ${shown.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 4.dp),
                )
            }
            HorizontalDivider()
            when {
                messages.isEmpty() -> CenterHint("这个会话还没有消息")
                shown.isEmpty() -> CenterHint("没有匹配的消息")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { message ->
                        HistoryRow(
                            message = message,
                            expanded = message.id == expandedId,
                            userName = userName,
                            userAvatar = userAvatar,
                            onToggle = {
                                expandedId = if (expandedId == message.id) null else message.id
                            },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 一条记录：折叠态只有一行摘要（首个非空行），展开态显示完整正文 */
@Composable
private fun HistoryRow(
    message: MessageEntity,
    expanded: Boolean,
    userName: String?,
    userAvatar: String?,
    onToggle: () -> Unit,
) {
    val isUser = message.role == Role.USER
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (isUser) {
            UserAvatar(name = userName, avatarDataUrl = userAvatar, size = 28.dp)
        } else {
            AssistantAvatar(size = 28.dp)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text(
                if (isUser) "用户" else "助手",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                if (isUser) {
                    Text(
                        message.content.ifBlank { "（无文本内容）" },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    MarkdownText(
                        message.content.ifBlank { "（无文本内容）" },
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            } else {
                Text(
                    summaryOf(message),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(18.dp),
        )
    }
}

/** 折叠态摘要：首个非空行；纯图片/空消息给占位文案，避免整行空白看不出是什么 */
private fun summaryOf(message: MessageEntity): String {
    val firstLine = message.content.lineSequence().firstOrNull { it.isNotBlank() }
    if (firstLine != null) return firstLine
    return if (message.images.isNotEmpty()) "（图片消息）" else "（无文本内容）"
}
