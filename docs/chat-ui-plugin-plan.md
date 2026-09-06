# 聊天页插件化（可自由设计 UI）— 实施计划

> 状态：方案已确认、暂缓执行。下次开工直接按本文档实施，无需重新调研。
> 需求：把聊天页改成"可自由设计的插件功能模式"，**其他页面（厂商/局域网等）不做任何插件化改造**。
> 基于 v0.4.0 代码现状（行号以 2026-09 时的代码为准，若代码有改动需重新定位）。

## 一、结论与路线

把 ChatScreen（777 行）重构为**宿主骨架 + 可替换槽位（slot）体系**：

- 聊天页拆出 8 个可插拔区域（槽位），插件按需覆写任意槽位，未覆写的自动回退默认实现；
- 宿主统一持有状态、发送逻辑和全部对话框，插件只负责绘制；
- 内置 3 个风格插件验证体系可真正换脸，聊天页顶栏菜单切换、选择持久化；
- 注册表为运行时可注册设计，为后续 JSON 导入皮肤、DexClassLoader 外部插件预留挂载点，届时页面零改动。

其他路线（页面区块编排用于 LAN/厂商页、JSON 描述式全页 UI）已讨论，按用户要求**不采用**，本计划只针对聊天页。

## 二、现状要点（实施前需知）

- 单 Activity + Navigation Compose，聊天路由 `chat`，见 `ui/navigation/PhoneRouterApp.kt`。
- `ui/chat/ChatScreen.kt`（777 行）：ModalNavigationDrawer 抽屉 + Scaffold + Column(imePadding) 骨架；8 个 `collectAsStateWithLifecycle` 状态；10 个本地 UI 状态；6 种对话框 + ModelPickerSheet + ImagePreviewDialog 全部在宿主。
- `ui/chat/ChatViewModel.kt`（436 行）：conversations/current/messages/pickerModels/currentModel/streaming/streamingText/streamingReasoning/error/drawerState 等流；send() 内含流式解析（ThinkParser、reasoning 合并）、自动建会话、写回模型等业务——**业务逻辑不动，本次只动 UI 层**。
- 持久化模式：`data/AppSettings.kt` = SharedPreferences + MutableStateFlow，已存 4 个键；无 Room 迁移需求。
- 无 DI 框架（App.kt 手写服务定位器）、无动态代码加载机制。

## 三、设计

### 1. 槽位划分（对应 ChatScreen.kt 现有代码行）

| 槽位 | 现有实现位置 | 说明 |
|---|---|---|
| `drawer` | L131-215 | 会话抽屉（文件夹/预设/会话列表） |
| `topBar` | L218-241 | 顶栏（菜单按钮 + 模型名标题） |
| `emptyState` | L249-276 | 无消息时的引导页 |
| `messageList` | L278-327 | 消息列表容器（含流式项/错误条） |
| `messageBubble` | L497-553 | 单条消息气泡（用户/助手两态） |
| `streamingBubble` | L555-576 | 流式输出气泡（思考+内容+光标） |
| `attachmentRow` | L331-344 | 待发送附件缩略行 |
| `inputBar` | L347-387 | 输入栏（加图/输入框/发送停止） |

### 2. 核心接口（新包 `ui/chat/plugin/`）

**ChatUiPlugin** —— 数据类形态的槽位集，槽位为可空 composable lambda，null 即回退默认（做一个"只换气泡样式"的皮肤只需几行）：

```kotlin
class ChatUiPlugin(
    val id: String, val name: String,
    val drawer: (@Composable ChatUiEnv.() -> Unit)? = null,
    val topBar: (@Composable ChatUiEnv.() -> Unit)? = null,
    val emptyState: (@Composable ChatUiEnv.() -> Unit)? = null,
    val messageList: (@Composable ChatUiEnv.() -> Unit)? = null,
    val messageBubble: (@Composable ChatUiEnv.(MessageEntity) -> Unit)? = null,
    val streamingBubble: (@Composable ChatUiEnv.(reasoning: String, content: String) -> Unit)? = null,
    val attachmentRow: (@Composable ChatUiEnv.(List<Uri>, remove: (Uri) -> Unit) -> Unit)? = null,
    val inputBar: (@Composable ChatUiEnv.() -> Unit)? = null,
)
```

**ChatUiEnv** —— 给插件的稳定 API 面：
- 宿主已收集的 8 个状态（current / messages / currentModel / streaming / streamingText / streamingReasoning / error / drawer）；
- `vm: ChatViewModel`（发送/建会话/文件夹/预设等动作）；
- `drawerState` + `scope`（开合抽屉）；
- 输入区共享状态：`input`、`attachments` 的 MutableState 与 `pickImages()` / `send()` 动作；
- 打开宿主对话框的回调：`showDetail(msg)` / `showImage(bmp)` / `openModelPicker()`；
- 可复用公共组件：`MarkdownText`、`ReasoningSection`、`MessageImage`。

插件不需要自己 collectAsState（避免订阅行为差异）。

**ChatUiRegistry** —— `mutableStateOf` 支撑的注册表（`register(plugin)` / `observe()`），运行时注册即触发 UI 更新。内置插件静态注册；这是未来外部插件/导入皮肤的挂载点。

### 3. 内置插件（既是功能也是示范与模板）

- `default` 默认风格：现有实现原样迁入，行为与改造前完全一致；
- `flat` 平铺风格：助手消息无气泡全宽 Markdown、用户消息浅色气泡右对齐、间距更紧凑（ChatGPT 式）；
- `mono` 终端风格：等宽字体、细边框气泡、单线输入栏（验证字体/颜色/形状层面的自由度）。

### 4. 切换入口与持久化

- ChatScreen 顶栏加 MoreVert 菜单 → "界面风格"对话框：列出注册表内插件，单选即时生效；
- AppSettings 新增 key `chat_ui_plugin`（String，默认 "default"），沿用现有 SharedPreferences + StateFlow 模式。

## 四、施工步骤

1. 新增 `ui/chat/plugin/ChatUiPlugin.kt`（ChatUiPlugin + ChatUiEnv）；
2. 新增 `ui/chat/plugin/ChatUiRegistry.kt`；
3. 新增 `ui/chat/plugin/ChatUiDefaults.kt`：把 ChatScreen.kt 中的 MessageBubble、StreamingBubble、MessageImage、AttachmentThumb、空态、TopBar、抽屉内容、输入栏原样抽为 internal 公共实现，default 插件槽位引用它们；`copyText`、图片位图加载等辅助一并迁入；
4. 新增 `ui/chat/plugin/BuiltInPlugins.kt`：default / flat / mono；
5. 新增 `ui/chat/plugin/PluginPickerDialog.kt`：风格选择对话框；
6. 重构 `ui/chat/ChatScreen.kt`：保留宿主职责——状态收集、自动滚动 LaunchedEffect、pickImages launcher、6 个对话框 + ModelPickerSheet + ImagePreviewDialog、ModalNavigationDrawer/Scaffold/Column 骨架与 imePadding；各渲染点改为 `activePlugin.槽位 ?: 默认实现`；顶栏挂风格菜单。预计 777 行降到 ~450 行；
7. 扩展 `data/AppSettings.kt`：`chatPluginId` + `setChatPluginId()`；
8. 不改 Room、导航、ChatViewModel 业务逻辑及其他任何页面。

## 五、关键决策

- **状态归宿主、插件只画 UI**：输入文字、附件列表由宿主持有并经 Env 共享，保证任何插件下发送/停止/流式逻辑零风险；
- **自定义 messageList 槽位接管滚动责任**（自动滚底逻辑在宿主列表内），在文档注释中说明；
- 长按消息看详情、点图片预览等功能经 Env 回调走宿主对话框，任何插件都不丢功能；
- 本次不做但已预留：JSON 参数化皮肤（气泡圆角/字号/配色 → 生成 ChatUiPlugin）、DexClassLoader 外部插件加载器——两者都只往 Registry 注册，页面零改动。

## 六、验证

- `gradlew :app:assembleDebug` 编译通过；
- 手动验证：不选插件时与现状完全一致；三个风格切换即时生效、重启后保留；流式输出、思考折叠、长按详情、图片选择/预览、抽屉全部分组操作、模型选择在各风格下均正常。
