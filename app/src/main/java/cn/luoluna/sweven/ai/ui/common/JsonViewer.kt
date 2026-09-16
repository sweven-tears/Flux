package cn.luoluna.sweven.ai.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val jsonParser = Json { ignoreUnknownKeys = true }

/** 判断文本是否为合法 JSON（供详情页决定是否提供树形视图） */
fun isJsonText(text: String): Boolean =
    runCatching { jsonParser.parseToJsonElement(text) }.isSuccess

/** JSON 美化：解析失败时原样返回 */
fun prettyJson(text: String): String = runCatching {
    jsonParser.parseToJsonElement(text).toString()
}.getOrDefault(text)

// JSON 树配色：独立于主题的固定色板（深浅色背景均可读）
private val JsonKeyColor = Color(0xFF6E9EE8)
private val JsonStringColor = Color(0xFF7BAE5F)
private val JsonNumberColor = Color(0xFFC98FD6)
private val JsonBoolColor = Color(0xFFD69A5C)
private val JsonNullColor = Color(0xFF9E9E9E)

/**
 * JSON 可视化树：节点按路径展开/折叠（默认展开前两层），键与值分色。
 * 非 lazy 递归渲染——默认折叠保证初始行数可控，调用方放在可滚动容器内。
 */
@Composable
fun JsonViewer(json: String, modifier: Modifier = Modifier) {
    val root = remember(json) { runCatching { jsonParser.parseToJsonElement(json) }.getOrNull() }
    Column(modifier.fillMaxWidth()) {
        when (root) {
            null -> Text(
                json,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFont,
            )
            else -> JsonNodeRow(keyName = null, value = root, depth = 0, path = "$")
        }
    }
}

@Composable
private fun JsonNodeRow(keyName: String?, value: JsonElement, depth: Int, path: String) {
    when (value) {
        is JsonObject -> BranchRow(keyName, value.keys.size, "{", "}", depth, path) {
            value.forEach { (k, v) -> JsonNodeRow(k, v, depth + 1, "$path.$k") }
        }
        is JsonArray -> BranchRow(keyName, value.size, "[", "]", depth, path) {
            value.forEachIndexed { i, v -> JsonNodeRow(null, v, depth + 1, "$path[$i]") }
        }
        else -> LeafRow(keyName, value as JsonPrimitive, depth)
    }
}

@Composable
private fun BranchRow(
    keyName: String?,
    size: Int,
    open: String,
    close: String,
    depth: Int,
    path: String,
    children: @Composable () -> Unit,
) {
    // 默认展开前两层，更深层收起；数组元素没有键名，箭头即行首标识
    var expanded by rememberSaveable(path) { mutableStateOf(depth < 2) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = (depth * 12).dp),
        ) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "折叠" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = annotatedKey(keyName) + annotatedRaw("$open$size$close"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) children()
    }
}

@Composable
private fun LeafRow(keyName: String?, value: JsonPrimitive, depth: Int) {
    val content = value.content
    val valueColor = when {
        content == "null" -> JsonNullColor
        content == "true" || content == "false" -> JsonBoolColor
        value.isString -> JsonStringColor
        else -> JsonNumberColor
    }
    Text(
        text = annotatedKey(keyName) + annotatedValue(content, valueColor, value.isString),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = MonoFont,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 12 + 14).dp),
    )
}

/** 键名部分：null（数组元素）返回空串 */
private fun annotatedKey(keyName: String?): AnnotatedString =
    if (keyName == null) {
        AnnotatedString("")
    } else {
        buildAnnotatedString {
            withStyle(SpanStyle(color = JsonKeyColor)) { append("\"$keyName\": ") }
        }
    }

private fun annotatedValue(content: String, color: Color, isString: Boolean): AnnotatedString =
    buildAnnotatedString {
        withStyle(
            SpanStyle(
                color = color,
                fontStyle = if (content == "null") FontStyle.Italic else null,
            )
        ) {
            append(if (isString) "\"$content\"" else content)
        }
    }

private fun annotatedRaw(text: String): AnnotatedString = AnnotatedString(text)
