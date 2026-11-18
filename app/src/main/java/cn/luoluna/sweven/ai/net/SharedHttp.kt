package cn.luoluna.sweven.ai.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 工具层共享的 OkHttpClient（联网搜索 / 自定义 HTTP 工具 / 网页正文抓取）。
 *
 * 与 [ProviderClient] 的私有 client 分开：工具调用是「一轮对话里的一个动作」，超时更短、
 * 不应占用聊天流式的长读超时；同时挂上 [NetworkLog] 拦截器，这些请求会出现在
 * 「设置 → 网络日志」里（tag=other），便于排查搜索/自定义接口失败。
 */
object SharedHttp {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(NetworkLog.interceptor)
            .build()
    }

    /** Model-controlled fetch/custom/test requests must use this default PUBLIC_ONLY entry point. */
    fun execute(request: Request, timeoutMillis: Long = 30_000): Response =
        OutboundPolicy.Transport(client).execute(request, timeoutMillis = timeoutMillis)

    /** Only the settings-backed SearXNG path may opt into LAN, scoped to its initial origin. */
    internal fun executeConfiguredSearch(request: Request, timeoutMillis: Long, allowLan: Boolean): Response =
        OutboundPolicy.Transport(client).execute(
            request,
            if (allowLan) OutboundPolicy.Mode.ALLOW_LAN else OutboundPolicy.Mode.PUBLIC_ONLY,
            timeoutMillis,
        )
}
