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
import cn.luoluna.sweven.ai.data.db.PresetDao
import cn.luoluna.sweven.ai.data.db.PresetEntity
import cn.luoluna.sweven.ai.data.db.ProviderDao
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.data.db.SmartRouteDao
import cn.luoluna.sweven.ai.data.repo.SmartAggregateCapability
import cn.luoluna.sweven.ai.data.repo.SmartAi
import cn.luoluna.sweven.ai.data.repo.SmartCandidate
import cn.luoluna.sweven.ai.data.repo.SmartEvent
import cn.luoluna.sweven.ai.data.repo.SmartHealth
import cn.luoluna.sweven.ai.data.repo.SmartPolicy
import cn.luoluna.sweven.ai.data.repo.SmartRouteExecutor
import cn.luoluna.sweven.ai.data.repo.aggregateCapability
import cn.luoluna.sweven.ai.data.repo.featuresOfHistory
import cn.luoluna.sweven.ai.data.repo.isSelfGatewayUrl
import cn.luoluna.sweven.ai.data.repo.orderCandidates
import cn.luoluna.sweven.ai.net.AdvancedParams
import cn.luoluna.sweven.ai.net.ChatEvent
import cn.luoluna.sweven.ai.net.ProviderClient
import cn.luoluna.sweven.ai.net.ProviderProtocol
import cn.luoluna.sweven.ai.net.ThinkParser
import cn.luoluna.sweven.ai.net.applyBuiltinTools
import cn.luoluna.sweven.ai.net.applyThinkingDepth
import cn.luoluna.sweven.ai.net.buildChatBody
import cn.luoluna.sweven.ai.net.buildMessagesJson
import cn.luoluna.sweven.ai.net.estimateMessageTokens
import cn.luoluna.sweven.ai.net.estimateTextTokens
import cn.luoluna.sweven.ai.net.mergeCustomBody
import cn.luoluna.sweven.ai.net.parseCustomHeaders
import cn.luoluna.sweven.ai.net.trimToBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.JsonObject

/** 模型选择器条目：厂商 + 模型 */
data class PickerModel(val provider: ProviderEntity, val model: ModelEntity) {
    /** 局域网聚合 id（按模型 id） */
    val aggregateId: String get() = "${provider.alias}/${model.name}"
    /** 展示名（优先别名） */
    val displayName: String get() = model.alias?.takeIf { it.isNotBlank() } ?: model.name
    val displayId: String get() = "${provider.alias}/$displayName"
    /** 模型是否处于启用状态（禁用模型仅供预设编辑回显，聊天选择器不出现） */
    val enabled: Boolean get() = model.enabled
}

/** 当前生效的聊天目标：单个模型或 smartAi 聚合 */
sealed interface ChatTarget {
    data class Single(val picker: PickerModel) : ChatTarget
    data object SmartAi : ChatTarget
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
data class ChatError(
    val message: String,
    val httpCode: Int? = null,
    val rawBody: String? = null,
) {
    /** 复制用的完整错误文本：状态码 + 原始响应体（缺省回退 message） */
    fun detailText(): String = buildString {
        httpCode?.let { append("HTTP $it\n\n") }
        if (rawBody.isNullOrBlank()) append(message) else append(rawBody)
    }
}

/** 聊天页上下文用量指示：按当前会话与目标模型的粗估 token；窗口未知时 window 为 null */
data class ContextEstimate(val usedTokens: Int, val window: Int?)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app as App
    private val conversationDao: ConversationDao = ctx.database.conversationDao()
    private val messageDao: MessageDao = ctx.database.messageDao()
    private val providerDao: ProviderDao = ctx.database.providerDao()
    private val modelDao: ModelDao = ctx.database.modelDao()
    private val folderDao: FolderDao = ctx.database.folderDao()
    private val presetDao: PresetDao = ctx.database.presetDao()
    private val smartRouteDao: SmartRouteDao = ctx.database.smartRouteDao()
    private val client: ProviderClient = ctx.providerClient
    private val router = ctx.modelRouter
    private val settings = ctx.settings
    private val smartExecutor = SmartRouteExecutor(client)

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

    /** 智能路由启用的候选数量（>0 时聊天选择器显示 smartAi）；候选所属厂商停用后不计入 */
    private val smartEnabledCount: StateFlow<Int> =
        combine(smartRouteDao.observeAll(), providerDao.observeAll()) { routes, providers ->
            val enabledIds = providers.filter { it.enabled }.map { it.id }.toSet()
            routes.count { it.enabled && it.providerId in enabledIds }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 聊天模型选择器是否显示 smartAi 条目 */
    val smartAiAvailable: StateFlow<Boolean> =
        smartEnabledCount.map { it > 0 }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** smartAi 聚合能力（启用候选的模态/能力并集）：SmartAi 目标下附件按钮与思考深度的显示依据 */
    val smartAiCapability: StateFlow<SmartAggregateCapability> =
        combine(smartRouteDao.observeAll(), providerDao.observeAll(), modelDao.observeAll()) { routes, providers, models ->
            val enabledIds = providers.filter { it.enabled }.map { it.id }.toSet()
            aggregateCapability(
                routes.filter { it.enabled && it.providerId in enabledIds }.mapNotNull { route ->
                    models.firstOrNull { it.providerId == route.providerId && it.name == route.modelName }
                },
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, aggregateCapability(emptyList()))

    /** 当前生效目标：优先会话自己选的，否则回退到上次使用的目标 */
    val currentTarget: StateFlow<ChatTarget?> =
        combine(current, pickerModels, lastModel, smartEnabledCount) { conv, models, last, smartCount ->
            fun resolve(providerId: Long, modelName: String?): ChatTarget? {
                if (providerId == SmartAi.PROVIDER_ID) {
                    return if (smartCount > 0) ChatTarget.SmartAi else null
                }
                return models.firstOrNull { it.provider.id == providerId && it.model.name == modelName }
                    ?.let { ChatTarget.Single(it) }
            }
            conv?.providerId?.let { pid -> resolve(pid, conv.modelName) }
                ?: last?.let { (pid, name) -> resolve(pid, name) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 上下文用量粗估：当前会话 systemPrompt + 全部历史（含图片），按当前目标的窗口显示 */
    val contextEstimate: StateFlow<ContextEstimate?> =
        combine(messages, current, currentTarget) { msgs, conv, target ->
            val window = (target as? ChatTarget.Single)?.picker?.model?.contextWindow
            val systemTokens = conv?.systemPrompt?.let { estimateTextTokens(it) + 4 } ?: 0
            val used = systemTokens + msgs.sumOf { estimateMessageTokens(it.content, it.images.size) }
            ContextEstimate(used, window)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText

    private val _streamingReasoning = MutableStateFlow("")
    val streamingReasoning: StateFlow<String> = _streamingReasoning

    private val _error = MutableStateFlow<ChatError?>(null)
    val error: StateFlow<ChatError?> = _error

    /** 非阻断提示（如上下文被裁剪），用信息样式展示，用户可关闭 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

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

    init {
        viewModelScope.launch {
            val latest = conversationDao.observeAll().first().firstOrNull()
            if (latest != null) currentId.value = latest.id
        }
    }

    // ---- 会话 ----

    fun newConversation(folderId: Long? = null) = viewModelScope.launch {
        stopStreamingInternal()
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
    }

    fun selectConversation(id: Long) {
        if (id == currentId.value) return
        stopStreamingInternal()
        currentId.value = id
        _notice.value = null
        // 切到已有会话后以会话自身的思考深度为准
        _pendingThinkingDepth.value = null
    }

    fun deleteConversation(id: Long) = viewModelScope.launch {
        if (id == currentId.value) {
            stopStreamingInternal()
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

    /** 会话持久化的目标标识：单个模型为真实厂商 id；smartAi 为哨兵值 */
    private fun ChatTarget.identifiers(): Pair<Long, String> = when (this) {
        is ChatTarget.Single -> picker.provider.id to picker.model.name
        ChatTarget.SmartAi -> SmartAi.pair
    }

    fun send(text: String, imageUris: List<Uri>) {
        val target = currentTarget.value
        if (text.isBlank() && imageUris.isEmpty()) return
        // 流式互斥：回复进行中拒绝新消息，避免两轮请求交叉写历史/并发写 _streamingText
        if (_streaming.value) {
            _error.value = ChatError("上一条回复尚未结束，请先停止或等待完成")
            return
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
            return
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
            } else if (current.value?.providerId == null) {
                // 会话没有固定模型时，把实际使用的目标写回会话
                conversationDao.setModel(convId, providerId, modelName)
            }

            val images = imageUris.mapNotNull { ChatImages.uriToDataUrl(getApplication(), it) }
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
    }

    /** 按目标发起流式请求：单个模型直连；smartAi 走回退链 */
    private suspend fun startStreaming(convId: Long, target: ChatTarget) {
        when (target) {
            is ChatTarget.Single -> streamSingle(convId, target.picker)
            ChatTarget.SmartAi -> streamSmart(convId)
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

    /** 构建聊天请求体（单个模型与 smartAi 候选共用）；模型窗口已知时把历史裁进预算 */
    private suspend fun buildRequestBody(
        provider: ProviderEntity,
        model: ModelEntity,
        conversation: ConversationEntity?,
        history: List<MessageEntity>,
    ): BuiltBody {
        var chatMessages = history.map { Triple(it.role, it.content, it.images) }
        val systemPrompt = conversation?.systemPrompt?.takeIf { it.isNotBlank() }
        var dropped = 0
        var strippedImages = 0
        val window = model.contextWindow
        if (window != null && window > 0) {
            // 预算取窗口的 90%，先扣除 systemPrompt 的份额
            val systemTokens = systemPrompt?.let { estimateTextTokens(it) + 4 } ?: 0
            val trimmed = trimToBudget(chatMessages, window * 9 / 10 - systemTokens)
            chatMessages = trimmed.items
            dropped = trimmed.droppedCount
            strippedImages = trimmed.strippedImageCount
        }
        val messagesJson = if (systemPrompt != null) {
            buildMessagesJson(listOf(Triple(Role.SYSTEM, systemPrompt, emptyList<String>())) + chatMessages)
        } else {
            buildMessagesJson(chatMessages)
        }
        var body = buildChatBody(
            modelName = model.name,
            messages = messagesJson,
            stream = true,
            defaults = advancedParamsOf(model),
        )
        body = applyThinkingDepth(body, conversation?.thinkingDepth, model.name)
        body = mergeCustomBody(body, model.customBody)
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

    private suspend fun streamSingle(convId: Long, picker: PickerModel) {
        // 选择器数据是发送时刻的快照，落库前以最新状态复核厂商可用性
        val provider = providerDao.byId(picker.provider.id)
        if (provider == null || !provider.enabled) {
            _error.value = ChatError("厂商「${picker.provider.alias}」已被停用，请到「厂商」页启用后再发送")
            return
        }
        val key = router.activeKey(provider)
        if (key == null || key.key.isBlank()) {
            _error.value = ChatError("厂商「${picker.provider.alias}」未配置 API Key，请到「厂商」页添加")
            return
        }

        val conversation = conversationDao.byId(convId)
        val history = messageDao.forConversation(convId)
        val built = buildRequestBody(provider, picker.model, conversation, history)
        val extraHeaders = parseCustomHeaders(picker.model.customHeaders)

        _notice.value = trimNotice(built.droppedCount, built.strippedImageCount)
        _error.value = null
        _streaming.value = true
        _streamingText.value = ""
        _streamingReasoning.value = ""
        activeStreamModel = picker.provider.id to picker.model.name
        val publisher = StreamPublisher()
        sendJob = viewModelScope.launch {
            var failed: ChatError? = null
            try {
                client.chatDeltas(provider.baseUrl, key.key, built.body, extraHeaders, provider.protocol).collect { ev ->
                    when (ev) {
                        is ChatEvent.Delta -> publisher.delta(ev.text)
                        is ChatEvent.ReasoningDelta -> publisher.reasoning(ev.text)
                        is ChatEvent.Failed -> failed = ChatError(ev.message, ev.httpCode, ev.rawBody)
                        ChatEvent.Done -> Unit
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

    /** smartAi：按智能路由候选顺序发起请求，首字前失败/超时自动切换下一个 */
    private suspend fun streamSmart(convId: Long) {
        val routes = smartRouteDao.all().filter { it.enabled }
        if (routes.isEmpty()) {
            _error.value = ChatError("smartAi 没有启用的候选模型，请到「厂商」页右上角 → 智能路由配置")
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
        val prepared = routes.mapNotNull { route ->
            val resolved = router.resolveById(route.providerId, route.modelName) ?: return@mapNotNull null
            val key = resolved.apiKey ?: return@mapNotNull null
            if (key.key.isBlank()) return@mapNotNull null
            // 指向本机网关自身的候选跳过，避免自连回环
            if (isSelfGatewayUrl(resolved.provider.baseUrl, settings.lanPort.value)) return@mapNotNull null
            // 模型配置行可能已被删除，此时退回空配置继续路由
            val model = resolved.model
                ?: ModelEntity(providerId = resolved.provider.id, name = route.modelName)
            val built = buildRequestBody(resolved.provider, model, conversation, history)
            if (built.droppedCount > maxDropped) maxDropped = built.droppedCount
            if (built.strippedImageCount > maxStripped) maxStripped = built.strippedImageCount
            SmartCandidate(
                label = "${resolved.provider.alias}/${model.alias?.takeIf { it.isNotBlank() } ?: model.name}",
                providerId = resolved.provider.id,
                modelName = model.name,
                baseUrl = resolved.provider.baseUrl,
                apiKey = key.key,
                extraHeaders = parseCustomHeaders(model.customHeaders),
                body = built.body,
                protocol = resolved.provider.protocol,
            ) to model
        }
        _notice.value = trimNotice(maxDropped, maxStripped)
        // 健康桶整链取同一时刻：冷却/降权候选垫底，桶内按特征匹配排序
        val now = System.currentTimeMillis()
        val candidates = orderCandidates(
            prepared,
            features,
            modelOf = { it.second },
            bucketOf = { SmartHealth.bucket(it.first.providerId, it.first.modelName, now) },
        ).map { it.first }
        if (candidates.isEmpty()) {
            _error.value = ChatError("smartAi 的候选模型均不可用（缺少 API Key、已被删除或厂商已停用）")
            return
        }
        val policy = SmartPolicy(
            mode = settings.smartResponseMode.value,
            fastMs = settings.smartFastFirstTokenMs.value.toLong(),
            chatMs = settings.smartFirstTokenTimeoutSeconds.value * 1000L,
            leisureMs = settings.smartLeisureFirstTokenSeconds.value * 1000L,
            cooldownThreshold = settings.smartCooldownThreshold.value,
            cooldownMs = settings.smartCooldownSeconds.value * 1000L,
        )
        val maxRounds = settings.smartRouteMaxRounds.value

        _error.value = null
        _streaming.value = true
        _streamingText.value = ""
        _streamingReasoning.value = ""
        val publisher = StreamPublisher()
        sendJob = viewModelScope.launch {
            var failed: ChatError? = null
            try {
                smartExecutor.stream(candidates, policy, maxRounds).collect { ev ->
                    when (ev) {
                        is SmartEvent.Delta -> publisher.delta(ev.text)
                        is SmartEvent.ReasoningDelta -> publisher.reasoning(ev.text)
                        is SmartEvent.Failed -> failed = ChatError(ev.message, ev.httpCode, ev.rawBody)
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

    /** 收尾：发布剩余内容并落库助手消息，失败则呈现错误 */
    private suspend fun finishStreaming(convId: Long, publisher: StreamPublisher, failed: ChatError?) {
        publisher.publish(force = true)
        _streaming.value = false
        val finalContent = _streamingText.value
        val finalReasoning = _streamingReasoning.value.trim().takeIf { it.isNotEmpty() }
        _streamingText.value = ""
        _streamingReasoning.value = ""
        if (finalContent.isNotEmpty() || finalReasoning != null) {
            messageDao.insert(
                MessageEntity(
                    conversationId = convId,
                    role = Role.ASSISTANT,
                    content = finalContent,
                    reasoning = finalReasoning,
                    providerId = activeStreamModel?.first,
                    modelName = activeStreamModel?.second,
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
        _streamingText.value = ""
        _streamingReasoning.value = ""
        activeStreamModel = null
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
    ) {
        val convId = message.conversationId
        if (convId != currentId.value) return
        if (newText.isBlank() && keepDataUrls.isEmpty() && newImageUris.isEmpty()) return
        if (_streaming.value) {
            _error.value = ChatError("上一条回复尚未结束，请先停止或等待完成")
            return
        }
        val target = currentTarget.value
        if (target == null) {
            _error.value = ChatError("请先选择模型")
            return
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

    fun stopStreaming() {
        val partial = _streamingText.value
        val partialReasoning = _streamingReasoning.value.trim().takeIf { it.isNotEmpty() }
        val model = activeStreamModel
        activeStreamModel = null
        sendJob?.cancel()
        _streaming.value = false
        _streamingText.value = ""
        _streamingReasoning.value = ""
        if (partial.isNotEmpty() || partialReasoning != null) {
            viewModelScope.launch {
                val convId = currentId.value ?: return@launch
                messageDao.insert(
                    MessageEntity(
                        conversationId = convId,
                        role = Role.ASSISTANT,
                        content = partial,
                        reasoning = partialReasoning,
                        providerId = model?.first,
                        modelName = model?.second,
                    )
                )
                conversationDao.touch(convId, System.currentTimeMillis())
            }
        }
    }

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

    private fun stopStreamingInternal() {
        val partial = _streamingText.value
        val partialReasoning = _streamingReasoning.value.trim().takeIf { it.isNotEmpty() }
        val model = activeStreamModel
        activeStreamModel = null
        sendJob?.cancel()
        _streaming.value = false
        _streamingText.value = ""
        _streamingReasoning.value = ""
        if (partial.isNotEmpty() || partialReasoning != null) {
            val convId = currentId.value
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
        stopStreamingInternal()
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
                    { it.model.name.lowercase() },
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
