package cn.luoluna.sweven.ai.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.ConversationDao
import cn.luoluna.sweven.ai.data.db.ConversationEntity
import cn.luoluna.sweven.ai.data.db.FolderDao
import cn.luoluna.sweven.ai.data.db.FolderEntity
import cn.luoluna.sweven.ai.data.db.MessageDao
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.ModelDao
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ModelType
import cn.luoluna.sweven.ai.data.db.ref
import cn.luoluna.sweven.ai.data.db.PresetDao
import cn.luoluna.sweven.ai.data.db.PresetEntity
import cn.luoluna.sweven.ai.data.db.ProviderDao
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.data.db.SmartRouteGroupDao
import cn.luoluna.sweven.ai.data.db.SmartRouteGroupEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteMemberDao
import cn.luoluna.sweven.ai.data.repo.FailureKind
import cn.luoluna.sweven.ai.data.repo.ModelAbnormalRecorder
import cn.luoluna.sweven.ai.data.repo.ModelRoute
import cn.luoluna.sweven.ai.data.repo.SmartAggregateCapability
import cn.luoluna.sweven.ai.data.repo.RouteGroupTarget
import cn.luoluna.sweven.ai.data.repo.SmartAi
import cn.luoluna.sweven.ai.data.repo.SmartCandidate
import cn.luoluna.sweven.ai.data.repo.SmartEvent
import cn.luoluna.sweven.ai.data.repo.SmartHealth
import cn.luoluna.sweven.ai.data.repo.SmartPolicy
import cn.luoluna.sweven.ai.data.repo.SmartRouteExecutor
import cn.luoluna.sweven.ai.data.repo.aggregateCapability
import cn.luoluna.sweven.ai.data.repo.eligibleCandidates
import cn.luoluna.sweven.ai.data.repo.featuresOfHistory
import cn.luoluna.sweven.ai.data.repo.intersectCapability
import cn.luoluna.sweven.ai.server.Notifications
import cn.luoluna.sweven.ai.server.VideoJobManager
import cn.luoluna.sweven.ai.data.repo.isSelfGatewayUrl
import cn.luoluna.sweven.ai.data.repo.modalityLabel
import cn.luoluna.sweven.ai.data.repo.orderCandidates
import cn.luoluna.sweven.ai.net.AdvancedParams
import cn.luoluna.sweven.ai.net.ChatEvent
import cn.luoluna.sweven.ai.net.HttpApiException
import cn.luoluna.sweven.ai.net.ProviderClient
import cn.luoluna.sweven.ai.net.ProviderProtocol
import cn.luoluna.sweven.ai.net.UsageInfo
import cn.luoluna.sweven.ai.net.ThinkParser
import cn.luoluna.sweven.ai.net.ToolCall
import cn.luoluna.sweven.ai.net.applyBuiltinTools
import cn.luoluna.sweven.ai.net.applyLocalTools
import cn.luoluna.sweven.ai.net.applyThinkingDepth
import cn.luoluna.sweven.ai.net.buildChatBody
import cn.luoluna.sweven.ai.net.buildMessagesJsonEx
import cn.luoluna.sweven.ai.net.ChatJson
import cn.luoluna.sweven.ai.net.ChatMessageInput
import cn.luoluna.sweven.ai.tools.ApprovalRequest
import cn.luoluna.sweven.ai.tools.FluxTool
import cn.luoluna.sweven.ai.tools.ToolApproval
import cn.luoluna.sweven.ai.tools.ToolRegistry
import cn.luoluna.sweven.ai.tools.custom.CustomToolLoader
import cn.luoluna.sweven.ai.net.buildMessagesJson
import cn.luoluna.sweven.ai.net.estimateMessageTokens
import cn.luoluna.sweven.ai.net.estimateTextTokens
import cn.luoluna.sweven.ai.net.mergeCustomBody
import cn.luoluna.sweven.ai.net.parseCustomHeaders
import cn.luoluna.sweven.ai.net.trimToBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 模型选择器条目：厂商 + 模型 */
data class PickerModel(val provider: ProviderEntity, val model: ModelEntity) {
    /** 局域网聚合 id（按模型 id） */
    val aggregateId: String get() = "${provider.alias}/${model.ref}"
    /** 展示名（优先别名） */
    val displayName: String get() = model.alias?.takeIf { it.isNotBlank() } ?: model.ref
    val displayId: String get() = "${provider.alias}/$displayName"
    /** 模型是否处于启用状态（禁用模型仅供预设编辑回显，聊天选择器不出现） */
    val enabled: Boolean get() = model.enabled
}

/** 当前生效的聊天目标：单个模型、flux-auto 聚合或路由分组 */
sealed interface ChatTarget {
    data class Single(val picker: PickerModel) : ChatTarget
    data object SmartAi : ChatTarget

    /** 路由分组（flux-<groupKey>）：组内成员按优先级与健康度自动切换 */
    data class Group(val group: SmartRouteGroupEntity) : ChatTarget
}

/** 抽屉展示状态：文件夹 + 各文件夹的预设对话与会话 */
data class DrawerState(
    val folders: List<FolderEntity> = emptyList(),
    val presetsByFolder: Map<Long, List<PresetEntity>> = emptyMap(),
    val conversationsByFolder: Map<Long?, List<ConversationEntity>> = emptyMap(),
)

/** 会话搜索命中条目：标题命中时 snippet 为 null，否则为首条命中消息的内容片段 */
data class ConversationHit(val conversation: ConversationEntity, val snippet: String?)

/** 聊天页错误：错误条展示 [message]，半窗详情可展开状态码与原始响应体 */
class ChatError(
    message: String,
    val httpCode: Int? = null,
    rawBody: String? = null,
    /** 失败分类（仅智能路由路径给出）：错误条据此区分「请求有问题」与「上游都挂了」 */
    val kind: FailureKind? = null,
) {
    private val redactedMessage = cn.luoluna.sweven.ai.net.SensitiveDataRedactor
        .redactUpstreamError(message)
    val message: String get() = redactedMessage
    val rawBody: String? = rawBody?.let {
        cn.luoluna.sweven.ai.net.SensitiveDataRedactor.redactUpstreamError(it)
    }

    /** 复制用的完整错误文本：状态码 + 脱敏后的上游响应体（缺省回退 message） */
    fun detailText(): String = buildString {
        httpCode?.let { append("HTTP $it\n\n") }
        if (rawBody.isNullOrBlank()) append(message) else append(rawBody)
    }
}

/** 聊天页上下文用量指示：按当前会话与目标模型的粗估 token；窗口未知时 window 为 null */
data class ContextEstimate(val usedTokens: Int, val window: Int?)

/** 工具名集合 → JSON 数组文本（空集合存 "[]"：与「未设置」区分，表示明确不启用任何工具） */
private fun encodeToolNames(names: Set<String>): String =
    ChatJson.json.encodeToString(names.sorted())

/** JSON 数组文本 → 工具名集合；null 或解析失败返回 null（视为未设置，回落全局默认） */
private fun decodeToolNames(json: String?): Set<String>? =
    json?.let { raw ->
        runCatching { ChatJson.json.decodeFromString<List<String>>(raw).toSet() }.getOrNull()
    }

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app as App
    private val conversationDao: ConversationDao = ctx.database.conversationDao()
    private val messageDao: MessageDao = ctx.database.messageDao()
    private val providerDao: ProviderDao = ctx.database.providerDao()
    private val modelDao: ModelDao = ctx.database.modelDao()
    private val folderDao: FolderDao = ctx.database.folderDao()
    private val presetDao: PresetDao = ctx.database.presetDao()
    private val smartRouteGroupDao: SmartRouteGroupDao = ctx.database.smartRouteGroupDao()
    private val smartRouteMemberDao: SmartRouteMemberDao = ctx.database.smartRouteMemberDao()
    private val client: ProviderClient = ctx.providerClient
    private val router = ctx.modelRouter
    private val settings = ctx.settings
    private val smartExecutor = SmartRouteExecutor(client, ModelAbnormalRecorder(ctx.database))
    private val approvalOwner = ToolApproval.newOwner()

    /** This ViewModel's approvals belong only to this chat instance. */
    val toolApprovalOwner: ToolApproval.Owner get() = approvalOwner

    init {
        // A ViewModel can outlive the composable during navigation; cancel only its tickets when cleared.
        viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion {
            ToolApproval.disposeOwner(approvalOwner)
        }
    }

    /** 进行中的视频生成任务数（聊天页「生成中」提示） */
    val activeVideoJobs: StateFlow<Int> = VideoJobManager.get(app).observeActiveCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val currentId = MutableStateFlow<Long?>(null)

    /** 尚无会话时设置的思考深度，待会话创建后写入 */
    private val _pendingThinkingDepth = MutableStateFlow<String?>(null)

    val conversations: StateFlow<List<ConversationEntity>> =
        conversationDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val current: StateFlow<ConversationEntity?> =
        combine(conversations, currentId) { list, id -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages: StateFlow<List<MessageEntity>> =
        currentId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else messageDao.observeForConversation(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pickerModels: StateFlow<List<PickerModel>> =
        combine(providerDao.observeAll(), modelDao.observeEnabled()) { providers, models ->
            buildPickerModels(providers, models, onlyEnabledProviders = true)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 全部模型（含禁用模型与停用厂商）：预设编辑弹窗使用，避免预设模型已禁用时编辑一次就被静默清空 */
    val allPickerModels: StateFlow<List<PickerModel>> =
        combine(providerDao.observeAll(), modelDao.observeAll()) { providers, models ->
            buildPickerModels(providers, models, onlyEnabledProviders = false)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val drawerState: StateFlow<DrawerState> =
        combine(
            conversations,
            folderDao.observeAll(),
            presetDao.observeAll(),
        ) { convs, folders, presets ->
            DrawerState(
                folders = folders,
                presetsByFolder = presets.groupBy { it.folderId },
                conversationsByFolder = convs.groupBy { it.folderId },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DrawerState())

    /** 上次使用的模型（全局记忆） */
    private val lastModel: StateFlow<Pair<Long, String>?> =
        combine(settings.lastModelProviderId, settings.lastModelName) { pid, name ->
            if (pid != null && !name.isNullOrBlank()) pid to name else null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 当前生效的思考深度：会话自身设置优先，否则取尚未落库的暂存值 */
    val thinkingDepth: StateFlow<String?> =
        combine(current, _pendingThinkingDepth) { conv, pending ->
            conv?.thinkingDepth ?: pending
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 尚无会话时勾选的工具名，待会话创建后写入（与思考深度同模式） */
    private val _pendingTools = MutableStateFlow<Set<String>?>(null)

    /**
     * 当前会话生效的工具名集合：会话自身设置优先（"[]" = 明确不启用），
     * 未设置（null）时回落到「工具中心的全局默认（内置工具）+ 全部已启用的自定义工具」，都没有则不启用。
     */
    val selectedToolNames: StateFlow<Set<String>> =
        combine(
            current,
            _pendingTools,
            settings.defaultToolIds,
            CustomToolLoader.enabledNames,
        ) { conv, pending, defaults, customNames ->
            pending
                ?: decodeToolNames(conv?.toolsJson)
                ?: ((decodeToolNames(defaults) ?: emptySet()) + customNames)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 当前会话勾选且已注册的工具（发给模型的 tools 数组来源） */
    val selectedTools: StateFlow<List<FluxTool>> =
        selectedToolNames.map { ToolRegistry.byNames(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 设置当前会话勾选的工具；尚无会话时先暂存，会话创建时写入 */
    fun setTools(names: Set<String>) {
        val convId = currentId.value
        if (convId == null) {
            _pendingTools.value = names
        } else {
            viewModelScope.launch { conversationDao.setTools(convId, encodeToolNames(names)) }
        }
    }

    /**
     * flux-auto 候选合集（providerId to modelName）：全部启用厂商 × 启用 chat/图像输出模型，
     * 按厂商配置顺序 + 模型顺序枚举；实际发哪几个由 streamSmart 按请求模态筛出。
     */
    private val smartChain: StateFlow<List<Pair<Long, String>>> =
        combine(
            providerDao.observeAll(),
            modelDao.observeAll(),
        ) { providers, models ->
            providers.filter { it.enabled }.flatMap { provider ->
                models.filter {
                    it.providerId == provider.id && it.enabled &&
                        (it.type == ModelType.CHAT || it.outputImage)
                }.map { provider.id to it.ref }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 聊天模型选择器是否显示 smartAi 条目 */
    val smartAiAvailable: StateFlow<Boolean> =
        smartChain.map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** smartAi 聚合能力（候选链的模态/能力并集）：SmartAi 目标下附件按钮与思考深度的显示依据 */
    val smartAiCapability: StateFlow<SmartAggregateCapability> =
        combine(smartChain, modelDao.observeAll()) { chain, models ->
            aggregateCapability(
                chain.mapNotNull { (providerId, modelName) ->
                    models.firstOrNull { it.providerId == providerId && it.ref == modelName }
                },
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, aggregateCapability(emptyList()))

    /**
     * 可选用的路由分组：启用且至少有一个可用成员（启用、厂商未停用）。
     * 每项附上对外声明能力（启用成员交集，短板口径），供选择器的类型筛选判断它属于哪一档；
     * [currentTarget] 解析（哨兵 providerId + groupKey）与当前分组能力也取自这份列表。
     */
    val pickerRouteGroups: StateFlow<List<PickerRouteGroup>> =
        combine(
            smartRouteGroupDao.observeAll(),
            smartRouteMemberDao.observeAll(),
            providerDao.observeAll(),
            modelDao.observeAll(),
        ) { groups, members, providers, models ->
            val enabledProviders = providers.filter { it.enabled }.map { it.id }.toSet()
            val usable = members.filter { it.enabled && it.providerId in enabledProviders }
            groups.filter { g -> g.enabled && usable.any { it.groupId == g.id } }
                .map { g ->
                    PickerRouteGroup(
                        group = g,
                        capability = intersectCapability(
                            usable.filter { it.groupId == g.id }.mapNotNull { m ->
                                models.firstOrNull { it.providerId == m.providerId && it.ref == m.modelName }
                            },
                        ),
                    )
                }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 可选用的路由分组（能力另存于 [pickerRouteGroups]） */
    val routeGroups: StateFlow<List<SmartRouteGroupEntity>> =
        pickerRouteGroups.map { list -> list.map { it.group } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 当前生效目标：优先会话自己选的，否则回退到上次使用的目标 */
    val currentTarget: StateFlow<ChatTarget?> =
        combine(current, pickerModels, lastModel, smartChain, routeGroups) { conv, models, last, chain, groups ->
            fun resolve(providerId: Long, modelName: String?): ChatTarget? {
                if (providerId == SmartAi.PROVIDER_ID) {
                    return if (chain.isNotEmpty()) ChatTarget.SmartAi else null
                }
                // 路由分组：modelName 存 groupKey，分组被删或无可用成员时回退
                if (providerId == RouteGroupTarget.PROVIDER_ID) {
                    return groups.firstOrNull { it.groupKey == modelName }?.let { ChatTarget.Group(it) }
                }
                return models.firstOrNull { it.provider.id == providerId && it.model.ref == modelName }
                    ?.let { ChatTarget.Single(it) }
            }
            conv?.providerId?.let { pid -> resolve(pid, conv.modelName) }
                ?: last?.let { (pid, name) -> resolve(pid, name) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 当前分组目标的能力交集（短板口径，与对外报告一致；附件/思考按钮显隐依据）；非分组目标为 null */
    val currentGroupCapability: StateFlow<SmartAggregateCapability?> =
        combine(currentTarget, pickerRouteGroups) { target, groups ->
            val key = (target as? ChatTarget.Group)?.group?.groupKey ?: return@combine null
            groups.firstOrNull { it.group.groupKey == key }?.capability
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 上下文用量粗估：当前会话 systemPrompt + 全部历史（含图片），按当前目标的窗口显示 */
    val contextEstimate: StateFlow<ContextEstimate?> =
        combine(messages, current, currentTarget) { msgs, conv, target ->
            val window = (target as? ChatTarget.Single)?.picker?.model?.contextWindow
            val systemTokens = conv?.systemPrompt?.let { estimateTextTokens(it) + 4 } ?: 0
            val used = systemTokens + msgs.sumOf { estimateMessageTokens(it.content, it.images.size) }
            ContextEstimate(used, window)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _streaming = MutableStateFlow(false)

    /**
     * 正在生成回复的会话 id：回复跑在后台时用户可能切到别的会话，
     * 流式相关状态只在「当前会话 == 生成会话」时对外可见，切回去就能继续看到输出。
     */
    private val streamingConversationId = MutableStateFlow<Long?>(null)

    /** [_error] / [_notice] 的归属会话：null = 全局（不随会话切换隐藏） */
    private val statusConvId = MutableStateFlow<Long?>(null)

    /** 会话级状态：只有归属当前会话时才对外暴露，否则给出空闲值 */
    private fun <T> scopedToConversation(
        source: StateFlow<T>,
        owner: StateFlow<Long?>,
        idle: T,
    ): StateFlow<T> = combine(source, owner, currentId) { value, oid, cid ->
        if (oid == null || oid == cid) value else idle
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), idle)

    val streaming: StateFlow<Boolean> = scopedToConversation(_streaming, streamingConversationId, false)

    /** 正在生图（复用流式气泡位置展示「生成中…」，输入栏同样显示停止按钮） */
    private val _generatingImage = MutableStateFlow(false)
    val generatingImage: StateFlow<Boolean> =
        scopedToConversation(_generatingImage, streamingConversationId, false)

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> =
        scopedToConversation(_streamingText, streamingConversationId, "")

    private val _streamingReasoning = MutableStateFlow("")
    val streamingReasoning: StateFlow<String> =
        scopedToConversation(_streamingReasoning, streamingConversationId, "")

    private val _error = MutableStateFlow<ChatError?>(null)
    val error: StateFlow<ChatError?> = scopedToConversation(_error, statusConvId, null)

    /** 非阻断提示（如上下文被裁剪），用信息样式展示，用户可关闭 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = scopedToConversation(_notice, statusConvId, null)

    /**
     * 正在执行的工具（流式期间的进度提示，如「正在调用 web_search…」）。
     * 非空时流式气泡上方显示进行中的工具行；工具执行结束即置 null。
     * 与 [notice] 分工：notice 只留「轮数上限 / 上下文裁剪」这类需要用户读的提示。
     */
    private val _toolProgress = MutableStateFlow<String?>(null)
    val toolProgress: StateFlow<String?> =
        scopedToConversation(_toolProgress, streamingConversationId, null)

    /**
     * 正在执行的工具名（结构化的实时进度）：聊天页把它合成执行轨迹里的 RUNNING 节点。
     * [_toolProgress] 保留为文案形态，兼容只读它的插件。
     */
    private val _liveToolCalls = MutableStateFlow<List<String>>(emptyList())
    val liveToolCalls: StateFlow<List<String>> =
        scopedToConversation(_liveToolCalls, streamingConversationId, emptyList())

    fun dismissNotice() {
        _notice.value = null
    }

    // ---- 会话搜索 ----

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** 全局搜索结果：标题或消息内容命中；标题未命中时附首条命中消息片段 */
    @OptIn(FlowPreview::class)
    val searchResults: StateFlow<List<ConversationHit>> =
        _searchQuery
            .debounce(300)
            .map { it.trim() }
            .distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    flow {
                        emit(
                            conversationDao.search(q).map { conv ->
                                val snippet = if (conv.title.contains(q, ignoreCase = true)) {
                                    null
                                } else {
                                    messageDao.firstMatchContent(conv.id, q)
                                }
                                ConversationHit(conv, snippet)
                            }
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var sendJob: Job? = null

    /**
     * 当前流式回复实际使用的模型（providerId to modelName）：助手消息落库时记录，
     * 让历史气泡显示当时回复的模型而不是当前选中模型；非流式时为 null。
     * smartAi 场景随 SmartEvent.Trying 更新为最新候选（出内容后不再变化，即胜出者）。
     */
    @Volatile private var activeStreamModel: Pair<Long, String>? = null

    // ---- 本次流式的用量与耗时统计（startStreaming 时重置，落库后清空） ----
    private var streamStartedAt = 0L
    private var streamFirstTokenAt = 0L
    private var streamUsage: UsageInfo? = null

    private fun beginStreamStats() {
        streamStartedAt = System.currentTimeMillis()
        streamFirstTokenAt = 0L
        streamUsage = null
    }

    /** 首个内容/思考增量到达时打点首字时间（重复调用只取第一次） */
    private fun markFirstToken() {
        if (streamFirstTokenAt == 0L && streamStartedAt > 0L) {
            streamFirstTokenAt = System.currentTimeMillis()
        }
    }

    /** usage 各字段按「最后非空值」合并（Anthropic 首帧给输入、末帧给输出；OpenAI/Gemini 单帧全量） */
    private fun mergeStreamUsage(info: UsageInfo) {
        val cur = streamUsage
        streamUsage = UsageInfo(
            promptTokens = info.promptTokens ?: cur?.promptTokens,
            completionTokens = info.completionTokens ?: cur?.completionTokens,
            cachedTokens = info.cachedTokens ?: cur?.cachedTokens,
        )
    }

    /** 快照本次统计（供消息落库），并复位为未开始状态 */
    private fun takeStreamStats(): StreamStats {
        val started = streamStartedAt
        val stats = StreamStats(
            usage = streamUsage,
            ttftMs = if (started > 0L && streamFirstTokenAt > 0L) streamFirstTokenAt - started else null,
            totalMs = if (started > 0L) System.currentTimeMillis() - started else null,
        )
        streamStartedAt = 0L
        streamFirstTokenAt = 0L
        streamUsage = null
        return stats
    }

    private data class StreamStats(val usage: UsageInfo?, val ttftMs: Long?, val totalMs: Long?)

    /** 聊天页是否前台可见（ChatScreen 按 ON_RESUME/ON_PAUSE 上报）。声明须在 init 之前（init 里被 combine 引用） */
    val pageVisible = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            val latest = conversationDao.observeAll().first().firstOrNull()
            if (latest != null) currentId.value = latest.id
        }
        // 离开聊天页 + 生成中 15 秒 → 发提醒通知；回到页面撤销；流式结束时更新为已完成
        viewModelScope.launch {
            combine(_streaming, pageVisible) { streaming, visible -> streaming to visible }
                .collect { (streaming, visible) -> syncAwayNotify(streaming, visible) }
        }
    }

    /** 离开页面多久后发「正在回复」提醒 */
    private val AWAY_NOTIFY_DELAY_MS = 15_000L

    private var awayNotifyJob: Job? = null

    @Volatile
    private var streamNotified = false

    private fun syncAwayNotify(streaming: Boolean, visible: Boolean) {
        val app = getApplication<Application>()
        if (visible) {
            awayNotifyJob?.cancel()
            awayNotifyJob = null
            if (streamNotified) {
                streamNotified = false
                Notifications.cancelChatStatus(app)
            }
            return
        }
        if (!streaming || awayNotifyJob?.isActive == true) return
        awayNotifyJob = viewModelScope.launch {
            delay(AWAY_NOTIFY_DELAY_MS)
            if (!_streaming.value || pageVisible.value) return@launch
            streamNotified = true
            Notifications.notifyChatStatus(
                app, done = false, conversationId = streamingConversationId.value ?: currentId.value,
                text = "正在后台生成回复，完成后提醒你",
            )
        }
    }

    /** 流式结束（完成/停止/丢弃）共用收尾：离开页面且已提醒过 → 通知更新为已完成 */
    private fun onStreamEndedForNotify() {
        if (!streamNotified) return
        streamNotified = false
        val app = getApplication<Application>()
        if (pageVisible.value) {
            Notifications.cancelChatStatus(app)
        } else {
            Notifications.notifyChatStatus(
                app, done = true, conversationId = streamingConversationId.value ?: currentId.value,
                text = "点击查看回复",
            )
        }
    }

    // ---- 会话 ----

    fun newConversation(folderId: Long? = null) = viewModelScope.launch {
        // 不打断正在生成的回复：它继续在后台写回自己的会话，切回去仍能看到输出
        _notice.value = null
        // 当前分组已有空会话则直接复用，保证每个分组只有一个没有内容的新窗口
        val id = conversationDao.findEmptyConversation(folderId)?.let { existing ->
            lastModel.value?.let { (pid, name) -> conversationDao.setModel(existing.id, pid, name) }
            conversationDao.touch(existing.id, System.currentTimeMillis())
            existing.id
        } ?: run {
            val last = lastModel.value
            conversationDao.insert(
                ConversationEntity(
                    providerId = last?.first,
                    modelName = last?.second,
                    folderId = folderId,
                )
            )
        }
        currentId.value = id
        _pendingThinkingDepth.value?.let {
            conversationDao.setThinkingDepth(id, it)
            _pendingThinkingDepth.value = null
        }
        _pendingTools.value?.let {
            conversationDao.setTools(id, encodeToolNames(it))
            _pendingTools.value = null
        }
    }

    fun selectConversation(id: Long) {
        if (id == currentId.value) return
        // 后台回复不随切换中断：流式状态按会话隔离，切回去会继续显示
        currentId.value = id
        _notice.value = null
        // 切到已有会话后以会话自身的思考深度/工具勾选为准
        _pendingThinkingDepth.value = null
        _pendingTools.value = null
    }

    fun deleteConversation(id: Long) = viewModelScope.launch {
        // 只有删掉正在生成的那个会话才中断；其它会话的后台回复继续跑。
        // 直接丢弃半截内容，避免异步落库落在外键已删除的会话上。
        if (id == streamingConversationId.value) stopStreamingInternal(id, savePartial = false)
        if (id == currentId.value) {
            currentId.value = null
        }
        conversationDao.byId(id)?.let { conversationDao.delete(it) }
    }

    /** 移动会话到文件夹（null = 默认分组） */
    fun moveConversation(id: Long, folderId: Long?) = viewModelScope.launch {
        conversationDao.setFolder(id, folderId)
    }

    /** 重命名会话标题（顶栏点击标题触发） */
    fun renameConversation(id: Long, title: String) = viewModelScope.launch {
        val trimmed = title.replace('\n', ' ').trim()
        if (trimmed.isNotEmpty()) conversationDao.rename(id, trimmed)
    }

    /** 复用分组内的空会话（没有则新建），供无当前会话时的隐式创建路径共用 */
    private suspend fun reuseOrCreateEmpty(folderId: Long?): Long =
        conversationDao.findEmptyConversation(folderId)?.let { existing ->
            conversationDao.touch(existing.id, System.currentTimeMillis())
            existing.id
        } ?: conversationDao.insert(ConversationEntity(folderId = folderId))

    fun selectModel(providerId: Long, modelName: String) = viewModelScope.launch {
        settings.setLastModel(providerId, modelName)
        val convId = currentId.value
            ?: reuseOrCreateEmpty(null).also { currentId.value = it }
        conversationDao.setModel(convId, providerId, modelName)
    }

    fun dismissError() {
        _error.value = null
    }

    /** 会话持久化的目标标识：单个模型为真实厂商 id；smartAi/路由分组为哨兵值（分组的 modelName 存 groupKey） */
    private fun ChatTarget.identifiers(): Pair<Long, String> = when (this) {
        is ChatTarget.Single -> picker.provider.id to picker.model.ref
        ChatTarget.SmartAi -> SmartAi.pair
        is ChatTarget.Group -> RouteGroupTarget.PROVIDER_ID to group.groupKey
    }

    /**
     * 发送消息。[imageUris] 为相册新选图片（发送时压缩转 data URL）；
     * [imageDataUrls] 为已是 data URL 的图片（助手生图「添加到对话」、编辑态保留原图等）。
     * @return 是否被接受。false 时输入框应保留草稿，避免消息被无声吞掉。
     */
    fun send(text: String, imageUris: List<Uri>, imageDataUrls: List<String> = emptyList()): Boolean {
        val target = currentTarget.value
        // 本次交互的错误/提示归属当前会话（切走时不串到别的会话上）
        statusConvId.value = currentId.value
        if (text.isBlank() && imageUris.isEmpty() && imageDataUrls.isEmpty()) return false
        // 流式互斥：回复进行中拒绝新消息，避免两轮请求交叉写历史/并发写 _streamingText
        if (_streaming.value) {
            _error.value = if (streamingConversationId.value != null &&
                streamingConversationId.value != currentId.value
            ) {
                ChatError("另一个会话正在回复，请等待完成或切回该会话停止")
            } else {
                ChatError("上一条回复尚未结束，请先停止或等待完成")
            }
            return false
        }
        if (target == null) {
            // 目标解析为空时区分「从未选过」与「会话指向的厂商已被停用」
            viewModelScope.launch {
                val pid = current.value?.providerId
                val providerDisabled = pid != null && pid != SmartAi.PROVIDER_ID &&
                    providerDao.byId(pid)?.enabled == false
                _error.value = ChatError(
                    if (providerDisabled) {
                        "当前会话的厂商已被停用，请到「厂商」页启用或重新选择模型"
                    } else {
                        "请先选择模型"
                    },
                )
            }
            return false
        }
        val (providerId, modelName) = target.identifiers()
        viewModelScope.launch {
            var convId = currentId.value
            if (convId == null) {
                convId = reuseOrCreateEmpty(null)
                currentId.value = convId
                conversationDao.setModel(convId, providerId, modelName)
                _pendingThinkingDepth.value?.let {
                    conversationDao.setThinkingDepth(convId, it)
                    _pendingThinkingDepth.value = null
                }
                _pendingTools.value?.let {
                    conversationDao.setTools(convId, encodeToolNames(it))
                    _pendingTools.value = null
                }
            } else if (current.value?.providerId == null) {
                // 会话没有固定模型时，把实际使用的目标写回会话
                conversationDao.setModel(convId, providerId, modelName)
            }

            val images = imageUris.mapNotNull { ChatImages.uriToDataUrl(getApplication(), it) } +
                imageDataUrls.filter { it.startsWith("data:") }
            val isFirst = messageDao.forConversation(convId).isEmpty()
            messageDao.insert(
                MessageEntity(
                    conversationId = convId,
                    role = Role.USER,
                    content = text,
                    imagesJson = MessageEntity.imagesJsonOf(images),
                )
            )
            // 预设创建的会话保留预设名，让带人设的窗口在抽屉里始终可识别
            if (isFirst && text.isNotBlank() && conversationDao.byId(convId)?.systemPrompt == null) {
                conversationDao.rename(convId, text.take(16).replace('\n', ' ').trim())
            }
            conversationDao.touch(convId, System.currentTimeMillis())

            startStreaming(convId, target)
        }
        return true
    }

    /** 按目标发起请求：生图模型走文生图分支；其余单模型直连；smartAi 走回退链；分组走组内切换 */
    private suspend fun startStreaming(convId: Long, target: ChatTarget) {
        // 本次交互的错误/提示归属这条会话（用户切走后不会串到别的对话上）
        statusConvId.value = convId
        when (target) {
            is ChatTarget.Single -> when {
                target.picker.model.outputVideo -> generateVideo(convId, target.picker)
                target.picker.model.type == ModelType.IMAGE -> generateImage(convId, target.picker)
                else -> streamSingle(convId, target.picker)
            }
            ChatTarget.SmartAi -> streamSmart(convId)
            is ChatTarget.Group -> streamGroup(convId, target.group)
        }
    }

    /** 请求体构建结果：body + 上下文裁剪统计（用于提示条） */
    private data class BuiltBody(
        val body: JsonObject,
        val droppedCount: Int = 0,
        val strippedImageCount: Int = 0,
    )

    /** 裁剪提示文案：都为 0 时返回 null */
    private fun trimNotice(dropped: Int, strippedImages: Int): String? = when {
        dropped > 0 -> "上下文超出模型窗口，本次请求已省略最早的 $dropped 条消息"
        strippedImages > 0 -> "上下文接近模型窗口，早期图片已转为占位文本"
        else -> null
    }

    /**
     * 一次请求内的消息数组缓存：命中同一窗口的候选（含全部未配置窗口的候选）复用同一份 JSON。
     * flux-auto 的候选是全部启用模型，逐个重编码整段历史（含图片 data URL）会成倍放大耗时与内存。
     */
    private class MessagesCache {
        private val entries = HashMap<Int, CachedMessages>()
        fun keyOf(model: ModelEntity): Int = model.contextWindow?.takeIf { it > 0 } ?: 0
        fun get(key: Int): CachedMessages? = entries[key]
        fun put(key: Int, value: CachedMessages) { entries[key] = value }
    }

    private data class CachedMessages(val json: JsonArray, val dropped: Int, val stripped: Int)

    /**
     * 构建聊天请求体（单个模型与路由候选共用）；模型窗口已知时把历史裁进预算。
     * [cache] 非空时按窗口复用消息数组，裁剪统计随缓存一并取出。
     * [tools] 非空且厂商为 OpenAI 兼容协议时注入 tools 数组（工具结果消息随历史一并回传）。
     */
    private suspend fun buildRequestBody(
        provider: ProviderEntity,
        model: ModelEntity,
        conversation: ConversationEntity?,
        history: List<MessageEntity>,
        cache: MessagesCache? = null,
        tools: List<FluxTool> = emptyList(),
    ): BuiltBody {
        val systemPrompt = conversation?.systemPrompt?.takeIf { it.isNotBlank() }
        var dropped = 0
        var strippedImages = 0
        val cacheKey = cache?.keyOf(model)
        val cached = cacheKey?.let { cache?.get(it) }
        val messagesJson: JsonArray
        if (cached != null) {
            messagesJson = cached.json
            dropped = cached.dropped
            strippedImages = cached.stripped
        } else {
            var chatMessages = history.map {
                // 助手消息里的图片是模型产出（生图结果），不回传上游：Anthropic 不允许 assistant 携带图片
                ChatMessageInput(
                    role = it.role,
                    content = it.content,
                    images = if (it.role == Role.ASSISTANT) emptyList() else it.images,
                    toolCallsJson = it.toolCallsJson,
                    toolCallId = it.toolCallId,
                )
            }
            val window = model.contextWindow
            if (window != null && window > 0) {
                // 预算取窗口的 90%，先扣除 systemPrompt 的份额
                val systemTokens = systemPrompt?.let { estimateTextTokens(it) + 4 } ?: 0
                val trimmed = trimToBudget(chatMessages, window * 9 / 10 - systemTokens)
                chatMessages = trimmed.items
                dropped = trimmed.droppedCount
                strippedImages = trimmed.strippedImageCount
            }
            messagesJson = if (systemPrompt != null) {
                buildMessagesJsonEx(
                    listOf(ChatMessageInput(Role.SYSTEM, systemPrompt)) + chatMessages
                )
            } else {
                buildMessagesJsonEx(chatMessages)
            }
            if (cacheKey != null) cache?.put(cacheKey, CachedMessages(messagesJson, dropped, strippedImages))
        }
        var body = buildChatBody(
            modelName = model.name,
            messages = messagesJson,
            stream = true,
            defaults = advancedParamsOf(model),
        )
        // OpenAI 兼容上游：请求流式尾帧携带 usage（Gemini/Anthropic 原生自带 usage，边界转换会剥离此键）；
        // 仅 App 内聊天注入，局域网网关不改动客户端语义
        body = JsonObject(
            body + ("stream_options" to JsonObject(mapOf("include_usage" to JsonPrimitive(true))))
        )
        body = applyThinkingDepth(body, conversation?.thinkingDepth, model.name)
        body = mergeCustomBody(body, model.customBody)
        // 本地工具：仅 OpenAI 兼容协议注入（Gemini/Anthropic 原生 function calling 形态不同，本期不注入）
        if (tools.isNotEmpty() && provider.protocol == ProviderProtocol.OPENAI) {
            body = applyLocalTools(body, tools)
        }
        // Gemini 内置工具：兼容层预设（presetId）或原生协议厂商均注入，原生协议的格式转换由 ProviderClient 边界完成
        if (provider.presetId == "gemini" || provider.protocol == ProviderProtocol.GEMINI) {
            body = applyBuiltinTools(
                body,
                model.builtinGoogleSearch,
                model.builtinUrlContext,
                model.builtinImageGen,
            )
        }
        return BuiltBody(body, dropped, strippedImages)
    }

    /**
     * 单模型直连：支持本地工具的多轮循环。
     * 每轮请求带上当前勾选的 tools 与既有工具结果历史；模型以 tool_calls 收尾时，
     * 落库本轮助手消息（含 toolCallsJson）→ 执行工具 → 落库 role=tool 结果 → 发起下一轮，
     * 直到模型正常输出文本或达到用户配置的轮数上限（默认 0 = 不限）。
     * 未勾选工具时行为与改造前完全一致。
     */
    private suspend fun streamSingle(convId: Long, picker: PickerModel) {
        // 选择器数据是发送时刻的快照，落库前以最新状态复核厂商可用性
        val provider = providerDao.byId(picker.provider.id)
        if (provider == null || !provider.enabled) {
            _error.value = ChatError("厂商「${picker.provider.alias}」已被停用，请到「厂商」页启用后再发送")
            return
        }
        val key = router.keyFor(picker.model, provider)
        if (key == null || key.key.isBlank()) {
            _error.value = ChatError("厂商「${picker.provider.alias}」未配置 API Key，请到「厂商」页添加")
            return
        }
        val extraHeaders = parseCustomHeaders(picker.model.customHeaders)

        _error.value = null
        _streaming.value = true
        // 流式状态从这一刻起归属这条会话：切走不中断，切回来继续显示
        streamingConversationId.value = convId
        _streamingText.value = ""
        _streamingReasoning.value = ""
        activeStreamModel = picker.provider.id to picker.model.ref
        beginStreamStats()
        var publisher = StreamPublisher()
        sendJob = viewModelScope.launch {
            var failed: ChatError? = null
            var round = 0
            // 每次发送读取一次：设置里改完立即对新一次的提问生效（0 = 不限）
            val maxRounds = settings.toolMaxRounds.value
            try {
                while (true) {
                    val conversation = conversationDao.byId(convId)
                    val history = messageDao.forConversation(convId)
                    val tools = selectedTools.value
                    val built = buildRequestBody(
                        provider, picker.model, conversation, history, tools = tools,
                    )
                    if (round == 0) {
                        _notice.value = trimNotice(built.droppedCount, built.strippedImageCount)
                    }
                    val calls = collectRound(provider, key.key, extraHeaders, built.body, publisher)
                    if (calls.isEmpty()) break
                    // 本轮以工具调用收尾：落库助手消息 + 执行工具 + 落库结果，再进入下一轮
                    _liveToolCalls.value = calls.map { it.name }
                    _toolProgress.value = "正在调用 ${calls.joinToString("、") { it.name }}…"
                    persistToolRound(convId, publisher, calls)
                    _liveToolCalls.value = emptyList()
                    _toolProgress.value = null
                    if (maxRounds > 0 && round + 1 >= maxRounds) {
                        _notice.value = "工具调用已达 $maxRounds 轮上限，本次不再继续调用" +
                            "（可在「设置 → 工具调用」调整，0 为不限）"
                        break
                    }
                    round++
                    publisher = StreamPublisher()
                    _streamingText.value = ""
                    _streamingReasoning.value = ""
                }
            } catch (e: ToolRoundFailure) {
                failed = e.error
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = ChatError(e.message ?: e.javaClass.simpleName)
            }
            finishStreaming(convId, publisher, failed)
        }
    }

    /** 收集一轮流式响应；返回本轮模型请求的工具调用（无工具调用时为空列表） */
    private suspend fun collectRound(
        provider: ProviderEntity,
        apiKey: String,
        extraHeaders: Map<String, String>,
        body: JsonObject,
        publisher: StreamPublisher,
    ): List<ToolCall> {
        var calls: List<ToolCall> = emptyList()
        var failed: ChatError? = null
        client.chatDeltas(provider.baseUrl, apiKey, body, extraHeaders, provider.protocol).collect { ev ->
            when (ev) {
                is ChatEvent.Delta -> {
                    markFirstToken()
                    publisher.delta(ev.text)
                }
                is ChatEvent.ReasoningDelta -> {
                    markFirstToken()
                    publisher.reasoning(ev.text)
                }
                is ChatEvent.Usage -> mergeStreamUsage(ev.info)
                is ChatEvent.ToolCalls -> calls = ev.calls
                is ChatEvent.Failed -> failed = ChatError(ev.message, ev.httpCode, ev.rawBody)
                ChatEvent.Done -> Unit
            }
        }
        failed?.let { throw ToolRoundFailure(it) }
        return calls
    }

    /** 工具轮失败：携带 ChatError 抛给上层统一收尾（保持与单轮失败一致的错误呈现） */
    private class ToolRoundFailure(val error: ChatError) : Exception(error.message)

    /**
     * 落库「助手（含工具调用）」+ 逐条执行工具 + 落库「role=tool 结果」。
     * 工具执行失败或未知工具不中断整轮：以 {"error": ...} 文本作为结果回传，让模型自行纠正。
     */
    private suspend fun persistToolRound(convId: Long, publisher: StreamPublisher, calls: List<ToolCall>) {
        publisher.publish(force = true)
        val content = _streamingText.value
        val reasoning = _streamingReasoning.value.trim().takeIf { it.isNotEmpty() }
        val model = activeStreamModel
        messageDao.insert(
            MessageEntity(
                conversationId = convId,
                role = Role.ASSISTANT,
                content = content,
                reasoning = reasoning,
                toolCallsJson = toolCallsJsonOf(calls),
                providerId = model?.first,
                modelName = model?.second,
            )
        )
        calls.forEach { call ->
            val result = runTool(call)
            messageDao.insert(
                MessageEntity(
                    conversationId = convId,
                    role = Role.TOOL,
                    content = result,
                    toolCallId = call.id,
                )
            )
        }
        conversationDao.touch(convId, System.currentTimeMillis())
    }

    /** 执行单个工具调用：未知工具 / 参数非法 / 用户拒绝 / 超时 / 异常一律转为错误文本结果 */
    private suspend fun runTool(call: ToolCall): String {
        val tool = ToolRegistry.byName(call.name)
            ?: return """{"error":"未找到工具 ${call.name}，请改用已启用的工具或直接回答"}"""
        val args = runCatching { ChatJson.json.parseToJsonElement(call.arguments).jsonObject }
            .getOrElse {
                return """{"error":"工具参数不是合法 JSON 对象：${it.message?.take(120).orEmpty()}"}"""
            }
        // 标记了 needsApproval 的工具：执行前挂起等用户点头（弹窗由 ChatScreen 渲染）。
        // 像 script_tool_dev 这种「按动作分级确认」的工具在自身实现里确认，这里不拦。
        val execution = ToolApproval.newExecutionScope(approvalOwner)
        return try {
            kotlinx.coroutines.withContext(ToolApproval.executionContext(execution)) {
                if (tool.needsApproval) {
                    val approval = ToolApproval.request(
                        toolName = tool.name,
                        displayName = tool.displayName,
                        request = ApprovalRequest(
                            title = "调用工具「${tool.displayName}」",
                            detail = "参数：\n${call.arguments.take(600)}",
                        ),
                    )
                    if (!approval.approved) {
                        return@withContext buildJsonObject {
                            put("error", approval.reason ?: "审批未获允许，操作未执行。")
                        }.toString()
                    }
                }
                executeWithBudget(tool, args, execution)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            """{"error":"工具执行失败：${(e.message ?: e.javaClass.simpleName).take(200)}"}"""
        }
    }

    /**
     * 执行工具并施加 [TOOL_EXECUTION_TIMEOUT_MS] 预算，但**审批弹窗的等待不计入预算**。
     *
     * script_tool_dev 的写操作在工具内部才发起确认（UI 的挂起点在里面），若直接用
     * `withTimeoutOrNull` 包住 execute，用户读代码/配置稍久就会被判超时，确认框还在、
     * 结果却已经回给模型说「超时」。这里改为分片等待：审批进行中只让出时间片、不扣预算。
     */
    private suspend fun executeWithBudget(
        tool: FluxTool,
        args: JsonObject,
        execution: ToolApproval.ExecutionScope,
    ): String = coroutineScope {
        val task = async { tool.execute(args) }
        var remaining = TOOL_EXECUTION_TIMEOUT_MS
        while (!task.isCompleted) {
            if (execution.isWaitingForApproval) {
                delay(APPROVAL_POLL_MS)
                continue
            }
            val slice = remaining.coerceAtMost(WATCHDOG_SLICE_MS)
            // await 被超时取消不影响 async 自身，所以分片超时不会打断工具执行
            val finished = withTimeoutOrNull(slice) { task.await() } != null ||
                task.isCompleted
            if (finished) break
            remaining -= slice
            if (remaining <= 0) {
                task.cancel()
                return@coroutineScope """{"error":"工具执行超时（${TOOL_EXECUTION_TIMEOUT_MS / 1000}s）"}"""
            }
        }
        task.await()
    }

    /** 工具调用列表 → 落库用 JSON 数组文本（含 id/name/arguments，回传上游时原样发出） */
    private fun toolCallsJsonOf(calls: List<ToolCall>): String = buildJsonArray {
        calls.forEach { call ->
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(call.id))
                    put("type", JsonPrimitive("function"))
                    put(
                        "function",
                        buildJsonObject {
                            put("name", JsonPrimitive(call.name))
                            put("arguments", JsonPrimitive(call.arguments))
                        },
                    )
                }
            )
        }
    }.toString()

    /**
     * 文生图：目标为生图模型（type=image）时的发送分支。
     * 取会话最近一条用户消息作为提示词；生成中复用流式气泡位置显示占位，停止按钮可中断；
     * 成功落库一条带图助手消息（图片经压缩转 data URL，与用户附图同一存储形态）。
     */
    /**
     * 视频生成：目标模型 outputVideo=true 时的发送分支。
     * 接口形态由适配器层探测/模型记忆决定（sora/agnes/kling/minimax/runway/veo），
     * 提交后台任务后立即返回——生成慢（数分钟起），轮询在前台服务里进行且无硬超时；
     * 完成后以带视频的助手消息呈现并发通知，用户可随时离开页面。
     */
    private suspend fun generateVideo(convId: Long, picker: PickerModel) {
        // 选择器数据是发送时刻的快照，落库前以最新状态复核厂商可用性
        val provider = providerDao.byId(picker.provider.id)
        if (provider == null || !provider.enabled) {
            _error.value = ChatError("厂商「${picker.provider.alias}」已被停用，请到「厂商」页启用后再发送")
            return
        }
        val key = router.keyFor(picker.model, provider)
        if (key == null || key.key.isBlank()) {
            _error.value = ChatError("厂商「${picker.provider.alias}」未配置 API Key，请到「厂商」页添加")
            return
        }
        val prompt = messageDao.forConversation(convId).lastOrNull { it.role == Role.USER }?.content
        if (prompt.isNullOrBlank()) {
            _error.value = ChatError("请先输入画面描述再生成视频")
            return
        }
        _error.value = null
        VideoJobManager.get(getApplication())
            .submit(convId, provider, key.key, picker.model, prompt)
            .onSuccess { _notice.value = "视频生成任务已提交，生成较慢可离开页面，完成后会通知你" }
            .onFailure { e ->
                _error.value = when (e) {
                    is HttpApiException -> ChatError(e.message ?: "视频任务提交失败", e.httpCode, e.rawBody)
                    else -> ChatError(e.message ?: "视频任务提交失败")
                }
            }
    }

    private suspend fun generateImage(convId: Long, picker: PickerModel) {
        // 选择器数据是发送时刻的快照，落库前以最新状态复核厂商可用性
        val provider = providerDao.byId(picker.provider.id)
        if (provider == null || !provider.enabled) {
            _error.value = ChatError("厂商「${picker.provider.alias}」已被停用，请到「厂商」页启用后再发送")
            return
        }
        if (provider.protocol == ProviderProtocol.ANTHROPIC) {
            _error.value = ChatError("Anthropic（Claude）暂不提供图片生成接口，请换用支持生图的厂商")
            return
        }
        val key = router.keyFor(picker.model, provider)
        if (key == null || key.key.isBlank()) {
            _error.value = ChatError("厂商「${picker.provider.alias}」未配置 API Key，请到「厂商」页添加")
            return
        }
        val prompt = messageDao.forConversation(convId).lastOrNull { it.role == Role.USER }?.content
        if (prompt.isNullOrBlank()) {
            _error.value = ChatError("请先输入图片描述再生成")
            return
        }

        _error.value = null
        _generatingImage.value = true
        _streaming.value = true
        streamingConversationId.value = convId
        activeStreamModel = provider.id to picker.model.ref
        sendJob = viewModelScope.launch {
            var failed: ChatError? = null
            var imageDataUrl: String? = null
            var note: String? = null
            try {
                client.generateImage(
                    baseUrl = provider.baseUrl,
                    apiKey = key.key,
                    model = picker.model.name,
                    prompt = prompt,
                    extraHeaders = parseCustomHeaders(picker.model.customHeaders),
                    protocol = provider.protocol,
                ).onSuccess { img ->
                    imageDataUrl = ChatImages.bytesToDataUrl(img.bytes)
                    note = img.revisedText?.takeIf { it.isNotBlank() }
                    if (imageDataUrl == null) failed = ChatError("生成的图片无法解析或保存")
                }.onFailure { e ->
                    failed = when (e) {
                        is HttpApiException -> ChatError(e.message ?: "生图失败", e.httpCode, e.rawBody)
                        else -> ChatError(e.message ?: e.javaClass.simpleName)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = ChatError(e.message ?: e.javaClass.simpleName)
            }
            _streaming.value = false
            _generatingImage.value = false
            streamingConversationId.value = null
            val url = imageDataUrl
            if (url != null) {
                messageDao.insert(
                    MessageEntity(
                        conversationId = convId,
                        role = Role.ASSISTANT,
                        content = note ?: "",
                        imagesJson = MessageEntity.imagesJsonOf(listOf(url)),
                        providerId = activeStreamModel?.first,
                        modelName = activeStreamModel?.second,
                    )
                )
                conversationDao.touch(convId, System.currentTimeMillis())
            }
            activeStreamModel = null
            failed?.let { _error.value = it }
        }
    }
    /** flux-auto：合集=全部启用模型（厂商序×模型序），本次请求按模态筛出能用的候选，首字前失败/超时自动切换 */
    private suspend fun streamSmart(convId: Long) {
        val chain: List<Pair<Long, String>> = router.allModelsChain()
            .map { (provider, model) -> provider.id to model.ref }
        if (chain.isEmpty()) {
            _error.value = ChatError("${SmartAi.MODEL_NAME} 没有可聚合的启用模型，请检查厂商与模型是否已启用")
            return
        }
        val conversation = conversationDao.byId(convId)
        val history = messageDao.forConversation(convId)
        // 带图片附件优先视觉候选、开了思考深度优先推理候选，其余保持原顺序兜底
        val features = featuresOfHistory(
            hasImageAttachment = history.any { it.images.isNotEmpty() },
            thinkingDepth = conversation?.thinkingDepth,
        )
        // 各候选按各自窗口裁剪上下文，取最坏情况作提示
        var maxDropped = 0
        var maxStripped = 0
        val messagesCache = MessagesCache()
        val resolved = chain.mapNotNull { (providerId, modelName) ->
            val route = router.resolveById(providerId, modelName) ?: return@mapNotNull null
            route to modelName
        }
        // 停用厂商/模型：静默跳过（不进入候选链，避免无效请求）
        val enabledCandidates = resolved.filter { (route, _) ->
            val model = route.model
            route.provider.enabled && (model == null || model.enabled)
        }
        val usable = enabledCandidates.filterNot { it.first.provider.circuitOpen }
        val breakerSkipped = enabledCandidates.size - usable.size
        if (usable.isEmpty() && breakerSkipped > 0) {
            _error.value = ChatError(breakerMessage(breakerSkipped, enabledCandidates.size))
            return
        }
        val prepared = usable.mapNotNull { (route, modelName) ->
            val member = resolveRouteMember(route, modelName, conversation, history, messagesCache)
                ?: return@mapNotNull null
            if (member.dropped > maxDropped) maxDropped = member.dropped
            if (member.stripped > maxStripped) maxStripped = member.stripped
            member.candidate to member.model
        }
        _notice.value = trimNotice(maxDropped, maxStripped)
        // 自动选择：先按请求模态剔除发过去必然失败的候选，再按健康桶与特征匹配排序（冷却候选垫底）
        val now = System.currentTimeMillis()
        val eligible = eligibleCandidates(prepared, features, modelOf = { it.second })
        if (eligible.isEmpty()) {
            _error.value = ChatError(
                "没有支持${features.modalityLabel()}的可用模型（厂商停用/缺 Key，或启用模型均不具备该能力）",
            )
            return
        }
        val candidates = orderCandidates(
            eligible,
            features,
            modelOf = { it.second },
            bucketOf = { SmartHealth.bucket(it.first.providerId, it.first.modelName, now) },
        ).map { it.first }
        if (candidates.isEmpty()) {
            _error.value = ChatError("${SmartAi.MODEL_NAME} 的候选模型均不可用（缺少 API Key、已被删除或厂商已停用）")
            return
        }
        launchSmartCollect(convId, candidates)
    }

    /**
     * 路由分组（flux-<groupKey>）：组内成员按优先级与健康桶自动切换。
     * 与 flux-auto 的差别：候选只在组内；同样按请求模态自动剔除能力不匹配的成员，
     * 保证「组内参数基本一致」：不会出现同一个分组有的成员支持图像、有的不支持却被路由到的情况。
     */
    private suspend fun streamGroup(convId: Long, group: SmartRouteGroupEntity) {
        val members = smartRouteMemberDao.forGroup(group.id).filter { it.enabled }
        if (members.isEmpty()) {
            _error.value = ChatError("分组「${group.routeName}」没有启用的成员，请到「智能路由」页添加")
            return
        }
        val conversation = conversationDao.byId(convId)
        val history = messageDao.forConversation(convId)
        val features = featuresOfHistory(
            hasImageAttachment = history.any { it.images.isNotEmpty() },
            thinkingDepth = conversation?.thinkingDepth,
        )
        var maxDropped = 0
        var maxStripped = 0
        val messagesCache = MessagesCache()
        val resolvedMembers = members.mapNotNull { member ->
            router.resolveById(member.providerId, member.modelName)?.let { it to member.modelName }
        }
        // 停用厂商/模型：静默跳过（不进入候选链，避免无效请求）
        val enabledMembers = resolvedMembers.filter { (route, _) ->
            val model = route.model
            route.provider.enabled && (model == null || model.enabled)
        }
        val usable = enabledMembers.filterNot { it.first.provider.circuitOpen }
        val breakerSkipped = enabledMembers.size - usable.size
        if (usable.isEmpty() && breakerSkipped > 0) {
            _error.value = ChatError(breakerMessage(breakerSkipped, enabledMembers.size))
            return
        }
        val prepared = usable.mapNotNull { (route, modelName) ->
            val member = resolveRouteMember(route, modelName, conversation, history, messagesCache)
                ?: return@mapNotNull null
            if (member.dropped > maxDropped) maxDropped = member.dropped
            if (member.stripped > maxStripped) maxStripped = member.stripped
            member.candidate to member.model
        }
        val eligible = eligibleCandidates(prepared, features, modelOf = { it.second })
        if (eligible.isEmpty()) {
            _error.value = ChatError(
                "分组「${group.routeName}」内没有支持${features.modalityLabel()}的可用成员" +
                    "（厂商停用/缺 Key，或成员均不具备该能力）",
            )
            return
        }
        _notice.value = trimNotice(maxDropped, maxStripped)
        val now = System.currentTimeMillis()
        val candidates = orderCandidates(
            eligible,
            features,
            modelOf = { it.second },
            bucketOf = { SmartHealth.bucket(it.first.providerId, it.first.modelName, now) },
        ).map { it.first }
        launchSmartCollect(convId, candidates)
    }

    /** 已解析的路由成员：候选请求 + 模型配置 + 上下文裁剪统计 */
    private data class ResolvedMember(
        val candidate: SmartCandidate,
        val model: ModelEntity,
        val dropped: Int,
        val stripped: Int,
    )

    /**
     * 解析一个路由成员（flux-auto 候选/分组成员共用）：跳过停用厂商、缺 Key、自连网关；附上下文裁剪统计。
     * [cache] 用于同一次请求内复用消息数组（全部候选枚举一遍时避免重复编码历史）。
     */
    private suspend fun resolveRouteMember(
        resolved: ModelRoute,
        modelName: String,
        conversation: ConversationEntity?,
        history: List<MessageEntity>,
        cache: MessagesCache? = null,
    ): ResolvedMember? {
        val key = resolved.apiKey ?: return null
        if (key.key.isBlank()) return null
        if (isSelfGatewayUrl(resolved.provider.baseUrl, settings.lanPort.value)) return null
        // 模型配置行可能已被删除，此时退回空配置继续路由
        val model = resolved.model ?: ModelEntity(providerId = resolved.provider.id, name = modelName)
        val built = buildRequestBody(resolved.provider, model, conversation, history, cache)
        val candidate = SmartCandidate(
            label = "${resolved.provider.alias}/${model.alias?.takeIf { it.isNotBlank() } ?: model.ref}",
            providerId = resolved.provider.id,
            modelName = model.ref,
            providerAlias = resolved.provider.alias,
            baseUrl = resolved.provider.baseUrl,
            apiKey = key.key,
            extraHeaders = parseCustomHeaders(model.customHeaders),
            body = built.body,
            protocol = resolved.provider.protocol,
            retryable = resolved.provider.retryable,
        )
        return ResolvedMember(candidate, model, built.droppedCount, built.strippedImageCount)
    }

    /** 候选全被手动熔断时的提示（区别于「没有可用模型」的缺 Key / 停用场景） */
    private fun breakerMessage(skipped: Int, total: Int): String =
        "全部 $total 个候选所在厂商均处于手动熔断状态（$skipped 个），" +
            "请到「局域网 → 模型异常记录」页对相关厂商点「可重试」解除"

    /** 智能路由策略（本机聊天）：App 内 smartAi 与路由分组共用同一组参数 */
    private fun smartPolicy() = SmartPolicy(
        mode = settings.smartResponseMode.value,
        fastMs = settings.smartFastFirstTokenMs.value.toLong(),
        chatMs = settings.smartFirstTokenTimeoutSeconds.value * 1000L,
        leisureMs = settings.smartLeisureFirstTokenSeconds.value * 1000L,
        cooldownThreshold = settings.smartCooldownThreshold.value,
        cooldownMs = settings.smartCooldownSeconds.value * 1000L,
    )

    /** 启动执行器并把事件接到节流发布器与落库（smartAi 与路由分组共用的收尾） */
    private fun launchSmartCollect(convId: Long, candidates: List<SmartCandidate>) {
        _error.value = null
        _streaming.value = true
        streamingConversationId.value = convId
        _streamingText.value = ""
        _streamingReasoning.value = ""
        beginStreamStats()
        val publisher = StreamPublisher()
        sendJob = viewModelScope.launch {
            var failed: ChatError? = null
            try {
                smartExecutor.stream(candidates, smartPolicy(), settings.smartRouteMaxRounds.value).collect { ev ->
                    when (ev) {
                        is SmartEvent.Delta -> {
                            markFirstToken()
                            publisher.delta(ev.text)
                        }
                        is SmartEvent.ReasoningDelta -> {
                            markFirstToken()
                            publisher.reasoning(ev.text)
                        }
                        is SmartEvent.Usage -> mergeStreamUsage(ev.info)
                        is SmartEvent.Failed -> failed = ChatError(ev.message, ev.httpCode, ev.rawBody, ev.kind)
                        // 出内容后不再有 Trying，首个内容出现时的最新候选即胜出者
                        is SmartEvent.Trying -> activeStreamModel = ev.providerId to ev.modelName
                        SmartEvent.Done -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = ChatError(e.message ?: e.javaClass.simpleName)
            }
            finishStreaming(convId, publisher, failed)
        }
    }

    /** 流式输出的节流发布器：增量先入缓冲区，最多 ~10Hz 刷新 UI */
    private inner class StreamPublisher {
        private val thinkParser = ThinkParser()
        private val explicitReasoning = StringBuilder()
        private val pending = StringBuilder()
        private var lastPublishAt = 0L

        fun delta(text: String) {
            pending.append(text)
            publish()
        }

        fun reasoning(text: String) {
            explicitReasoning.append(text)
            publish()
        }

        fun publish(force: Boolean = false) {
            val now = System.nanoTime() / 1_000_000L
            if (!force && now - lastPublishAt < STREAM_PUBLISH_INTERVAL_MS) return
            lastPublishAt = now
            if (pending.isNotEmpty()) {
                thinkParser.append(pending.toString())
                pending.clear()
            }
            _streamingText.value = thinkParser.content
            _streamingReasoning.value =
                listOf(explicitReasoning.toString(), thinkParser.reasoning)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
        }
    }

    /** 收尾：发布剩余内容并落库助手消息（附 token 用量与耗时），失败则呈现错误 */
    private suspend fun finishStreaming(convId: Long, publisher: StreamPublisher, failed: ChatError?) {
        publisher.publish(force = true)
        _streaming.value = false
        // 先按「生成所在会话」更新通知，再清空归属（清空后只能回落到当前会话）
        onStreamEndedForNotify()
        streamingConversationId.value = null
        _liveToolCalls.value = emptyList()
        _toolProgress.value = null
        val finalContent = _streamingText.value
        val finalReasoning = _streamingReasoning.value.trim().takeIf { it.isNotEmpty() }
        _streamingText.value = ""
        _streamingReasoning.value = ""
        val stats = takeStreamStats()
        if (finalContent.isNotEmpty() || finalReasoning != null) {
            messageDao.insert(
                MessageEntity(
                    conversationId = convId,
                    role = Role.ASSISTANT,
                    content = finalContent,
                    reasoning = finalReasoning,
                    providerId = activeStreamModel?.first,
                    modelName = activeStreamModel?.second,
                    promptTokens = stats.usage?.promptTokens,
                    completionTokens = stats.usage?.completionTokens,
                    cachedTokens = stats.usage?.cachedTokens,
                    ttftMs = stats.ttftMs,
                    totalMs = stats.totalMs,
                )
            )
            conversationDao.touch(convId, System.currentTimeMillis())
        }
        activeStreamModel = null
        failed?.let { _error.value = it }
    }

    /** 取消流式并丢弃未完成的半截内容（重新生成前调用，不落库） */
    private fun cancelStreamingDiscard() {
        sendJob?.cancel()
        _streaming.value = false
        onStreamEndedForNotify()
        streamingConversationId.value = null
        _generatingImage.value = false
        _liveToolCalls.value = emptyList()
        _toolProgress.value = null
        _streamingText.value = ""
        _streamingReasoning.value = ""
        activeStreamModel = null
        takeStreamStats()
    }

    /**
     * 重新生成整个工具轮次：从本轮第一条消息起删除（含工具调用与 role=tool 结果）。
     * 与 [regenerateFrom] 的区别：只删答复会留下无主的工具结果，下一轮请求会带着
     * 「没有对应 assistant.tool_calls 的 tool 消息」，协议上不合法。
     */
    fun regenerateToolTurn(conversationId: Long, firstMessageId: Long) {
        if (conversationId != currentId.value) return
        viewModelScope.launch {
            cancelStreamingDiscard()
            messageDao.deleteFromInclusive(conversationId, firstMessageId)
            conversationDao.touch(conversationId, System.currentTimeMillis())
            val target = currentTarget.value ?: return@launch
            startStreaming(conversationId, target)
        }
    }

    /**
     * 重新生成：助手消息删除自身及其后全部消息；用户消息保留自身、删除其后回复，
     * 然后基于剩余历史重新请求。
     */
    fun regenerateFrom(message: MessageEntity) {
        val convId = message.conversationId
        if (convId != currentId.value) return
        viewModelScope.launch {
            cancelStreamingDiscard()
            if (message.role == Role.USER) {
                messageDao.deleteAfter(convId, message.id)
            } else {
                messageDao.deleteFromInclusive(convId, message.id)
            }
            conversationDao.touch(convId, System.currentTimeMillis())
            val target = currentTarget.value ?: return@launch
            startStreaming(convId, target)
        }
    }

    /**
     * 编辑并重发：删除原用户消息及其后全部消息，以新内容重新发送。
     * 原消息的图片附件以 data URL 形式保留（已压缩过，不再处理），新选图片走正常压缩。
     */
    fun editAndResend(
        message: MessageEntity,
        newText: String,
        keepDataUrls: List<String>,
        newImageUris: List<Uri>,
    ): Boolean {
        val convId = message.conversationId
        if (convId != currentId.value) return false
        if (newText.isBlank() && keepDataUrls.isEmpty() && newImageUris.isEmpty()) return false
        if (_streaming.value) {
            _error.value = if (streamingConversationId.value != null &&
                streamingConversationId.value != currentId.value
            ) {
                ChatError("另一个会话正在回复，请等待完成或切回该会话停止")
            } else {
                ChatError("上一条回复尚未结束，请先停止或等待完成")
            }
            return false
        }
        val target = currentTarget.value
        if (target == null) {
            _error.value = ChatError("请先选择模型")
            return false
        }
        viewModelScope.launch {
            cancelStreamingDiscard()
            messageDao.deleteFromInclusive(convId, message.id)
            val newImages = newImageUris.mapNotNull { ChatImages.uriToDataUrl(getApplication(), it) }
            messageDao.insert(
                MessageEntity(
                    conversationId = convId,
                    role = Role.USER,
                    content = newText,
                    imagesJson = MessageEntity.imagesJsonOf(keepDataUrls + newImages),
                )
            )
            conversationDao.touch(convId, System.currentTimeMillis())
            startStreaming(convId, target)
        }
        return true
    }

    /** 创建分支：把会话开头到该消息（含）复制进新会话并切换过去 */
    fun branchConversation(message: MessageEntity) = viewModelScope.launch {
        stopStreamingInternal()
        val conv = conversationDao.byId(message.conversationId) ?: return@launch
        val newId = conversationDao.insert(
            ConversationEntity(
                title = "${conv.title} · 分支",
                providerId = conv.providerId,
                modelName = conv.modelName,
                folderId = conv.folderId,
                systemPrompt = conv.systemPrompt,
                thinkingDepth = conv.thinkingDepth,
            )
        )
        messageDao.forConversationUpTo(message.conversationId, message.id).forEach { m ->
            messageDao.insert(
                MessageEntity(
                    conversationId = newId,
                    role = m.role,
                    content = m.content,
                    imagesJson = m.imagesJson,
                    reasoning = m.reasoning,
                    providerId = m.providerId,
                    modelName = m.modelName,
                    createdAt = m.createdAt,
                )
            )
        }
        conversationDao.touch(newId, System.currentTimeMillis())
        currentId.value = newId
    }

    /** 从会话中删除单条消息（物理删除，不可恢复） */
    fun deleteMessage(message: MessageEntity) = viewModelScope.launch {
        messageDao.delete(message)
        conversationDao.touch(message.conversationId, System.currentTimeMillis())
    }

    /** 设置当前会话的思考深度；尚无会话时先暂存，会话创建时写入 */
    fun setThinkingDepth(depth: String) {
        val convId = currentId.value
        if (convId == null) {
            _pendingThinkingDepth.value = depth
        } else {
            viewModelScope.launch { conversationDao.setThinkingDepth(convId, depth) }
        }
    }

    /** 修改当前会话的系统提示词（人设），顶栏入口可查看与编辑 */
    fun setSystemPrompt(prompt: String?) = viewModelScope.launch {
        val convId = currentId.value ?: return@launch
        conversationDao.setSystemPrompt(convId, prompt?.takeIf { it.isNotBlank() })
    }

    /** 用户主动停止：中断生成，已产出的半截内容落库到生成所在会话 */
    fun stopStreaming() = stopStreamingInternal()

    /** 首次点停止后的提示开关，界面居中短暂显示后自动复位 */
    private val _stopHint = MutableStateFlow(false)
    val stopHint: StateFlow<Boolean> = _stopHint

    private var lastStopTapAt = 0L

    /**
     * 停止按钮点击入口：3 秒内第二次点击才真正中断输出，
     * 第一次点击置 stopHint 提示「再点一次」。返回是否执行了停止。
     * 时间戳窗口自动失效，无需在流式状态变化时清理残留。
     */
    fun onStopTap(): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastStopTapAt in 0..STOP_TAP_WINDOW_MS) {
            lastStopTapAt = 0L
            _stopHint.value = false
            stopStreaming()
            true
        } else {
            lastStopTapAt = now
            _stopHint.value = true
            viewModelScope.launch {
                delay(STOP_HINT_VISIBLE_MS)
                if (lastStopTapAt == now) _stopHint.value = false
            }
            false
        }
    }

    /**
     * 中断当前生成。半截内容落库到「生成所在会话」而不是当前查看的会话：
     * 后台回复期间用户可能已经切走，按 currentId 写会把内容记到别的对话里。
     */
    private fun stopStreamingInternal(
        targetConvId: Long? = streamingConversationId.value ?: currentId.value,
        savePartial: Boolean = true,
    ) {
        val partial = _streamingText.value
        val partialReasoning = _streamingReasoning.value.trim().takeIf { it.isNotEmpty() }
        val model = activeStreamModel
        activeStreamModel = null
        val stats = takeStreamStats()
        sendJob?.cancel()
        _streaming.value = false
        _generatingImage.value = false
        onStreamEndedForNotify()
        streamingConversationId.value = null
        _streamingText.value = ""
        _streamingReasoning.value = ""
        if (savePartial && (partial.isNotEmpty() || partialReasoning != null)) {
            val convId = targetConvId
            if (convId != null) {
                viewModelScope.launch {
                    messageDao.insert(
                        MessageEntity(
                            conversationId = convId,
                            role = Role.ASSISTANT,
                            content = partial,
                            reasoning = partialReasoning,
                            providerId = model?.first,
                            modelName = model?.second,
                            promptTokens = stats.usage?.promptTokens,
                            completionTokens = stats.usage?.completionTokens,
                            cachedTokens = stats.usage?.cachedTokens,
                            ttftMs = stats.ttftMs,
                            totalMs = stats.totalMs,
                        )
                    )
                }
            }
        }
    }

    // ---- 文件夹 ----

    fun createFolder(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) folderDao.insert(FolderEntity(name = name.trim()))
    }

    fun renameFolder(id: Long, name: String) = viewModelScope.launch {
        if (name.isNotBlank()) folderDao.byId(id)?.let { folderDao.update(it.copy(name = name.trim())) }
    }

    fun deleteFolder(id: Long) = viewModelScope.launch {
        // 会话移回默认分组（预设对话随文件夹级联删除）
        conversationDao.clearFolder(id)
        folderDao.byId(id)?.let { folderDao.delete(it) }
    }

    // ---- 预设对话 ----

    fun savePreset(
        folderId: Long,
        existing: PresetEntity?,
        name: String,
        systemPrompt: String?,
        providerId: Long?,
        modelName: String?,
    ) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        if (existing == null) {
            presetDao.insert(
                PresetEntity(
                    folderId = folderId,
                    name = name.trim(),
                    systemPrompt = systemPrompt?.takeIf { it.isNotBlank() },
                    providerId = providerId,
                    modelName = modelName,
                )
            )
        } else {
            presetDao.update(
                existing.copy(
                    name = name.trim(),
                    systemPrompt = systemPrompt?.takeIf { it.isNotBlank() },
                    providerId = providerId,
                    modelName = modelName,
                )
            )
        }
    }

    fun deletePreset(preset: PresetEntity) = viewModelScope.launch {
        presetDao.delete(preset)
    }

    /** 用预设对话新建会话：应用其系统提示词与模型；同一预设的空会话直接复用 */
    fun startFromPreset(preset: PresetEntity) = viewModelScope.launch {
        val providerId = preset.providerId ?: lastModel.value?.first
        val modelName = preset.modelName ?: lastModel.value?.second
        val id = conversationDao.findEmptyByPreset(preset.folderId, preset.name, preset.systemPrompt)?.let { existing ->
            conversationDao.setModel(existing.id, providerId, modelName)
            conversationDao.touch(existing.id, System.currentTimeMillis())
            existing.id
        } ?: conversationDao.insert(
            ConversationEntity(
                title = preset.name,
                providerId = providerId,
                modelName = modelName,
                folderId = preset.folderId,
                systemPrompt = preset.systemPrompt,
            )
        )
        currentId.value = id
        _pendingThinkingDepth.value = null
    }

    companion object {
        /** 流式文本的发布节流间隔：UI 最多以 ~10Hz 刷新，而不是每个 token 刷新 */
        private const val STREAM_PUBLISH_INTERVAL_MS = 100L

        /** 停止按钮二次点击的判定窗口 */
        private const val STOP_TAP_WINDOW_MS = 3000L

        /** 「再点一次停止」居中提示的显示时长 */
        private const val STOP_HINT_VISIBLE_MS = 1800L

        /** 单个工具执行超时（毫秒）：超时按错误结果回传，不阻塞整轮 */
        private const val TOOL_EXECUTION_TIMEOUT_MS = 30_000L

        /** 工具执行预算的分片粒度：分片只用于重复检查「是否在等用户确认」，不改变总预算 */
        private const val WATCHDOG_SLICE_MS = 250L

        /** 审批弹窗期间的轮询间隔：等待用户确认时不消耗执行预算 */
        private const val APPROVAL_POLL_MS = 200L

        /** 厂商按用户拖拽顺序分组，厂商内按模型拖拽顺序；onlyEnabledProviders=false 时含停用厂商（预设编辑回显用） */
        private fun buildPickerModels(
            providers: List<ProviderEntity>,
            models: List<ModelEntity>,
            onlyEnabledProviders: Boolean,
        ): List<PickerModel> {
            val visible = if (onlyEnabledProviders) providers.filter { it.enabled } else providers
            val providerRank = visible.withIndex().associate { it.value.id to it.index }
            return models.mapNotNull { m ->
                visible.firstOrNull { it.id == m.providerId }?.let { PickerModel(it, m) }
            }.sortedWith(
                compareBy(
                    { providerRank[it.provider.id] ?: Int.MAX_VALUE },
                    { it.model.sortOrder },
                    { it.model.ref.lowercase() },
                )
            )
        }

        fun advancedParamsOf(m: ModelEntity): AdvancedParams? {
            if (m.temperature == null && m.topP == null && m.maxTokens == null &&
                m.presencePenalty == null && m.frequencyPenalty == null
            ) return null
            return AdvancedParams(
                temperature = m.temperature,
                topP = m.topP,
                maxTokens = m.maxTokens,
                presencePenalty = m.presencePenalty,
                frequencyPenalty = m.frequencyPenalty,
            )
        }
    }
}
