package cn.luoluna.sweven.ai.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 工具层共享的 OkHttpClient（联网搜索 / 自定义 HTTP 工具 / 网页正文抓取）。
 *
 * 与 [ProviderClient] 的私有 client 分开：工具调用是「一轮对话里的一个动作」，超时更短、
 * 不应占用聊天流式的长读超时；同时挂上 [NetworkLog] 拦截器，这些请求会出现在
 * 「设置 → 网络日志」里（tag=other），便于排查搜索/自定义接口失败。
 */
object SharedHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(NetworkLog.interceptor)
            .build()
    }
}
