package cn.luoluna.sweven.ai.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.ui.common.FullPageScaffold

/**
 * 自由复制页：文本可长按拖选后单独复制，也可一键复制全文。
 * 按 id 实时读库（observeById），流式输出中的消息打开时内容同步更新。
 */
@Composable
fun FreeCopyScreen(id: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val message by remember(id) { app.database.messageDao().observeById(id) }
        .collectAsStateWithLifecycle(initialValue = null)

    val msg = message
    FullPageScaffold(
        title = when {
            msg == null -> "自由复制"
            msg.role == Role.USER -> "用户消息"
            else -> "助手消息"
        },
        onBack = onBack,
        actions = {
            if (msg != null) {
                TextButton(onClick = { copyMessageText(context, msg.content) }) { Text("复制全文") }
            }
        },
    ) { padding ->
        if (msg == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("消息不存在或已被删除", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            SelectionContainer(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(
                    msg.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
