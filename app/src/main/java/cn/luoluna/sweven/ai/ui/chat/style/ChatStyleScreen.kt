package cn.luoluna.sweven.ai.ui.chat.style

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.ui.chat.ChatImages
import cn.luoluna.sweven.ai.ui.chat.MarkdownText
import cn.luoluna.sweven.ai.ui.chat.rememberDataUrlThumb
import cn.luoluna.sweven.ai.ui.chat.plugin.ChatUiDefaults
import cn.luoluna.sweven.ai.ui.chat.plugin.ChatUiRegistry
import cn.luoluna.sweven.ai.ui.common.SectionCard
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 界面风格编辑页：**预览 → 预设 → 参数 → 聊天背景**，改动即时落库（写透）。
 *
 * 写透而非「草稿 + 保存」的理由：预览与聊天页共用同一份设置（AppSettings），所见即所得，
 * 也不存在「返回时忘了保存」的状态丢失。代价是拖动滑块会连续写 SharedPreferences
 * （JSON 约 1KB，apply() 异步，可接受）。
 *
 * 参数改动会把握着的皮肤切到 `custom`（chatStyleJson 生效），点预设 chips 则清空自定义回到预设。
 * 聊天背景**独立存放**：换预设不会把背景一起换掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatStyleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { (context.applicationContext as App).settings }
    val scope = rememberCoroutineScope()

    val chatPluginId by settings.chatPluginId.collectAsStateWithLifecycle()
    val chatStyleJson by settings.chatStyleJson.collectAsStateWithLifecycle()
    val backgroundJson by settings.chatBackgroundJson.collectAsStateWithLifecycle()
    val backgroundImage by settings.chatBackgroundImage.collectAsStateWithLifecycle()

    val plugin = ChatUiRegistry.resolve(chatPluginId ?: ChatUiRegistry.DEFAULT_ID)
    val styleOverride = remember(chatStyleJson, plugin.id) {
        if (plugin.id == ChatUiRegistry.CUSTOM_ID) ChatStyleCodec.decode(chatStyleJson) else null
    }
    val style = styleOverride ?: plugin.chatStyle ?: ChatStylePreset.of(plugin.id).config()
    val background = remember(backgroundJson) { ChatBackgroundCodec.decode(backgroundJson) }
    val isCustom = chatStyleJson != null

    /** 参数改动 = 立刻落库：切到 custom 皮肤，聊天页与预览同步生效 */
    fun applyStyle(newStyle: ChatStyleConfig) {
        settings.setChatStyleJson(ChatStyleCodec.encode(newStyle))
        settings.setChatPluginId(ChatUiRegistry.CUSTOM_ID)
    }

    fun applyBackground(newBackground: ChatBackgroundStyle) {
        settings.setChatBackgroundJson(ChatBackgroundCodec.encode(newBackground))
    }

    var styleJsonOpen by remember { mutableStateOf(false) }

    // 背景图片：选图 → 压缩成 data URL 落库（与用户头像同一存储形态），并自动切到「图片」类型
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                ChatImages.uriToDataUrl(context, uri, maxDim = 1280, quality = 80)?.let { url ->
                    settings.setChatBackgroundImage(url)
                    applyBackground(background.copy(kind = ChatBackgroundKind.IMAGE))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("界面风格") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "预览") {
                ChatStylePreview(style = style, background = background, backgroundImage = backgroundImage)
            }

            SectionCard(title = "预设风格") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatStylePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = !isCustom && plugin.id == preset.id,
                            onClick = {
                                settings.setChatStyleJson(null)
                                settings.setChatPluginId(preset.id)
                            },
                            label = { Text(preset.displayName) },
                        )
                    }
                }
                Text(
                    if (isCustom) {
                        "当前是自定义参数：改下面任何一项都会立刻生效（点上面的预设可恢复成预设样式）"
                    } else {
                        "点预设立即换风格；再调下面的参数就变成自定义"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(onClick = { styleJsonOpen = true }) { Text("编辑样式 JSON（高级）") }
            }

            ChatStyleParams(style = style, onChange = ::applyStyle)

            ChatBackgroundSection(
                background = background,
                backgroundImage = backgroundImage,
                onPickImage = {
                    pickBackground.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onClearImage = {
                    settings.setChatBackgroundImage(null)
                    applyBackground(background.copy(kind = ChatBackgroundKind.THEME))
                },
                onChange = ::applyBackground,
            )
        }
    }

    if (styleJsonOpen) {
        StyleJsonDialog(
            initial = chatStyleJson.orEmpty(),
            onDismiss = { styleJsonOpen = false },
            onSave = { json ->
                settings.setChatStyleJson(json)
                settings.setChatPluginId(ChatUiRegistry.CUSTOM_ID)
                styleJsonOpen = false
            },
            onReset = {
                settings.setChatStyleJson(null)
                settings.setChatPluginId(ChatUiRegistry.DEFAULT_ID)
                styleJsonOpen = false
            },
        )
    }
}

/**
 * 预览：**只用 [ChatBubble] + [ChatMessageRow] 绘制**（与真实消息同一个绘制核心），
 * 禁止在这里自绘气泡，否则预览会和聊天页漂移。
 */
@Composable
private fun ChatStylePreview(
    style: ChatStyleConfig,
    background: ChatBackgroundStyle,
    backgroundImage: String?,
) {
    ChatBackground(
        style = background,
        imageDataUrl = backgroundImage,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            ChatMessageRow(profile = style.slotFor(Role.USER).profile) {
                ChatBubble(style = style.slotFor(Role.USER).style) {
                    Text("帮我把这个方案压成三条要点", style = ChatUiDefaults.bodyTextStyle(style.user))
                }
            }
            ChatMessageRow(profile = style.slotFor(Role.ASSISTANT).profile) {
                ChatBubble(style = style.slotFor(Role.ASSISTANT).style) {
                    MarkdownText(
                        "好的：\n1. 默认改成扁平\n2. 消息组件统一\n3. 加背景配置",
                        style = ChatUiDefaults.bodyTextStyle(style.ai),
                    )
                }
            }
            ChatMessageRow(profile = style.slotFor(Role.TOOL).profile) {
                ChatBubble(style = style.slotFor(Role.TOOL).style) {
                    Text("工具结果 · web_search", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 参数调整：按角色（用户/助手/工具）编辑气泡与布局 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatStyleParams(style: ChatStyleConfig, onChange: (ChatStyleConfig) -> Unit) {
    val roles = listOf("用户", "助手", "工具")
    var roleIndex by rememberSaveable { mutableIntStateOf(0) }
    val bubble = when (roleIndex) {
        0 -> style.user
        1 -> style.ai
        else -> style.tool
    }
    // 工具消息沿用助手档案（见 ChatStyleConfig.slotFor）
    val profile = if (roleIndex == 0) style.userProfile else style.aiProfile

    fun setBubble(newBubble: BubbleStyle) {
        onChange(
            when (roleIndex) {
                0 -> style.copy(user = newBubble)
                1 -> style.copy(ai = newBubble)
                else -> style.copy(tool = newBubble)
            },
        )
    }

    fun setProfile(newProfile: RoleProfile) {
        onChange(
            if (roleIndex == 0) style.copy(userProfile = newProfile) else style.copy(aiProfile = newProfile)
        )
    }

    SectionCard(title = "参数调整") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            roles.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = roleIndex == index,
                    onClick = { roleIndex = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = roles.size),
                ) { Text(label) }
            }
        }
        StyleSlider("圆角", bubble.cornerRadius.value, 0f..28f, steps = 27) {
            setBubble(bubble.copy(cornerRadius = it.dp))
        }
        StyleSlider("切角", bubble.cutCornerSize.value, 0f..24f, steps = 23) {
            setBubble(bubble.copy(cutCornerSize = it.dp))
        }
        StyleSlider("左右内边距", bubble.paddingH.value, 0f..24f, steps = 23) {
            setBubble(bubble.copy(paddingH = it.dp))
        }
        StyleSlider("上下内边距", bubble.paddingV.value, 0f..20f, steps = 19) {
            setBubble(bubble.copy(paddingV = it.dp))
        }
        StyleSlider("描边宽度", bubble.strokeWidth.value, 0f..3f, steps = 5) {
            setBubble(bubble.copy(strokeWidth = it.dp))
        }
        StyleSlider("阴影", bubble.shadowElevation.value, 0f..8f, steps = 7) {
            setBubble(bubble.copy(shadowElevation = it.dp))
        }
        EnumDropdown("填充色", BubbleFillRole.entries, bubble.fill, ::fillLabel) {
            setBubble(bubble.copy(fill = it))
        }
        EnumDropdown("文字色", BubbleTextRole.entries, bubble.text, ::textLabel) {
            setBubble(bubble.copy(text = it))
        }
        EnumDropdown("描边", BubbleStrokeRole.entries, bubble.stroke, ::strokeLabel) {
            setBubble(bubble.copy(stroke = it))
        }
        SwitchRow("等宽字体", bubble.monospace) { setBubble(bubble.copy(monospace = it)) }
        SwitchRow("底部分隔线", bubble.dividerBottom) { setBubble(bubble.copy(dividerBottom = it)) }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("布局（${roles[roleIndex]}）", style = MaterialTheme.typography.labelMedium)
        EnumDropdown("对齐", RoleAlign.entries, profile.align, ::alignLabel) {
            setProfile(profile.copy(align = it))
        }
        StyleSlider(
            label = "最大宽度占比",
            value = profile.maxWidthFraction,
            range = 0.2f..1f,
            steps = 7,
            valueText = { String.format(Locale.ROOT, "%.0f%%", it * 100) },
        ) { setProfile(profile.copy(maxWidthFraction = it)) }
        SwitchRow("显示头像行", profile.showHeader) { setProfile(profile.copy(showHeader = it)) }
    }
}

/** 聊天背景：跟随主题 / 纯色 / 图片（图片 + 遮罩） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatBackgroundSection(
    background: ChatBackgroundStyle,
    backgroundImage: String?,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onChange: (ChatBackgroundStyle) -> Unit,
) {
    val kinds = ChatBackgroundKind.entries
    SectionCard(title = "聊天背景") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            kinds.forEachIndexed { index, kind ->
                SegmentedButton(
                    selected = background.kind == kind,
                    onClick = { onChange(background.copy(kind = kind)) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = kinds.size),
                ) { Text(kindLabel(kind)) }
            }
        }
        when (background.kind) {
            ChatBackgroundKind.THEME -> Text(
                "跟随主题底色，聊天页外观与改动前一致",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            ChatBackgroundKind.COLOR -> EnumDropdown(
                "背景色",
                BubbleFillRole.entries,
                background.color,
                ::fillLabel,
            ) {
                onChange(background.copy(color = it))
            }

            ChatBackgroundKind.IMAGE -> {
                val thumb by rememberDataUrlThumb(backgroundImage, maxDim = 320)
                val bmp = thumb
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .height(120.dp),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    TextButton(onClick = onPickImage) { Text(if (bmp == null) "选择图片" else "更换图片") }
                    if (backgroundImage != null) {
                        TextButton(onClick = onClearImage) { Text("清除图片") }
                    }
                }
                if (backgroundImage == null) {
                    Text(
                        "还没选图片，聊天页暂时按主题底色显示",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StyleSlider(
                    label = "图片遮罩",
                    value = background.dim,
                    range = 0f..1f,
                    steps = 9,
                    valueText = { String.format(Locale.ROOT, "%.0f%%", it * 100) },
                ) { onChange(background.copy(dim = it)) }
            }
        }
    }
}

// ---------------- 小控件 ----------------

/** 带数值显示的滑块（参数调整里所有 dp/比例参数共用） */
@Composable
private fun StyleSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueText: (Float) -> String = { it.toInt().toString() },
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(
                valueText(value),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

/** 开关行 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 枚举下拉：展示中文文案，值仍是枚举名（进 JSON 用枚举名）。选项列表由调用方给（枚举 entries） */
@Composable
private fun <T : Enum<T>> EnumDropdown(
    label: String,
    options: List<T>,
    value: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(labelOf(value)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(labelOf(option)) },
                        onClick = {
                            onSelect(option)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

private fun fillLabel(role: BubbleFillRole): String = when (role) {
    BubbleFillRole.PRIMARY -> "主色"
    BubbleFillRole.SECONDARY_CONTAINER -> "次色容器"
    BubbleFillRole.SURFACE_VARIANT -> "浅灰底"
    BubbleFillRole.SURFACE_CONTAINER_HIGH -> "浅灰高"
    BubbleFillRole.SURFACE -> "页面底色"
    BubbleFillRole.TRANSPARENT -> "透明"
}

private fun textLabel(role: BubbleTextRole): String = when (role) {
    BubbleTextRole.ON_PRIMARY -> "主色上文字"
    BubbleTextRole.ON_SECONDARY_CONTAINER -> "次色容器上文字"
    BubbleTextRole.ON_SURFACE -> "正文色"
    BubbleTextRole.ON_SURFACE_VARIANT -> "次要文字色"
}

private fun strokeLabel(role: BubbleStrokeRole): String = when (role) {
    BubbleStrokeRole.NONE -> "无描边"
    BubbleStrokeRole.OUTLINE -> "描边色"
    BubbleStrokeRole.OUTLINE_VARIANT -> "浅描边"
    BubbleStrokeRole.PRIMARY -> "主色描边"
}

private fun alignLabel(align: RoleAlign): String = when (align) {
    RoleAlign.START -> "左"
    RoleAlign.END -> "右"
    RoleAlign.CENTER -> "居中"
}

private fun kindLabel(kind: ChatBackgroundKind): String = when (kind) {
    ChatBackgroundKind.THEME -> "跟随主题"
    ChatBackgroundKind.COLOR -> "纯色"
    ChatBackgroundKind.IMAGE -> "图片"
}
