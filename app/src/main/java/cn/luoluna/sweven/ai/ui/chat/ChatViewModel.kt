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
import cn.luoluna.sweven.ai.data.repo.SmartRouteExecutor
import cn.luoluna.sweven.ai.data.repo.aggregateCapability
import cn.luoluna.sweven.ai.data.repo.featuresOfHistory
import cn.luoluna.sweven.ai.data.repo.isSelfGatewayUrl
import cn.luoluna.sweven.ai.data.repo.orderCandidates
import cn.luoluna.sweven.ai.net.AdvancedParams
import cn.luoluna.sweven.ai.net.ChatEvent
import cn.luoluna.sweven.ai.net.ProviderClient
import cn.luoluna.sweven.ai.net.ThinkParser
import cn.luoluna.sweven.ai.net.applyBuiltinTools
import cn.luoluna.sweven.ai.net.applyThinkingDepth
import cn.luoluna.sweven.ai.net.buildChatBody
import cn.luoluna.sweven.ai.net.buildMessagesJson
import cn.luoluna.sweven.ai.net.mergeCustomBody
import cn.luoluna.sweven.ai.net.parseCustomHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
            buildPickerModels(providers, models)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 全部模型（含禁用）：预设编辑弹窗使用，避免预设模型已禁用时编辑一次就被静默清空 */
    val allPickerModels: StateFlow<List<PickerModel>> =
        combine(providerDao.observeAll(), modelDao.observeAll()) { providers, models ->
            buildPickerModels(providers, models)
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

    /** 智能路由启用的候选数量（>0 时聊天选择器显示 smartAi） */
    private val smartEnabledCount: StateFlow<Int> =
        smartRouteDao.observeAll().map { routes -> routes.count { it.enabled } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 聊天模型选择器是否显示 smartAi 条目 */
    val smartAiAvailable: StateFlow<Boolean> =
        smartEnabledCount.map { it > 0 }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** smartAi 聚合能力（启用候选的模态/能力并集）：SmartAi 目标下附件按钮与思考深度的显示依据 */
    val smartAiCapability: StateFlow<SmartAggregateCapability> =
        combine(smartRouteDao.observeAll(), modelDao.observeAll()) { routes, models ->
            aggregateCapability(
                routes.filter { it.enabled }.mapNotNull { route ->
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

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText

    private val _streamingReasoning = MutableStateFlow("")
    val streamingReasoning: StateFlow<String> = _streamingReasoning

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var sendJob: Job? = null

    init {
        viewModelScope.launch {
            val latest = conversationDao.observeAll().first().firstOrNull()
            if (latest != null) currentId.value = latest.id
        }
    }

    // ---- 会话 ----

    fun newConversation(folderId: Long? = null) = viewModelScope.launch {
        stopStreamingInternal()
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
        if (target == null) {
            _error.value = "请先选择模型"
            return
        }
        if (text.isBlank() && imageUris.isEmpty()) return
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

    /** 构建聊天请求体（单个模型与 smartAi 候选共用） */
    private suspend fun buildRequestBody(
        provider: ProviderEntity,
        model: ModelEntity,
        conversation: ConversationEntity?,
        history: List<MessageEntity>,
    ): JsonObject {
        val chatMessages = history.map { Triple(it.role, it.content, it.images) }
        val systemPrompt = conversation?.systemPrompt?.takeIf { it.isNotBlank() }
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
        if (provider.presetId == "gemini") {
            body = applyBuiltinTools(
                body,
                model.builtinGoogleSearch,
                model.builtinUrlContext,
                model.builtinImageGen,
            )
        }
        return body
    }

    private suspend fun streamSingle(convId: Long, picker: PickerModel) {
        val key = router.activeKey(picker.provider)
        if (key == null || key.key.isBlank()) {
            _error.value = "厂商「${picker.provider.alias}」未配置 API Key，请到「厂商」页添加"
            return
        }

        val conversation = conversationDao.byId(convId)
        val history = messageDao.forConversation(convId)
        val body = buildRequestBody(picker.provider, picker.model, conversation, history)
        val extraHeaders = parseCustomHeaders(picker.model.customHeaders)

        _error.value = null
        _streaming.value = true
        _streamingText.value = ""
        _streamingReasoning.value = ""
        val publisher = StreamPublisher()
        sendJob = viewModelScope.launch {
            var failed: String? = null
            try {
                client.chatDeltas(picker.provider.baseUrl, key.key, body, extraHeaders).collect { ev ->
                    when (ev) {
                        is ChatEvent.Delta -> publisher.delta(ev.text)
                        is ChatEvent.ReasoningDelta -> publisher.reasoning(ev.text)
                        is ChatEvent.Failed -> failed = ev.message
                        ChatEvent.Done -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = e.message ?: e.javaClass.simpleName
            }
            finishStreaming(convId, publisher, failed)
        }
    }

    /** smartAi：按智能路由候选顺序发起请求，首字前失败/超时自动切换下一个 */
    private suspend fun streamSmart(convId: Long) {
        val routes = smartRouteDao.all().filter { it.enabled }
        if (routes.isEmpty()) {
            _error.value = "smartAi 没有启用的候选模型，请到「厂商」页右上角 → 智能路由配置"
            return
        }
        val conversation = conversationDao.byId(convId)
        val history = messageDao.forConversation(convId)
        // 带图片附件优先视觉候选、开了思考深度优先推理候选，其余保持原顺序兜底
        val features = featuresOfHistory(
            hasImageAttachment = history.any { it.images.isNotEmpty() },
            thinkingDepth = conversation?.thinkingDepth,
        )
        val prepared = routes.mapNotNull { route ->
            val resolved = router.resolveById(route.providerId, route.modelName) ?: return@mapNotNull null
            val key = resolved.apiKey ?: return@mapNotNull null
            if (key.key.isBlank()) return@mapNotNull null
            // 指向本机网关自身的候选跳过，避免自连回环
            if (isSelfGatewayUrl(resolved.provider.baseUrl, settings.lanPort.value)) return@mapNotNull null
            // 模型配置行可能已被删除，此时退回空配置继续路由
            val model = resolved.model
                ?: ModelEntity(providerId = resolved.provider.id, name = route.modelName)
            SmartCandidate(
                label = "${resolved.provider.alias}/${model.alias?.takeIf { it.isNotBlank() } ?: model.name}",
                baseUrl = resolved.provider.baseUrl,
                apiKey = key.key,
                extraHeaders = parseCustomHeaders(model.customHeaders),
                body = buildRequestBody(resolved.provider, model, conversation, history),
            ) to model
        }
        val candidates = orderCandidates(prepared, features) { it.second }.map { it.first }
        if (candidates.isEmpty()) {
            _error.value = "smartAi 的候选模型均不可用（缺少 API Key 或已被删除）"
            return
        }
        val timeoutMs = settings.smartFirstTokenTimeoutSeconds.value * 1000L

        _error.value = null
        _streaming.value = true
        _streamingText.value = ""
        _streamingReasoning.value = ""
        val publisher = StreamPublisher()
        sendJob = viewModelScope.launch {
            var failed: String? = null
            try {
                smartExecutor.stream(candidates, timeoutMs).collect { ev ->
                    when (ev) {
                        is SmartEvent.Delta -> publisher.delta(ev.text)
                        is SmartEvent.ReasoningDelta -> publisher.reasoning(ev.text)
                        is SmartEvent.Failed -> failed = ev.message
                        is SmartEvent.Trying, SmartEvent.Done -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = e.message ?: e.javaClass.simpleName
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
    private suspend fun finishStreaming(convId: Long, publisher: StreamPublisher, failed: String?) {
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
                )
            )
            conversationDao.touch(convId, System.currentTimeMillis())
        }
        failed?.let { _error.value = it }
    }

    /** 取消流式并丢弃未完成的半截内容（重新生成前调用，不落库） */
    private fun cancelStreamingDiscard() {
        sendJob?.cancel()
        _streaming.value = false
        _streamingText.value = ""
        _streamingReasoning.value = ""
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
                    )
                )
                conversationDao.touch(convId, System.currentTimeMillis())
            }
        }
    }

    private fun stopStreamingInternal() {
        val partial = _streamingText.value
        val partialReasoning = _streamingReasoning.value.trim().takeIf { it.isNotEmpty() }
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

        /** 厂商按用户拖拽顺序分组，厂商内按模型拖拽顺序 */
        private fun buildPickerModels(
            providers: List<ProviderEntity>,
            models: List<ModelEntity>,
        ): List<PickerModel> {
            val providerRank = providers.withIndex().associate { it.value.id to it.index }
            return models.mapNotNull { m ->
                providers.firstOrNull { it.id == m.providerId }?.let { PickerModel(it, m) }
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
