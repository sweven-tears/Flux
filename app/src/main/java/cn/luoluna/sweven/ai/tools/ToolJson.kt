package cn.luoluna.sweven.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 工具执行结果的错误形态：`{"error":"..."}`。
 * 与内置工具、`ChatViewModel.runTool` 的约定一致——错误也作为「工具结果文本」回给模型，
 * 让模型自己纠正（换关键词/换工具/直接回答），而不是中断整轮对话。
 */
internal fun toolErrorJson(message: String): String = buildJsonObject { put("error", message) }.toString()
