package cn.luoluna.sweven.ai.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.Role

/** 「更多」弹层的动作集合；onEdit 仅用户消息传入（编辑并重发）；onDownload/onAttach/onOpenExternally 仅媒体消息 */
data class MessageActions(
    val onFreeCopy: () -> Unit,
    val onWebRender: () -> Unit,
    val onEdit: (() -> Unit)? = null,
    val onBranch: () -> Unit,
    val onDelete: () -> Unit,
    /** 下载（保存到相册），媒体消息必传 */
    val onDownload: (() -> Unit)? = null,
    /** 添加到对话（作为待发附件）；attachEnabled=false 时置灰（当前助手不支持图像输入） */
    val onAttach: (() -> Unit)? = null,
    val attachEnabled: Boolean = true,
    /** 打开方式…（交给系统图片查看器/视频播放器）；媒体消息必传 */
    val onOpenExternally: (() -> Unit)? = null,
)

/**
 * 消息「更多」弹层。
 * 助手媒体消息（带图/视频）：打开方式… / 下载 / 添加到对话 / 创建分支 / 删除；
 * 其余：自由选择复制 / 编辑并重发（仅用户消息）/ 网页视图渲染 / 创建分支 / 删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    message: MessageEntity,
    onDismiss: () -> Unit,
    actions: MessageActions,
) {
    val isAssistantMedia = message.role != Role.USER && message.images.isNotEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                if (message.role == Role.USER) "用户消息" else "助手消息",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (isAssistantMedia) {
                MessageActionRow(Icons.Filled.OpenWith, "打开方式…") {
                    onDismiss(); actions.onOpenExternally?.invoke()
                }
                MessageActionRow(Icons.Filled.Download, "下载（保存到相册）") {
                    onDismiss(); actions.onDownload?.invoke()
                }
                MessageActionRow(
                    Icons.Filled.AddPhotoAlternate,
                    if (actions.attachEnabled) "添加到对话" else "添加到对话（当前助手不支持图像输入）",
                    enabled = actions.attachEnabled,
                ) {
                    onDismiss(); actions.onAttach?.invoke()
                }
                MessageActionRow(Icons.Filled.AccountTree, "创建分支") {
                    onDismiss(); actions.onBranch()
                }
                MessageActionRow(Icons.Filled.Delete, "删除（不可恢复）") {
                    onDismiss(); actions.onDelete()
                }
            } else {
                MessageActionRow(Icons.Filled.ContentCopy, "复制（自由选择）") {
                    onDismiss(); actions.onFreeCopy()
                }
                if (message.role == Role.USER && actions.onEdit != null) {
                    MessageActionRow(Icons.Filled.Edit, "编辑并重发") {
                        onDismiss(); actions.onEdit?.invoke()
                    }
                }
                MessageActionRow(Icons.Filled.Web, "网页视图渲染") {
                    onDismiss(); actions.onWebRender()
                }
                MessageActionRow(Icons.Filled.AccountTree, "创建分支") {
                    onDismiss(); actions.onBranch()
                }
                MessageActionRow(Icons.Filled.Delete, "删除（不可恢复）") {
                    onDismiss(); actions.onDelete()
                }
            }
        }
    }
}

@Composable
private fun MessageActionRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}

/**
 * 全屏自由复制页：文本可长按拖选后单独复制，也可一键复制全文。
 * 顶/底吃进系统栏安全区——长内容滚动到底时最后一行不再被手势条/导航栏遮挡。
 */
@Composable
fun FullScreenCopyDialog(message: MessageEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Bottom
                        )
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text(
                        if (message.role == Role.USER) "用户消息" else "助手消息",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        copyMessageText(context, message.content)
                    }) { Text("复制全文") }
                }
                HorizontalDivider()
                SelectionContainer(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

internal fun copyMessageText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Flux", text))
}

/** 删除消息的确认弹窗（物理删除，不可恢复） */
@Composable
fun DeleteMessageDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("删除消息") },
        text = { Text("这条消息将从会话中删除，后续对话不再携带它，且无法恢复。确定删除？") },
    )
}
