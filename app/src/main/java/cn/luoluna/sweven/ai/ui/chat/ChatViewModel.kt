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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 模型选择器条目：厂商 + 模型 */
data class PickerModel(val provider: ProviderEntity, val model: ModelEntity) {
    /** 局域网聚合 id（按模型 id） */
    val aggregateId: String get() = "${provider.alias}/${model.name}"
    /** 展示名（优先别名） */
    val displayName: String get() = model.alias?.takeIf { it.isNotBlank() } ?: model.name
    val displayId: String get() = "${provider.alias}/$displayName"
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
    private val client: ProviderClient = ctx.providerClient
    private val router = ctx.modelRouter
    private val settings = ctx.settings

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
            val providerRank = providers.withIndex().associate { it.value.id to it.index }
            models.mapNotNull { m ->
                providers.firstOrNull { it.id == m.providerId }?.let { PickerModel(it, m) }
            }.sortedWith(
                // 厂商按用户拖拽顺序分组，厂商内按模型拖拽顺序
                compareBy(
                    { providerRank[it.provider.id] ?: Int.MAX_VALUE },
                    { it.model.sortOrder },
                    { it.model.name.lowercase() },
                )
            )
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

    /** 当前生效模型：优先会话自己选的，否则回退到上次使用的模型 */
    val currentModel: StateFlow<PickerModel?> =
        combine(current, pickerModels, lastModel) { conv, models, last ->
            val fromConv = conv?.let { c ->
                models.firstOrNull { it.provider.id == c.providerId && it.model.name == c.modelName }
            }
            fromConv ?: last?.let { (pid, name) ->
                models.firstOrNull { it.provider.id == pid && it.model.name == name }
            }
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

    fun newConversation() = viewModelScope.launch {
        stopStreamingInternal()
        val last = lastModel.value
        val id = conversationDao.insert(
            ConversationEntity(
                providerId = last?.first,
                modelName = last?.second,
            )
        )
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

    fun selectModel(providerId: Long, modelName: String) = viewModelScope.launch {
        settings.setLastModel(providerId, modelName)
        val convId = currentId.value
            ?: conversationDao.insert(
                ConversationEntity(providerId = providerId, modelName = modelName)
            ).also { currentId.value = it }
        conversationDao.setModel(convId, providerId, modelName)
    }

    fun dismissError() {
        _error.value = null
    }

    fun send(text: String, imageUris: List<Uri>) {
        val picker = currentModel.value
        if (picker == null) {
            _error.value = "请先选择模型"
            return
        }
        if (text.isBlank() && imageUris.isEmpty()) return
        viewModelScope.launch {
            var convId = currentId.value
            if (convId == null) {
                convId = conversationDao.insert(
                    ConversationEntity(providerId = picker.provider.id, modelName = picker.model.name)
                )
                currentId.value = convId
                _pendingThinkingDepth.value?.let {
                    conversationDao.setThinkingDepth(convId, it)
                    _pendingThinkingDepth.value = null
                }
            } else if (current.value?.providerId == null) {
                // 会话没有固定模型时，把实际使用的模型写回会话
                conversationDao.setModel(convId, picker.provider.id, picker.model.name)
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
            if (isFirst && text.isNotBlank()) {
                conversationDao.rename(convId, text.take(16).replace('\n', ' ').trim())
            }
            conversationDao.touch(convId, System.currentTimeMillis())

            startStreaming(convId, picker)
        }
    }

    /** 依据会话内现有消息构建请求体并发起流式请求，完成后将助手消息落库 */
    private suspend fun startStreaming(convId: Long, picker: PickerModel) {
        val key = router.activeKey(picker.provider)
        if (key == null || key.key.isBlank()) {
            _error.value = "厂商「${picker.provider.alias}」未配置 API Key，请到「厂商」页添加"
            return
        }

        val history = messageDao.forConversation(convId)
        val chatMessages = history.map { Triple(it.role, it.content, it.images) }
        val conversation = conversationDao.byId(convId)
        val systemPrompt = conversation?.systemPrompt?.takeIf { it.isNotBlank() }
        val messagesJson = if (systemPrompt != null) {
            buildMessagesJson(listOf(Triple(Role.SYSTEM, systemPrompt, emptyList<String>())) + chatMessages)
        } else {
            buildMessagesJson(chatMessages)
        }
        var body = buildChatBody(
            modelName = picker.model.name,
            messages = messagesJson,
            stream = true,
            defaults = advancedParamsOf(picker.model),
        )
        body = applyThinkingDepth(body, conversation?.thinkingDepth, picker.model.name)
        body = mergeCustomBody(body, picker.model.customBody)
        if (picker.provider.presetId == "gemini") {
            body = applyBuiltinTools(
                body,
                picker.model.builtinGoogleSearch,
                picker.model.builtinUrlContext,
                picker.model.builtinImageGen,
            )
        }
        val extraHeaders = parseCustomHeaders(picker.model.customHeaders)

        _error.value = null
        _streaming.value = true
        _streamingText.value = ""
        _streamingReasoning.value = ""
        val thinkParser = ThinkParser()
        val explicitReasoning = StringBuilder()
        sendJob = viewModelScope.launch {
            var failed: String? = null
            // 增量先攒进缓冲区，最多每隔 STREAM_PUBLISH_INTERVAL_MS 才解析并发布一次，
            // 避免每个 token 都在主线程做全量重解析 + 触发重组
            val pending = StringBuilder()
            var lastPublishAt = 0L
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
            client.chatDeltas(picker.provider.baseUrl, key.key, body, extraHeaders).collect { ev ->
                when (ev) {
                    is ChatEvent.Delta -> {
                        pending.append(ev.text)
                        publish()
                    }
                    is ChatEvent.ReasoningDelta -> {
                        explicitReasoning.append(ev.text)
                        publish()
                    }
                    is ChatEvent.Failed -> failed = ev.message
                    ChatEvent.Done -> Unit
                }
            }
            publish(force = true)
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
            val picker = currentModel.value ?: return@launch
            startStreaming(convId, picker)
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

    /** 用预设对话新建会话：应用其系统提示词与模型 */
    fun startFromPreset(preset: PresetEntity) = viewModelScope.launch {
        stopStreamingInternal()
        val id = conversationDao.insert(
            ConversationEntity(
                title = preset.name,
                providerId = preset.providerId ?: lastModel.value?.first,
                modelName = preset.modelName ?: lastModel.value?.second,
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
