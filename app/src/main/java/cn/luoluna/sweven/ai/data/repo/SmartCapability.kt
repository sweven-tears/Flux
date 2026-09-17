package cn.luoluna.sweven.ai.data.repo

import cn.luoluna.sweven.ai.data.SmartModes
import cn.luoluna.sweven.ai.data.db.ModelEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** smartAi 聚合能力：启用候选模型的模态与能力并集 */
data class SmartAggregateCapability(
    val inputText: Boolean,
    val inputImage: Boolean,
    val inputVideo: Boolean,
    val outputText: Boolean,
    val outputImage: Boolean,
    val outputVideo: Boolean,
    val tools: Boolean,
    val reasoning: Boolean,
)

/**
 * 对候选模型求能力并集。input/output 数组只表达「可接受的输入集合 / 可产出的输出集合」，
 * 不承诺同一请求内输入输出配对（如并集后可能呈现 文图-文图，实际不存在 图-图 候选），
 * 实际路由由 [orderCandidates] 按请求特征动态匹配。
 */
fun aggregateCapability(models: List<ModelEntity>): SmartAggregateCapability {
    val cap = SmartAggregateCapability(
        inputText = models.any { it.inputText },
        inputImage = models.any { it.inputImage },
        inputVideo = models.any { it.inputVideo },
        outputText = models.any { it.outputText },
        outputImage = models.any { it.outputImage },
        outputVideo = models.any { it.outputVideo },
        tools = models.any { it.capabilityTools },
        reasoning = models.any { it.capabilityReasoning },
    )
    // 候选存在但模态全空（异常数据）时兜底回文本，避免他端拿到空 input/output
    if (models.isNotEmpty()) {
        if (!cap.inputText && !cap.inputImage && !cap.inputVideo) return cap.copy(inputText = true)
        if (!cap.outputText && !cap.outputImage && !cap.outputVideo) return cap.copy(outputText = true)
    }
    return cap
}

/**
 * 路由分组对外能力：成员能力交集（木桶短板口径）——某项能力全员都支持才对外声明，
 * 保证其他端按 /v1/models 声明调用时，请求落到组内任一成员都可用。
 * 实际路由仍由运行时严格模态过滤兜底（带图请求只走支持图像的成员）。
 */
fun intersectCapability(models: List<ModelEntity>): SmartAggregateCapability {
    if (models.isEmpty()) return aggregateCapability(models)
    var cap = SmartAggregateCapability(
        inputText = models.all { it.inputText },
        inputImage = models.all { it.inputImage },
        inputVideo = models.all { it.inputVideo },
        outputText = models.all { it.outputText },
        outputImage = models.all { it.outputImage },
        outputVideo = models.all { it.outputVideo },
        tools = models.all { it.capabilityTools },
        reasoning = models.all { it.capabilityReasoning },
    )
    // 与并集同款兜底：交集后模态全空（异常数据）时回退文本，避免他端拿到空 input/output
    if (!cap.inputText && !cap.inputImage && !cap.inputVideo) cap = cap.copy(inputText = true)
    if (!cap.outputText && !cap.outputImage && !cap.outputVideo) cap = cap.copy(outputText = true)
    return cap
}

/** 一项能力的短板：有成员支持但并非全员支持时该项被拖出交集，lacking 为拖后腿（不支持该项）的成员 */
data class CapabilityGap(
    val label: String,
    val lacking: List<ModelEntity>,
)

/** 逐项找出组内能力的短板成员（全员都不支持的项不算短板——没有可通过禁用成员回升的空间） */
fun capabilityGaps(models: List<ModelEntity>): List<CapabilityGap> {
    if (models.size < 2) return emptyList()
    val dims = listOf(
        "文本输入" to { m: ModelEntity -> m.inputText },
        "图像输入" to { m: ModelEntity -> m.inputImage },
        "视频输入" to { m: ModelEntity -> m.inputVideo },
        "文本输出" to { m: ModelEntity -> m.outputText },
        "图像输出" to { m: ModelEntity -> m.outputImage },
        "视频输出" to { m: ModelEntity -> m.outputVideo },
        "工具调用" to { m: ModelEntity -> m.capabilityTools },
        "推理" to { m: ModelEntity -> m.capabilityReasoning },
    )
    return dims.mapNotNull { (label, get) ->
        val lacking = models.filter { !get(it) }
        if (lacking.isNotEmpty() && lacking.size < models.size) CapabilityGap(label, lacking) else null
    }
}

/** 一次请求对候选能力的期望，用于候选排序（只排序不过滤，能力标记是启发式的） */
data class SmartRequestFeatures(
    val needsImageInput: Boolean = false,
    val needsVideoInput: Boolean = false,
    val wantsImageOutput: Boolean = false,
    val wantsVideoOutput: Boolean = false,
    val needsTools: Boolean = false,
    val needsReasoning: Boolean = false,
)

private val IMAGE_PART_TYPES = setOf("image_url", "input_image", "image")
private val VIDEO_PART_TYPES = setOf("video_url", "input_video", "video")

/** 从 OpenAI 风格请求体识别请求特征（局域网网关用） */
fun featuresOfRequestBody(body: JsonObject, messages: JsonArray): SmartRequestFeatures {
    var imageInput = false
    var videoInput = false
    for (msg in messages) {
        val obj = msg as? JsonObject ?: continue
        when (val content = obj["content"]) {
            is JsonArray -> for (part in content) {
                val type = (part as? JsonObject)?.get("type")?.jsonPrimitive?.content ?: continue
                if (type in IMAGE_PART_TYPES) imageInput = true
                if (type in VIDEO_PART_TYPES) videoInput = true
            }
            is JsonObject -> {
                val type = content["type"]?.jsonPrimitive?.content
                if (type in IMAGE_PART_TYPES) imageInput = true
                if (type in VIDEO_PART_TYPES) videoInput = true
            }
            else -> Unit
        }
    }
    val modalities = (body["modalities"] as? JsonArray)
        ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
        .orEmpty()
    return SmartRequestFeatures(
        needsImageInput = imageInput,
        needsVideoInput = videoInput,
        wantsImageOutput = "image" in modalities,
        wantsVideoOutput = "video" in modalities,
        needsTools = (body["tools"] as? JsonArray)?.any() == true,
        needsReasoning = body.containsKey("reasoning_effort") || body.containsKey("reasoning"),
    )
}

/** App 内聊天：从历史消息附件与思考深度识别请求特征 */
fun featuresOfHistory(
    hasImageAttachment: Boolean,
    thinkingDepth: String?,
): SmartRequestFeatures = SmartRequestFeatures(
    needsImageInput = hasImageAttachment,
    // 自动挡不注入思考参数，也不强制优先推理候选
    needsReasoning = !thinkingDepth.isNullOrBlank() && thinkingDepth != "off" && thinkingDepth != "auto",
)

/** 候选能力与请求特征的匹配得分：模态匹配权重高（不匹配大概率直接报错），工具/推理次之 */
private fun matchScore(model: ModelEntity?, f: SmartRequestFeatures): Int {
    if (model == null) return 0
    var score = 0
    if (f.needsImageInput && model.inputImage) score += 2
    if (f.needsVideoInput && model.inputVideo) score += 2
    if (f.wantsImageOutput && model.outputImage) score += 2
    if (f.wantsVideoOutput && model.outputVideo) score += 2
    if (f.needsTools && model.capabilityTools) score += 1
    if (f.needsReasoning && model.capabilityReasoning) score += 1
    return score
}

/**
 * 按请求特征 + 健康桶对候选稳定排序：
 * 桶优先（冷却/降权候选即使模态完全匹配也后置——踩坑成本是首字超时，远大于模态不匹配的快速报错），
 * 桶内匹配得分高者排前，同分保持原顺序（sortOrder）。只排序不过滤，不满足特征的候选留在链尾兜底。
 */
fun <T> orderCandidates(
    items: List<T>,
    features: SmartRequestFeatures,
    modelOf: (T) -> ModelEntity?,
    bucketOf: (T) -> Int = { 0 },
): List<T> {
    if (items.size <= 1) return items
    return items.sortedWith(
        compareBy(
            bucketOf,
            { -matchScore(modelOf(it), features) },
        )
    )
}

/**
 * 按请求模态剔除不能用的候选（自动选择）：输入/输出模态是硬条件——发错必然失败，
 * 所以直接不发；工具与推理只参与 [orderCandidates] 的排序加分（能力标记是启发式，不据此剔除）。
 * 模型配置行已被删除的候选无从判断能力，保留在链里兜底。
 */
fun <T> eligibleCandidates(
    items: List<T>,
    features: SmartRequestFeatures,
    modelOf: (T) -> ModelEntity?,
): List<T> = items.filter { item ->
    val m = modelOf(item) ?: return@filter true
    (!features.needsImageInput || m.inputImage) &&
        (!features.needsVideoInput || m.inputVideo) &&
        (!features.wantsImageOutput || m.outputImage) &&
        (!features.wantsVideoOutput || m.outputVideo)
}

/**
 * 候选准备：先按请求模态筛掉不能用的（[eligibleCandidates]），再按健康桶与特征匹配稳定排序
 * （[orderCandidates]）。flux-auto（全模型合集）与路由分组共用同一口径，两步之间可插入空集提示。
 */
fun <T> selectCandidates(
    items: List<T>,
    features: SmartRequestFeatures,
    modelOf: (T) -> ModelEntity?,
    bucketOf: (T) -> Int = { 0 },
): List<T> = orderCandidates(eligibleCandidates(items, features, modelOf), features, modelOf, bucketOf)

/** 请求模态的文字描述（候选被筛空时用于说明缺什么能力） */
fun SmartRequestFeatures.modalityLabel(): String = buildList {
    if (needsImageInput) add("图像输入")
    if (needsVideoInput) add("视频输入")
    if (wantsImageOutput) add("图像输出")
    if (wantsVideoOutput) add("视频输出")
}.joinToString("、")

/** 极速轮候选数量上限：限制速度未知候选的「学费」上界（被取消的尝试仍消耗上游输入 token） */
const val SMART_FAST_ROUND_LIMIT = 3

/**
 * 极速轮候选：健康（未降桶）且速度未知/实测达标的候选，最多取前 [limit] 个。
 * 已知慢的候选直接留给放宽轮，极速请求不在其身上开连接，避免重复浪费。
 */
inline fun <T> pickFastRound(
    items: List<T>,
    limit: Int,
    bucketOf: (T) -> Int,
    fastCapableOf: (T) -> Boolean,
): List<T> = items.filter { bucketOf(it) == 0 && fastCapableOf(it) }.take(limit.coerceAtLeast(1))

/**
 * 按响应模式与轮次决定本轮首字超时：
 * 极速模式仅首轮用极速档（一轮无成功后放宽到聊天档），聊天/随缘模式全轮同值
 */
fun firstTokenTimeoutMs(mode: String, round: Int, fastMs: Long, chatMs: Long, leisureMs: Long): Long = when {
    mode == SmartModes.FAST && round <= 1 -> fastMs
    mode == SmartModes.LEISURE -> leisureMs
    else -> chatMs
}
