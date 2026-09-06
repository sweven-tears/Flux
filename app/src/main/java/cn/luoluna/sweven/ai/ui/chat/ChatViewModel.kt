package cn.luoluna.sweven.ai.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.ConversationDao
import cn.luoluna.sweven.ai.data.db.ConversationEntity
import cn.luoluna.sweven.ai.data.db.MessageDao
import cn.luoluna.sweven.ai.data.db.MessageEntity
import cn.luoluna.sweven.ai.data.db.ModelDao
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.ProviderDao
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.data.db.Role
import cn.luoluna.sweven.ai.data.repo.ModelRoute
import cn.luoluna.sweven.ai.net.AdvancedParams
import cn.luoluna.sweven.ai.net.ChatEvent
import cn.luoluna.sweven.ai.net.ProviderClient
import cn.luoluna.sweven.ai.net.buildChatBody
import cn.luoluna.sweven.ai.net.buildMessagesJson
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
    val aggregateId: String get() = "${provider.alias}/${model.name}"
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app as App
    private val conversationDao: ConversationDao = ctx.database.conversationDao()
    private val messageDao: MessageDao = ctx.database.messageDao()
    private val providerDao: ProviderDao = ctx.database.providerDao()
    private val modelDao: ModelDao = ctx.database.modelDao()
    private val client: ProviderClient = ctx.providerClient
    private val router = ctx.modelRouter

    private val currentId = MutableStateFlow<Long?>(null)

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
            models.mapNotNull { m ->
                providers.firstOrNull { it.id == m.providerId }?.let { PickerModel(it, m) }
            }.sortedWith(compareBy({ it.provider.alias.lowercase() }, { it.model.name.lowercase() }))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前会话选中的模型路由（不含 Key，发送时再解析） */
    val currentModel: StateFlow<PickerModel?> =
        combine(current, pickerModels) { conv, models ->
            if (conv == null) null
            else models.firstOrNull {
                it.provider.id == conv.providerId && it.model.name == conv.modelName
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var sendJob: Job? = null

    init {
        viewModelScope.launch {
            val latest = conversationDao.observeAll().first().firstOrNull()
            if (latest != null) currentId.value = latest.id
        }
    }

    fun newConversation() = viewModelScope.launch {
        stopStreamingInternal()
        val id = conversationDao.insert(ConversationEntity())
        currentId.value = id
    }

    fun selectConversation(id: Long) {
        if (id == currentId.value) return
        stopStreamingInternal()
        currentId.value = id
    }

    fun deleteConversation(id: Long) = viewModelScope.launch {
        if (id == currentId.value) {
            stopStreamingInternal()
            currentId.value = null
        }
        conversationDao.byId(id)?.let { conversationDao.delete(it) }
    }

    fun selectModel(providerId: Long, modelName: String) = viewModelScope.launch {
        val convId = currentId.value
            ?: conversationDao.insert(ConversationEntity()).also { currentId.value = it }
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
            val convId = currentId.value
                ?: conversationDao.insert(ConversationEntity()).also { currentId.value = it }

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

            val key = router.activeKey(picker.provider)
            if (key == null || key.key.isBlank()) {
                _error.value = "厂商「${picker.provider.alias}」未配置 API Key，请到「厂商」页添加"
                return@launch
            }

            val history = messageDao.forConversation(convId)
            val body = buildChatBody(
                modelName = picker.model.name,
                messages = buildMessagesJson(history.map { Triple(it.role, it.content, it.images) }),
                stream = true,
                defaults = advancedParamsOf(picker.model),
            )

            _error.value = null
            _streaming.value = true
            _streamingText.value = ""
            val acc = StringBuilder()
            sendJob = viewModelScope.launch {
                var failed: String? = null
                client.chatDeltas(picker.provider.baseUrl, key.key, body).collect { ev ->
                    when (ev) {
                        is ChatEvent.Delta -> {
                            acc.append(ev.text)
                            _streamingText.value = acc.toString()
                        }
                        is ChatEvent.Failed -> failed = ev.message
                        ChatEvent.Done -> Unit
                    }
                }
                _streaming.value = false
                _streamingText.value = ""
                if (acc.isNotEmpty()) {
                    messageDao.insert(
                        MessageEntity(conversationId = convId, role = Role.ASSISTANT, content = acc.toString())
                    )
                    conversationDao.touch(convId, System.currentTimeMillis())
                }
                failed?.let { _error.value = it }
            }
        }
    }

    fun stopStreaming() {
        val partial = _streamingText.value
        sendJob?.cancel()
        _streaming.value = false
        _streamingText.value = ""
        if (partial.isNotEmpty()) {
            viewModelScope.launch {
                val convId = currentId.value ?: return@launch
                messageDao.insert(
                    MessageEntity(conversationId = convId, role = Role.ASSISTANT, content = partial)
                )
                conversationDao.touch(convId, System.currentTimeMillis())
            }
        }
    }

    private fun stopStreamingInternal() {
        val partial = _streamingText.value
        sendJob?.cancel()
        _streaming.value = false
        _streamingText.value = ""
        if (partial.isNotEmpty()) {
            val convId = currentId.value
            if (convId != null) {
                viewModelScope.launch {
                    messageDao.insert(
                        MessageEntity(conversationId = convId, role = Role.ASSISTANT, content = partial)
                    )
                }
            }
        }
    }

    companion object {
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
