package cn.luoluna.sweven.ai.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.Role
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

    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val pickerModels by vm.pickerModels.collectAsStateWithLifecycle()
    val currentModel by vm.currentModel.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val streamingText by vm.streamingText.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var pickerOpen by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(listOf<android.net.Uri>()) }
    var input by remember { mutableStateOf("") }
    var detailMessage by remember { mutableStateOf<MessageEntity?>(null) }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris ->
        if (uris.isNotEmpty()) attachments = uris
    }

    val listState = rememberLazyListState()
    val extraItems = (if (streaming) 1 else 0) + (if (error != null) 1 else 0)
    LaunchedEffect(messages.size, streamingText, error) {
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
                    IconButton(onClick = {
                        vm.newConversation()
                        scope.launch { drawerState.close() }
                    }) { Icon(Icons.Filled.Add, contentDescription = "新建对话") }
                }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(conversations, key = { it.id }) { conv ->
                        NavigationDrawerItem(
                            label = { Text(conv.title, maxLines = 1) },
                            selected = conv.id == current?.id,
                            onClick = {
                                vm.selectConversation(conv.id)
                                scope.launch { drawerState.close() }
                            },
                            badge = {
                                IconButton(onClick = { vm.deleteConversation(conv.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除对话",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                    }
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
                                currentModel?.aggregateId ?: "选择模型",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                            )
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换模型")
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
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp, vertical = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            MessageBubble(
                                message = msg,
                                onLongPress = { detailMessage = msg },
                            )
                        }
                        if (streaming) {
                            item(key = "streaming") {
                                AssistantBubble(
                                    content = streamingText + " ▍",
                                )
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: MessageEntity, onLongPress: () -> Unit) {
    if (message.role == Role.USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                if (message.images.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        message.images.forEach { url -> MessageImage(url) }
                    }
                }
                if (message.content.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress),
                    ) {
                        Text(
                            message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.widthIn(max = 320.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress),
                ) {
                    MarkdownText(
                        message.content,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(content: String) {
    Row(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
        ) {
            Text(
                content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MessageImage(url: String) {
    val bitmap = rememberDataUrlBitmap(url).value
    Surface(shape = RoundedCornerShape(10.dp)) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(10.dp)),
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
