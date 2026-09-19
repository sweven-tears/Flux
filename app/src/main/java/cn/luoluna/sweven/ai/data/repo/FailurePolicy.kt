package cn.luoluna.sweven.ai.data.repo

/**
 * 上游失败的分类：决定「该不该换下一个候选」「要不要冷却这个候选」「是否立即终态」。
 * 依据只有 HTTP 状态码（[classifyFailure]）——把 400 参数错误也当上游故障去轮巡 N 轮，
 * 会让用户白等好几倍的首字超时才看到一句「全部候选均不可用」。
 */
enum class FailureKind {
    /** 400 / 422：请求本身有问题，换候选也没用 → 立即终态 */
    PARAM,

    /** 401 / 403：凭证问题。单个厂商 Key 失效仍应换候选（别家可能是好的），本轮全部鉴权失败才终态 */
    AUTH,

    /** 404：模型不存在（可能是模型名映射问题，换候选有用） */
    MODEL,

    /** 429：限流，换候选并把该候选冷却一段时间 */
    RATE_LIMIT,

    /** 408 / 5xx / 其他 4xx：上游故障，换候选 */
    UPSTREAM,

    /** 连接失败（拒绝 / DNS / 坏地址）/ 流中断 */
    NETWORK,

    /** 首字超时 / 建连超时 / 无输出 */
    TIMEOUT,

    /** 全部候选耗尽（终态汇总，不是单个候选的失败原因） */
    EXHAUSTED,
}

/** 是否立即终止整个路由：不再换候选、不再轮巡 */
val FailureKind.terminal: Boolean get() = this == FailureKind.PARAM

/**
 * 是否计入连续失败（进而触发冷却）。
 * 参数错误、鉴权失败、模型不存在都不是「这个候选病了」，把它们打进冷却会让下次请求绕开本来好用的候选。
 */
val FailureKind.countsTowardCooldown: Boolean
    get() = this == FailureKind.UPSTREAM || this == FailureKind.NETWORK || this == FailureKind.TIMEOUT

/** HTTP 状态码 → 失败分类；null（连接失败 / 超时 / 流中断）按网络问题归类，由调用方在超时场景改判 [FailureKind.TIMEOUT] */
fun classifyFailure(httpCode: Int?): FailureKind = when (httpCode) {
    400, 422 -> FailureKind.PARAM
    401, 403 -> FailureKind.AUTH
    404 -> FailureKind.MODEL
    429 -> FailureKind.RATE_LIMIT
    null -> FailureKind.NETWORK
    else -> FailureKind.UPSTREAM
}

/** 失败分类的短标签（聊天页错误条与详情半窗展示，替代「全部候选均不可用」的一刀切文案） */
fun FailureKind.label(): String = when (this) {
    FailureKind.PARAM -> "请求参数问题"
    FailureKind.AUTH -> "鉴权失败"
    FailureKind.MODEL -> "模型不存在"
    FailureKind.RATE_LIMIT -> "上游限流"
    FailureKind.UPSTREAM -> "上游故障"
    FailureKind.NETWORK -> "连接失败"
    FailureKind.TIMEOUT -> "上游超时"
    FailureKind.EXHAUSTED -> "候选全部不可用"
}
