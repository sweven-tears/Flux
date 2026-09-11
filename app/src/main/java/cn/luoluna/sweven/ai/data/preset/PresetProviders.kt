package cn.luoluna.sweven.ai.data.preset

import cn.luoluna.sweven.ai.net.ProviderProtocol

data class PresetProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val note: String = "",
    /** 接口协议（net.ProviderProtocol 取值），缺省 OpenAI 兼容 */
    val protocol: String = ProviderProtocol.OPENAI,
)

object Presets {
    val all: List<PresetProvider> = listOf(
        PresetProvider("openai", "OpenAI", "https://api.openai.com/v1"),
        PresetProvider("deepseek", "DeepSeek", "https://api.deepseek.com/v1"),
        PresetProvider("moonshot", "Kimi / Moonshot", "https://api.moonshot.cn/v1"),
        PresetProvider("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4"),
        PresetProvider("qwen", "通义千问 Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        PresetProvider("siliconflow", "SiliconFlow 硅基流动", "https://api.siliconflow.cn/v1"),
        PresetProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
        PresetProvider(
            "anthropic", "Anthropic", "https://api.anthropic.com/v1",
            protocol = ProviderProtocol.ANTHROPIC,
        ),
        // Gemini 预设走官方 OpenAI 兼容层（内置工具即用）；需要原生协议可在详情页切换
        PresetProvider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
        PresetProvider("xai", "xAI Grok", "https://api.x.ai/v1"),
        PresetProvider("ollama", "Ollama（本机）", "http://127.0.0.1:11434/v1", "手机上运行时请改为电脑的局域网 IP"),
    )
}
