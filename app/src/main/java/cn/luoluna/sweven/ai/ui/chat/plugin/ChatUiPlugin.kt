package cn.luoluna.sweven.ai.ui.chat.plugin

import android.net.Uri
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import cn.luoluna.sweven.ai.data.db.ConversationEntity
import cn.luoluna.sweven.ai.data.db.FolderEntity
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.PresetEntity
import cn.luoluna.sweven.ai.data.repo.SmartAggregateCapability
import cn.luoluna.sweven.ai.ui.chat.ChatTarget
import cn.luoluna.sweven.ai.ui.chat.ChatViewModel
import cn.luoluna.sweven.ai.ui.chat.ConversationHit
import cn.luoluna.sweven.ai.ui.chat.DrawerState as ChatDrawerData
import cn.luoluna.sweven.ai.ui.chat.PickerModel
import kotlinx.coroutines.CoroutineScope

/**
 * 聊天页 UI 插件：8 个槽位均为可空 composable，null 表示回退到默认实现。
 * 皮肤类插件只覆写个别槽位（如只换 messageBubble）即可，其余区域保持默认。
 *
 * 职责边界：状态与发送/停止/流式逻辑全部在宿主（ChatScreen + ChatViewModel），
 * 插件只负责绘制；长按详情、图片预览、模型选择等经 [ChatUiEnv] 回调走宿主对话框，
 * 任何插件都不会丢功能。
 */
interface ChatUiPlugin {
    /** 唯一 id，用于持久化选择 */
    val id: String

    /** 展示名（界面风格选择器里显示） */
    val displayName: String

    /** 一句话描述 */
    val description: String

    /** 会话抽屉（文件夹/预设/会话列表/搜索） */
    val drawer: (@Composable (ChatUiEnv) -> Unit)? get() = null

    /** 顶栏（菜单按钮 + 模型名标题 + 操作） */
    val topBar: (@Composable (ChatUiEnv) -> Unit)? get() = null

    /** 无消息时的引导页 */
    val emptyState: (@Composable (ChatUiEnv) -> Unit)? get() = null

    /**
     * 消息列表容器（含流式项/错误条/提示条）。
     * 覆写此槽位即接管滚动责任：宿主仍提供 [ChatUiEnv.listState] 与初始滚动，
     * 但流式跟随滚动等列表行为由插件实现（参考默认实现）。
     */
    val messageList: (@Composable (ChatUiEnv, androidx.compose.foundation.layout.PaddingValues) -> Unit)?
        get() = null

    /** 单条消息气泡（用户/助手两态） */
    val messageBubble: (@Composable (ChatUiEnv, MessageEntity) -> Unit)? get() = null

    /** 流式输出气泡（思考+内容+光标） */
    val streamingBubble: (@Composable (ChatUiEnv) -> Unit)? get() = null

    /** 待发送附件缩略行 */
    val attachmentRow: (@Composable (ChatUiEnv) -> Unit)? get() = null

    /** 输入栏（思考深度/加图/输入框/发送停止） */
    val inputBar: (@Composable (ChatUiEnv) -> Unit)? get() = null
}

/**
 * 宿主提供给插件的稳定 API 面：已收集好的状态 + 动作回调 + 可复用组件入口。
 * 插件不需要自己 collectAsState，也不应直接持有 ViewModel 之外的状态源。
 */
class ChatUiEnv(
    // ---- 已收集状态 ----
    val current: ConversationEntity?,
    val messages: List<MessageEntity>,
    val currentTarget: ChatTarget?,
    /** 当前单模型目标（SmartAi 时为 null） */
    val currentPicker: PickerModel?,
    val isSmartAi: Boolean,
    val smartAiCapability: SmartAggregateCapability,
    /** 会话原模型不可用、当前展示回退目标 */
    val modelFallback: Boolean,
    val streaming: Boolean,
    val error: String?,
    val notice: String?,
    val contextEstimateText: String?,
    /** 上下文用量级别：0 正常 / 1 接近(≥80%) / 2 超出(≥100%)，-1 表示无指示 */
    val contextEstimateLevel: Int,
    val drawer: ChatDrawerData,
    val searchQuery: String,
    val searchResults: List<ConversationHit>,
    val effectiveThinkingDepth: String,
    val showReasoning: Boolean,
    val showImageInput: Boolean,

    // ---- 宿主持有的 UI 状态（插件读写） ----
    val input: String,
    val onInputChange: (String) -> Unit,
    val attachments: List<Uri>,
    val editAttachments: List<String>,
    val editingMessage: MessageEntity?,
    /** 抽屉当前选中的分组 tab（null = 「聊天」默认分组） */
    val selectedFolderTab: Long?,
    val onSelectFolderTab: (Long?) -> Unit,

    // ---- 宿主对象 ----
    val vm: ChatViewModel,
    val drawerState: DrawerState,
    val scope: CoroutineScope,
    val listState: LazyListState,

    // ---- 输入区动作 ----
    val pickImages: () -> Unit,
    /** 发送（或编辑态下替换重发）；宿主已处理清空输入框/附件 */
    val send: () -> Unit,
    val removeAttachment: (Uri) -> Unit,
    val removeEditAttachment: (String) -> Unit,
    val cancelEditing: () -> Unit,

    // ---- 宿主对话框/弹层回调（任何插件不丢功能） ----
    val showDetail: (MessageEntity) -> Unit,
    val showImage: (ImageBitmap) -> Unit,
    val openModelPicker: () -> Unit,
    val openSystemPrompt: () -> Unit,
    val openPluginPicker: () -> Unit,
    val showMessageActions: (MessageEntity) -> Unit,
    val copyMessage: (MessageEntity) -> Unit,

    // ---- 抽屉动作 ----
    val showNewFolder: () -> Unit,
    val renameFolder: (FolderEntity) -> Unit,
    val deleteFolder: (FolderEntity) -> Unit,
    val editPreset: (folderId: Long, existing: PresetEntity?) -> Unit,
    val moveConversation: (ConversationEntity) -> Unit,

    // ---- 导航 ----
    val onGoProviders: () -> Unit,
    val onOpenSettings: () -> Unit,
)
