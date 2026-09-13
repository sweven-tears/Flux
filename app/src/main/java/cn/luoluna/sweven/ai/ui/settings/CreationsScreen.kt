package cn.luoluna.sweven.ai.ui.settings

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.ui.chat.FluxVideoPlayer
import cn.luoluna.sweven.ai.ui.chat.ImagePreviewDialog
import cn.luoluna.sweven.ai.ui.chat.rememberDataUrlBitmap
import cn.luoluna.sweven.ai.ui.chat.rememberDataUrlThumb
import cn.luoluna.sweven.ai.ui.chat.rememberGallerySaver
import cn.luoluna.sweven.ai.ui.chat.rememberVideoFrame
import cn.luoluna.sweven.ai.ui.common.openImageExternally
import cn.luoluna.sweven.ai.ui.common.openVideoExternally
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 网格中的一件创作：助手消息 imagesJson 里的一张图/一个视频（视频为 flux-video: 文件路径前缀） */
private data class CreationItem(
    val messageId: Long,
    val index: Int,
    val url: String,
    val createdAt: Long,
    val modelName: String?,
    /** 来源会话；0 = 局域网来源任务（无会话），不可跳转 */
    val conversationId: Long,
) {
    val key: String get() = "${messageId}_$index"
    val isVideo: Boolean get() = MessageEntity.isVideo(url)
    val videoPath: String? get() = if (isVideo) url.removePrefix(MessageEntity.VIDEO_PREFIX) else null
}

private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/**
 * 我的创作：集中管理 AI 生成的图像/视频。
 * 删除不是物理抹除，而是把消息里的 data URL 原位替换为「已清理」标记，
 * 聊天室对应位置显示占位块；批量下载走系统相册（Pictures/Flux）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CreationsScreen(onBack: () -> Unit, onOpenChat: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val scope = rememberCoroutineScope()
    val messageDao = remember { app.database.messageDao() }
    val messages by remember { messageDao.observeCreations() }
        .collectAsStateWithLifecycle(emptyList())
    // 会话标题表：预览时显示来源并支持跳回聊天（会话已删除则不显示入口）
    val conversations by remember { app.database.conversationDao().observeAll() }
        .collectAsStateWithLifecycle(emptyList())
    val conversationTitles = remember(conversations) {
        conversations.associate { it.id to it.title }
    }
    // 文件打开方式（设置项）：点击媒体时是否直接交系统应用查看
    val imageOpenExternal by app.settings.imageOpenExternal.collectAsStateWithLifecycle()
    val videoOpenExternal by app.settings.videoOpenExternal.collectAsStateWithLifecycle()
    val saveToGallery = rememberGallerySaver()

    // 展开为网格项：一张图/一个视频一项；已清理的占位标记不再收录
    val items = remember(messages) {
        messages.flatMap { m ->
            m.images.mapIndexedNotNull { idx, url ->
                if (url.startsWith("data:") || MessageEntity.isVideo(url)) {
                    CreationItem(m.id, idx, url, m.createdAt, m.modelName, m.conversationId)
                } else {
                    null
                }
            }
        }
    }

    var tab by remember { mutableStateOf(0) } // 0 全部 / 1 图像 / 2 视频
    val filtered = when (tab) {
        1 -> items.filter { !it.isVideo }
        2 -> items.filter { it.isVideo }
        else -> items
    }

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var previewKey by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "已选 ${selected.size} 项" else "我的创作") },
                navigationIcon = {
                    IconButton(onClick = { if (selecting) exitSelection() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                val urls = filtered.filter { it.key in selected }.map { it.url }
                                saveToGallery(urls)
                                exitSelection()
                            },
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = "下载到相册")
                        }
                        IconButton(
                            enabled = selected.isNotEmpty(),
                            onClick = { confirmDelete = true },
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                        IconButton(onClick = {
                            selected = if (selected.size == filtered.size) {
                                emptySet()
                            } else {
                                filtered.map { it.key }.toSet()
                            }
                        }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "全选/全不选")
                        }
                    } else if (items.isNotEmpty()) {
                        TextButton(onClick = { selecting = true }) { Text("选择") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("全部") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("图像") })
                FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("视频") })
            }
            if (filtered.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Text(
                        if (tab == 2) "暂无视频创作，去聊天页用视频生成模型试试" else "还没有创作，去聊天页用生图模型生成第一张图吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filtered, key = { it.key }) { item ->
                        CreationThumb(
                            item = item,
                            selecting = selecting,
                            selected = item.key in selected,
                            onClick = {
                                if (selecting) {
                                    selected = if (item.key in selected) selected - item.key else selected + item.key
                                } else if (item.isVideo && videoOpenExternal) {
                                    item.videoPath?.let { openVideoExternally(context, java.io.File(it)) }
                                } else if (!item.isVideo && imageOpenExternal) {
                                    openImageExternally(context, item.url)
                                } else {
                                    previewKey = item.key
                                }
                            },
                            onLongClick = {
                                if (!selecting) {
                                    selecting = true
                                    selected = setOf(item.key)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // 大图/视频预览：进入时按 key 找当前项（删除后自动关闭）；视频用内嵌播放器，图片解码完成再弹；
    // 有来源会话时显示「打开会话」跳回对应聊天（局域网来源/会话已删除不显示）
    previewKey?.let { key ->
        val item = filtered.firstOrNull { it.key == key }
        if (item == null) {
            previewKey = null
        } else if (item.isVideo) {
            val sourceTitle = item.conversationId.takeIf { it != 0L }?.let { conversationTitles[it] }
            AlertDialog(
                onDismissRequest = { previewKey = null },
                confirmButton = {
                    TextButton(onClick = { previewKey = null }) { Text("关闭") }
                },
                dismissButton = if (sourceTitle != null) {
                    {
                        TextButton(onClick = {
                            previewKey = null
                            onOpenChat(item.conversationId)
                        }) { Text("打开会话") }
                    }
                } else {
                    null
                },
                title = {
                    Column {
                        Text(item.modelName ?: "视频")
                        if (sourceTitle != null) {
                            Text(
                                "来自会话：$sourceTitle",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                text = {
                    FluxVideoPlayer(
                        item.videoPath ?: "",
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                },
            )
        } else {
            val bitmap = rememberDataUrlBitmap(item.url).value
            if (bitmap != null) {
                val sourceTitle = item.conversationId.takeIf { it != 0L }?.let { conversationTitles[it] }
                ImagePreviewDialog(
                    bitmap = bitmap,
                    onDismiss = { previewKey = null },
                    footer = if (sourceTitle != null) {
                        {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color.Black.copy(alpha = 0.6f),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 16.dp),
                                ) {
                                    Text(
                                        "来自：$sourceTitle",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                    )
                                    TextButton(onClick = {
                                        previewKey = null
                                        onOpenChat(item.conversationId)
                                    }) { Text("打开会话") }
                                }
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("清理创作") },
            text = {
                Text(
                    "选中的 ${selected.size} 件作品将被清理，聊天室对应位置会显示「资源已被清理」占位。" +
                        "此操作不可恢复，确定继续？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val targets = items.filter { it.key in selected }
                    confirmDelete = false
                    exitSelection()
                    scope.launch {
                        // 按消息分组，把对应位置的原条目（data URL / 视频文件）原位替换为已清理标记
                        targets.groupBy { it.messageId }.forEach { (mid, parts) ->
                            val msg = messages.firstOrNull { it.id == mid } ?: return@forEach
                            val imgs = msg.images.toMutableList()
                            parts.forEach { p ->
                                if (p.index in imgs.indices) {
                                    val old = imgs[p.index]
                                    // 视频清理时同步删除私有目录里的文件（体积大，不留孤儿）
                                    if (MessageEntity.isVideo(old)) {
                                        runCatching {
                                            java.io.File(old.removePrefix(MessageEntity.VIDEO_PREFIX)).delete()
                                        }
                                    }
                                    imgs[p.index] = MessageEntity.DELETED_IMAGE
                                }
                            }
                            messageDao.update(msg.copy(imagesJson = MessageEntity.imagesJsonOf(imgs)))
                        }
                        Toast.makeText(context, "已清理 ${targets.size} 件作品", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CreationThumb(
    item: CreationItem,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    // 视频取封面帧（视频文件无法用 data URL 解码），失败显示占位
    val videoFrame = if (item.isVideo) rememberVideoFrame(item.videoPath ?: "") else null
    val bitmap = if (item.isVideo) videoFrame else rememberDataUrlThumb(item.url).value
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (item.isVideo) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "视频",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .padding(4.dp),
            )
        }
        if (selecting) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        },
                    )
                    .padding(2.dp),
            ) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(2.dp),
                )
            }
        }
    }
}
