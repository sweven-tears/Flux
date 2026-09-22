package cn.luoluna.sweven.ai.tools.local

import cn.luoluna.sweven.ai.tools.FluxTool
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

/**
 * JS 执行工具（QuickJS 沙箱，移植自 rikkahub JavascriptTool.kt）：
 * - ES2020，无 DOM / Node API；返回值为最后一条表达式的值
 * - console.log/info/warn/error 捕获到 logs 字段
 * - 10 秒超时、64MB 内存、256KB 栈、日志与结果均限长
 * 默认关闭（native 体积 + 安全面），需用户在工具中心主动开启。
 */
internal fun buildJavascriptTool(): FluxTool = FluxTool(
    name = "eval_javascript",
    displayName = "JS 执行器",
    description = "在 QuickJS 沙箱中执行 JavaScript（ES2020），返回最后一条表达式的值；" +
        "console 输出捕获到 logs；无 DOM/Node API；小数计算请用 toFixed() 控制精度",
    parameters = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put(
                    "code",
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("description", JsonPrimitive("要执行的 JavaScript 代码"))
                    },
                )
            },
        )
        put("required", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("code")) })
    },
) { args ->
    val code = args.jsonObject["code"]?.jsonPrimitive?.contentOrNull
        ?: error("code 不能为空")
    evaluateJavascript(code)
}

internal suspend fun evaluateJavascript(
    code: String,
    timeoutMillis: Long = JS_EXECUTION_TIMEOUT_MS,
): String = withContext(Dispatchers.Default) {
    val logs = StringBuilder()
    var logsTruncated = false
    fun appendLog(line: String) {
        val remaining = JS_MAX_LOG_CHARS - logs.length
        if (remaining <= 0) {
            logsTruncated = true
            return
        }
        val entry = if (logs.isEmpty()) line else "\n$line"
        logs.append(entry.take(remaining))
        if (entry.length > remaining) logsTruncated = true
    }

    fun errorPayload(message: String) = buildJsonObject {
        put("error", message)
    }.toString()

    try {
        withTimeoutOrNull(timeoutMillis.milliseconds) {
            // 该绑定在超时/取消时中断原生求值，并在执行（含结果序列化）停止后才关闭运行时
            quickJs(Dispatchers.Default) {
                memoryLimit = JS_MEMORY_LIMIT_BYTES
                maxStackSize = JS_MAX_STACK_BYTES
                evaluationTimeoutMillis = timeoutMillis
                function("__fluxLog") { args ->
                    appendLog(args[0] as String)
                }
                // 在限时求值内部完成值转换：getter/toJSON 可执行 JS，返回任意对象也会绕过输出上限
                val result = evaluate<String?>(
                    """
                    (() => {
                        const log = globalThis.__fluxLog;
                        delete globalThis.__fluxLog;
                        const stringify = JSON.stringify;
                        const toString = String;
                        const format = value => {
                            if (value !== null && typeof value === 'object') {
                                try { return stringify(value); } catch (_) {}
                            }
                            return toString(value);
                        };
                        globalThis.console = {};
                        for (const level of ['log', 'info', 'warn', 'error', 'debug']) {
                            console[level] = (...args) => {
                                let line = '[' + (level === 'debug' ? 'LOG' : level.toUpperCase()) + ']';
                                for (const arg of args) {
                                    line += ' ' + format(arg);
                                    if (line.length > $JS_MAX_LOG_CHARS) {
                                        line = line.slice(0, $JS_MAX_LOG_CHARS) + ' [truncated]';
                                        break;
                                    }
                                }
                                log(line);
                            };
                        }
                        const result = (0, eval)(${JsonPrimitive(code)});
                        if (result == null) return null;
                        const type = typeof result;
                        const text = type === 'object' || type === 'function'
                            ? stringify(result) : toString(result);
                        if (text != null && text.length > $JS_MAX_RESULT_CHARS) {
                            throw new Error('JavaScript result exceeds output limit');
                        }
                        return text == null ? null : text;
                    })()
                    """.trimIndent()
                )
                buildJsonObject {
                    if (logs.isNotEmpty()) {
                        put("logs", logs.toString() + if (logsTruncated) "\n[Logs truncated]" else "")
                    }
                    put("result", result?.let(::JsonPrimitive) ?: JsonNull)
                }.toString()
            }
        } ?: errorPayload("JavaScript 执行超时（${timeoutMillis}ms）")
    } catch (_: QuickJsInterruptedException) {
        errorPayload("JavaScript 执行超时（${timeoutMillis}ms）")
    } catch (e: QuickJsException) {
        errorPayload((e.message ?: "JavaScript 执行失败").take(JS_MAX_LOG_CHARS))
    }
}

private const val JS_EXECUTION_TIMEOUT_MS = 10_000L
private const val JS_MEMORY_LIMIT_BYTES = 64L * 1024 * 1024
private const val JS_MAX_STACK_BYTES = 256L * 1024
private const val JS_MAX_LOG_CHARS = 64 * 1024
private const val JS_MAX_RESULT_CHARS = 1024 * 1024
