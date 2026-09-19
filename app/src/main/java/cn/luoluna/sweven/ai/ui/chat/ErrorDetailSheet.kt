package cn.luoluna.sweven.ai.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cn.luoluna.sweven.ai.data.repo.label
import cn.luoluna.sweven.ai.ui.common.MonoFont

/**
 * 错误详情半窗：点击聊天页错误条打开。
 * 展示 HTTP 状态码、错误消息与上游原始响应体；响应体支持长按选择复制，也可一键复制全部。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorDetailSheet(error: ChatError, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "错误详情",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    copyMessageText(context, error.detailText())
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }) { Text("复制全部") }
            }
            error.kind?.let { kind ->
                Text(
                    kind.label(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            error.httpCode?.let {
                Text(
                    "HTTP $it",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = MonoFont,
                )
            }
            Text(
                error.message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                "上游原始响应",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val body = error.rawBody?.takeIf { it.isNotBlank() }
            SelectionContainer {
                Text(
                    body ?: "（无响应体）",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (body == null) FontFamily.Default else MonoFont,
                    color = if (body == null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                )
            }
        }
    }
}
