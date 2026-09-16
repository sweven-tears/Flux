package cn.luoluna.sweven.ai.ui.chat

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.luoluna.sweven.ai.ui.common.InAppBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val INLINE_PATTERN = Regex("""\*\*(.+?)\*\*|~~(.+?)~~|`([^`]+)`|\[([^\]]+)\]\(([^)\s]+)\)""")
private val HEADER_PATTERN = Regex("""^(#{1,4})\s+(.*)$""")
private val BULLET_PATTERN = Regex("""^[-*+]\s+(.*)$""")
private val NUMBERED_PATTERN = Regex("""^(\d+)[.)]\s+(.*)$""")
private val QUOTE_PATTERN = Regex("""^>\s?(.*)$""")

private val IGNORE_CASE = setOf<RegexOption>(RegexOption.IGNORE_CASE)
private val BR_TAG = Regex("""<br\s*/?>""", IGNORE_CASE)
private val LI_OPEN = Regex("""<li[^>]*>""", IGNORE_CASE)
private val BLOCK_CLOSE = Regex("""</(p|div|li|ul|ol|tr|h[1-6])\s*>""", IGNORE_CASE)
private val HEADING_OPEN = Regex("""<h([1-6])[^>]*>""", IGNORE_CASE)
private val B_OPEN = Regex("""<(b|strong)[^>]*>""", IGNORE_CASE)
private val B_CLOSE = Regex("""</(b|strong)\s*>""", IGNORE_CASE)
private val I_OPEN = Regex("""<(i|em|cite)[^>]*>""", IGNORE_CASE)
private val I_CLOSE = Regex("""</(i|em|cite)\s*>""", IGNORE_CASE)
private val S_TAG = Regex("""</?(del|s|strike)[^>]*>""", IGNORE_CASE)
private val CODE_TAG = Regex("""</?code[^>]*>""", IGNORE_CASE)
private val ANY_TAG = Regex("""<[^>]+>""")

private val HEAD_TAG = Regex("""<head[^>]*>""", IGNORE_CASE)
private val HTML_TAG = Regex("""<html[^>]*>""", IGNORE_CASE)
private val VIEWPORT_PROBE = Regex("""<meta[^>]+name=(["'])viewport\1""", IGNORE_CASE)
private val VIEWPORT_META =
    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
private val PREVIEW_SHELL_HEAD =
    "<!DOCTYPE html><html><head><meta charset=\"utf-8\">$VIEWPORT_META" +
        "<style>html,body{margin:0;padding:0}svg{max-width:100%;height:auto}</style></head><body>"

/**
 * HTML → Markdown 兼容：模型偶尔用 HTML 标签回复，这里转成等价 Markdown，
 * 无法识别的标签直接剔除，HTML 实体做反转义。
 */
fun normalizeHtmlLike(text: String): String =
    text
        .replace(BR_TAG, "\n")
        .replace(LI_OPEN, "\n- ")
        .replace(BLOCK_CLOSE, "\n")
        .replace(HEADING_OPEN) { m -> "\n" + "#".repeat(m.groupValues[1].toInt()) + " " }
        .replace(B_OPEN, "**")
        .replace(B_CLOSE, "**")
        .replace(I_OPEN, "*")
        .replace(I_CLOSE, "*")
        .replace(S_TAG, "~~")
        .replace(CODE_TAG, "`")
        .replace(ANY_TAG, "")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")

/** 轻量 Markdown 渲染：标题/加粗/删除线/行内代码/代码块/列表/引用/链接，兼容 HTML 输入 */
fun renderMarkdown(text: String, onOpenUrl: ((String) -> Unit)? = null): AnnotatedString {
    val source = normalizeHtmlLike(text)
    return buildAnnotatedString {
        var inCodeBlock = false
        source.lines().forEachIndexed { index, raw ->
            if (index > 0) append('\n')
            val line = raw.trimEnd()
            when {
                line.trim().startsWith("```") -> {
                    inCodeBlock = !inCodeBlock
                }
                inCodeBlock -> {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(line) }
                }
                else -> {
                    val header = HEADER_PATTERN.find(line)
                    val bullet = BULLET_PATTERN.find(line)
                    val numbered = NUMBERED_PATTERN.find(line)
                    val quote = QUOTE_PATTERN.find(line)
                    when {
                        header != null -> {
                            val size = when (header.groupValues[1].length) {
                                1 -> 19.sp
                                2 -> 17.sp
                                else -> 15.5.sp
                            }
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size)) {
                                inline(header.groupValues[2], onOpenUrl)
                            }
                        }
                        bullet != null -> {
                            append("•  ")
                            inline(bullet.groupValues[1], onOpenUrl)
                        }
                        numbered != null -> {
                            append("${numbered.groupValues[1]}. ")
                            inline(numbered.groupValues[2], onOpenUrl)
                        }
                        quote != null -> {
                            withStyle(SpanStyle(color = Color.Gray)) {
                                append("▎")
                                inline(quote.groupValues[1], onOpenUrl)
                            }
                        }
                        else -> inline(line, onOpenUrl)
                    }
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.inline(line: String, onOpenUrl: ((String) -> Unit)?) {
    var last = 0
    for (match in INLINE_PATTERN.findAll(line)) {
        append(line.substring(last, match.range.first))
        val bold = match.groupValues[1]
        val strike = match.groupValues[2]
        val code = match.groupValues[3]
        val linkText = match.groupValues[4]
        val linkUrl = match.groupValues[5]
        when {
            bold.isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            strike.isNotEmpty() ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(strike) }
            code.isNotEmpty() ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            linkText.isNotEmpty() && linkUrl.isNotEmpty() -> {
                val annotation = LinkAnnotation.Url(
                    linkUrl,
                    TextLinkStyles(
                        style = SpanStyle(
                            color = Color(0xFF3B82F6),
                            textDecoration = TextDecoration.Underline,
                        )
                    ),
                ) { onOpenUrl?.invoke(linkUrl) }
                withLink(annotation) { append(linkText) }
            }
        }
        last = match.range.last + 1
    }
    if (last < line.length) append(line.substring(last))
}

/** 消息分块：普通文本 / ``` 围栏代码块（代码内容不做 HTML 清洗，原样保留供预览）/ Markdown 表格 */
private sealed interface MdBlock {
    data class Text(val content: String) : MdBlock
    data class Code(val lang: String?, val content: String, val closed: Boolean) : MdBlock

    /** Markdown 表格：表头 + 数据行 + 各列对齐（按分隔行 : 标记） */
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val aligns: List<TableAlign>,
    ) : MdBlock
}

/** 表格列对齐 */
private enum class TableAlign { START, CENTER, END }

/** 分隔行：| --- | :---: | ---: | 这类（每列至少 3 个 -，: 标记对齐） */
private val TABLE_SEP_ROW = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""")

private const val ESCAPED_PIPE = "\u0000"

/** 表格行按未转义 | 切分，\| 还原为 |，并去掉外层管道产生的首尾空列 */
private fun splitTableRow(line: String): List<String> {
    val replaced = line.replace("\\|", ESCAPED_PIPE)
    val cells = replaced.split('|').map { it.trim().replace(ESCAPED_PIPE, "|") }
    var list = cells
    if (replaced.trimStart().startsWith("|") && list.size > 1) list = list.drop(1)
    if (replaced.trimEnd().endsWith("|") && list.isNotEmpty()) list = list.dropLast(1)
    return list
}

private fun parseAligns(separatorLine: String): List<TableAlign> =
    splitTableRow(separatorLine).map { cell ->
        when {
            cell.startsWith(":") && cell.endsWith(":") -> TableAlign.CENTER
            cell.endsWith(":") -> TableAlign.END
            else -> TableAlign.START
        }
    }

private fun parseBlocks(text: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val textLines = ArrayList<String>()
    val codeLines = ArrayList<String>()
    var inCode = false
    var codeLang: String? = null

    fun flushText() {
        if (textLines.isNotEmpty()) {
            blocks += MdBlock.Text(textLines.joinToString("\n"))
            textLines.clear()
        }
    }

    fun flushCode(closed: Boolean) {
        if (codeLines.isNotEmpty() || !closed) {
            blocks += MdBlock.Code(codeLang, codeLines.joinToString("\n"), closed)
            codeLines.clear()
            codeLang = null
        }
    }

    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val trimmed = raw.trimStart()
        when {
            !inCode && trimmed.startsWith("```") -> {
                flushText()
                inCode = true
                codeLang = trimmed.removePrefix("```").trim()
                    .takeIf { it.isNotEmpty() }?.substringBefore(' ')
                i++
            }
            inCode && trimmed.startsWith("```") -> {
                inCode = false
                flushCode(closed = true)
                i++
            }
            inCode -> {
                codeLines += raw
                i++
            }
            // 表格：本行含 | 且下一行是分隔行，此后连续含 | 的行都算数据行
            trimmed.contains('|') && i + 1 < lines.size && TABLE_SEP_ROW.matches(lines[i + 1]) -> {
                val headers = splitTableRow(raw)
                var j = i + 2
                val rows = ArrayList<List<String>>()
                while (j < lines.size) {
                    val rowTrimmed = lines[j].trimStart()
                    if (rowTrimmed.isNotBlank() && rowTrimmed.contains('|')) {
                        rows.add(splitTableRow(lines[j]))
                        j++
                    } else break
                }
                flushText()
                blocks += MdBlock.Table(headers, rows, parseAligns(lines[i + 1]))
                i = j
            }
            else -> {
                textLines += raw
                i++
            }
        }
    }
    flushText()
    // 流式输出时围栏可能尚未闭合：仍按代码块展示，但不提供预览入口
    if (inCode) flushCode(closed = false)
    return blocks
}

private val WEB_PREVIEW_LANGS = setOf("html", "htm", "svg")

/** ```html / ```svg 等可直接在 WebView 渲染的代码块，提供一键预览入口 */
fun isWebPreviewable(lang: String?, code: String): Boolean {
    val l = lang?.trim()?.lowercase()
    if (l != null && l in WEB_PREVIEW_LANGS) return true
    val t = code.trimStart()
    return t.startsWith("<!doctype html", true) ||
        t.startsWith("<html", true) ||
        t.startsWith("<svg", true)
}

private fun ensureViewport(html: String): String {
    if (VIEWPORT_PROBE.containsMatchIn(html)) return html
    HEAD_TAG.find(html)?.let {
        return html.replaceRange(it.range.last + 1, it.range.last + 1, VIEWPORT_META)
    }
    HTML_TAG.find(html)?.let {
        return html.replaceRange(it.range.last + 1, it.range.last + 1, VIEWPORT_META)
    }
    return "$VIEWPORT_META\n$html"
}

/** 代码块 → 可在 WebView 直接渲染的 HTML：SVG 套壳，HTML 补 viewport */
fun buildPreviewHtml(lang: String?, code: String): String {
    val trimmed = code.trim()
    val l = lang?.trim()?.lowercase()
    val isHtml = (l == "html" || l == "htm") ||
        trimmed.startsWith("<!doctype html", true) ||
        trimmed.startsWith("<html", true)
    return if (isHtml) ensureViewport(trimmed) else "$PREVIEW_SHELL_HEAD$trimmed</body></html>"
}

/** 点击入口：内置浏览器打开网页 / 内置 WebView 渲染代码块预览 */
private sealed interface Preview {
    data class Web(val url: String) : Preview
    data class Html(val html: String) : Preview
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<Preview?>(null) }
    val blocks = remember(text) { parseBlocks(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Text -> MarkdownParagraph(block.content, style) { raw ->
                    val url = normalizeUrl(raw)
                    if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                        preview = Preview.Web(url)
                    } else {
                        // mailto:、tel: 等非网页链接交给系统处理
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                }
                is MdBlock.Code -> CodeBlockView(
                    code = block,
                    onPreview = {
                        preview = Preview.Html(buildPreviewHtml(block.lang, block.content))
                    },
                )
                is MdBlock.Table -> MdTableView(block)
            }
        }
    }

    when (val p = preview) {
        is Preview.Web -> InAppBrowser(url = p.url, onDismiss = { preview = null })
        is Preview.Html -> InAppBrowser(content = p.html, onDismiss = { preview = null })
        null -> Unit
    }
}

/** 超过该长度时把 Markdown 解析挪到后台线程，避免长消息首次渲染卡住主线程 */
private const val ASYNC_PARSE_THRESHOLD = 2_000

@Composable
private fun MarkdownParagraph(content: String, style: TextStyle, onOpenUrl: (String) -> Unit) {
    if (content.length <= ASYNC_PARSE_THRESHOLD) {
        val annotated = remember(content) { renderMarkdown(content, onOpenUrl) }
        Text(annotated, style = style)
    } else {
        val annotated by produceState<AnnotatedString?>(null, content) {
            value = withContext(Dispatchers.Default) { renderMarkdown(content, onOpenUrl) }
        }
        Text(
            // 解析完成前先按原文展示，避免气泡高度跳变
            annotated ?: AnnotatedString(content),
            style = style,
        )
    }
}

/** Markdown 表格视图：列宽均分可用宽度（最小 88dp，超出横向滚动），表头加粗带底色，行间分隔线 */
@Composable
private fun MdTableView(table: MdBlock.Table) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnCount = table.headers.size.coerceAtLeast(1)
        val minCell = 88.dp
        val share = (maxWidth - 1.dp) / columnCount
        val cellWidth = if (share > minCell) share else minCell
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                table.headers.forEachIndexed { col, header ->
                    TableCell(
                        text = header,
                        align = table.aligns.getOrNull(col) ?: TableAlign.START,
                        header = true,
                        width = cellWidth,
                    )
                }
            }
            table.rows.forEach { row ->
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                Row(Modifier.fillMaxWidth()) {
                    repeat(columnCount) { col ->
                        TableCell(
                            text = row.getOrNull(col).orEmpty(),
                            align = table.aligns.getOrNull(col) ?: TableAlign.START,
                            header = false,
                            width = cellWidth,
                        )
                    }
                }
            }
        }
    }
}

/** 单元格：复用行内 Markdown 渲染（加粗/删除线/行内代码/链接），按列对齐 */
@Composable
private fun RowScope.TableCell(text: String, align: TableAlign, header: Boolean, width: Dp) {
    val annotated = remember(text) { renderMarkdown(text) }
    Box(
        Modifier
            .width(width)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = when (align) {
            TableAlign.CENTER -> Alignment.Center
            TableAlign.END -> Alignment.CenterEnd
            TableAlign.START -> Alignment.CenterStart
        },
    ) {
        Text(
            annotated,
            style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (header) FontWeight.Bold else null,
        )
    }
}

@Composable
private fun CodeBlockView(code: MdBlock.Code, onPreview: () -> Unit) {
    val context = LocalContext.current
    val previewable = code.closed && isWebPreviewable(code.lang, code.content)
    var collapsed by rememberSaveable { mutableStateOf(false) }
    val lineCount = remember(code.content) { code.content.count { it == '\n' } + 1 }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { collapsed = !collapsed }
                    .padding(vertical = 2.dp),
            ) {
                Icon(
                    if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = if (collapsed) "展开代码" else "折叠代码",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    code.lang ?: "代码",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (collapsed) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "$lineCount 行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                // 快速复制整个代码块
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            copyMessageText(context, code.content)
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "复制代码",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "复制",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (previewable) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onPreview)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "在 WebView 中预览",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "预览",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (!collapsed) {
                Text(
                    code.content,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** 补全省略协议的裸域名；带其他 scheme 的链接原样返回 */
private fun normalizeUrl(raw: String): String {
    val url = raw.trim()
    return when {
        url.startsWith("http://", true) || url.startsWith("https://", true) -> url
        url.contains("://") -> url
        else -> "https://$url"
    }
}

private const val MESSAGE_HTML_STYLE =
    "<style>body{font-family:-apple-system,'Segoe UI',Roboto,sans-serif;padding:12px;" +
        "line-height:1.6;-webkit-text-size-adjust:100%}" +
        "h1,h2,h3,h4{margin:12px 0 6px}p{margin:6px 0}" +
        "pre{background:#f4f4f5;padding:10px;border-radius:8px;overflow-x:auto}" +
        "code{font-family:Menlo,Consolas,monospace;font-size:0.92em}" +
        "blockquote{border-left:3px solid #d4d4d8;margin:6px 0;padding-left:10px;color:#52525b}" +
        "a{color:#3b82f6}img{max-width:100%}ul,ol{margin:6px 0;padding-left:22px}" +
        "table{border-collapse:collapse;margin:6px 0}" +
        "th,td{border:1px solid #d4d4d8;padding:4px 10px}" +
        "th{background:#f4f4f5;text-align:left}</style>"

/** HTML 转义（先转义再做 Markdown 行内替换，避免注入） */
private fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/** 行内 Markdown → HTML（加粗/删除线/行内代码/链接），输入需已做 HTML 转义 */
private fun inlineToHtml(escaped: String): String {
    val out = StringBuilder()
    var last = 0
    for (match in INLINE_PATTERN.findAll(escaped)) {
        out.append(escaped.substring(last, match.range.first))
        val bold = match.groupValues[1]
        val strike = match.groupValues[2]
        val code = match.groupValues[3]
        val linkText = match.groupValues[4]
        val linkUrl = match.groupValues[5]
        out.append(
            when {
                bold.isNotEmpty() -> "<strong>$bold</strong>"
                strike.isNotEmpty() -> "<del>$strike</del>"
                code.isNotEmpty() -> "<code>$code</code>"
                linkText.isNotEmpty() && linkUrl.isNotEmpty() -> "<a href=\"$linkUrl\">$linkText</a>"
                else -> ""
            }
        )
        last = match.range.last + 1
    }
    out.append(escaped.substring(last))
    return out.toString()
}

/**
 * 消息 Markdown → 完整 HTML（供「网页视图渲染」用 WebView 查看）。
 * 支持标题/无序与有序列表/引用/围栏代码块/表格/行内样式与链接，与 renderMarkdown 的规则一致。
 */
fun buildMessageHtml(text: String): String {
    val source = normalizeHtmlLike(text)
    val sb = StringBuilder()
    var listTag: String? = null
    fun closeList() {
        listTag?.let { sb.append("</").append(it).append(">\n") }
        listTag = null
    }
    var inCode = false
    val lines = source.lines()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            if (inCode) {
                sb.append("</code></pre>\n")
                inCode = false
            } else {
                closeList()
                val lang = trimmed.removePrefix("```").trim().substringBefore(' ')
                if (lang.isNotEmpty()) {
                    sb.append("<pre><code class=\"lang-").append(escapeHtml(lang)).append("\">\n")
                } else {
                    sb.append("<pre><code>\n")
                }
                inCode = true
            }
            i++
            continue
        }
        if (inCode) {
            sb.append(escapeHtml(raw)).append('\n')
            i++
            continue
        }
        // 表格：与 parseBlocks 同口径识别，输出 <table>（th 加粗带底色由样式表控制）
        if (trimmed.contains('|') && i + 1 < lines.size && TABLE_SEP_ROW.matches(lines[i + 1])) {
            val headers = splitTableRow(line)
            var j = i + 2
            val rows = ArrayList<List<String>>()
            while (j < lines.size) {
                val rowTrimmed = lines[j].trimStart()
                if (rowTrimmed.isNotBlank() && rowTrimmed.contains('|')) {
                    rows.add(splitTableRow(lines[j]))
                    j++
                } else break
            }
            closeList()
            sb.append("<table>\n<thead><tr>")
            headers.forEach { header ->
                sb.append("<th>").append(inlineToHtml(escapeHtml(header))).append("</th>")
            }
            sb.append("</tr></thead>\n<tbody>")
            rows.forEach { row ->
                sb.append("<tr>")
                repeat(headers.size) { col ->
                    sb.append("<td>").append(inlineToHtml(escapeHtml(row.getOrNull(col).orEmpty()))).append("</td>")
                }
                sb.append("</tr>\n")
            }
            sb.append("</tbody></table>\n")
            i = j
            continue
        }
        val header = HEADER_PATTERN.find(line)
        val bullet = BULLET_PATTERN.find(line)
        val numbered = NUMBERED_PATTERN.find(line)
        val quote = QUOTE_PATTERN.find(line)
        when {
            header != null -> {
                closeList()
                val level = header.groupValues[1].length
                sb.append("<h$level>")
                    .append(inlineToHtml(escapeHtml(header.groupValues[2])))
                    .append("</h$level>\n")
            }
            bullet != null -> {
                if (listTag != "ul") {
                    closeList()
                    sb.append("<ul>\n")
                    listTag = "ul"
                }
                sb.append("<li>").append(inlineToHtml(escapeHtml(bullet.groupValues[1]))).append("</li>\n")
            }
            numbered != null -> {
                if (listTag != "ol") {
                    closeList()
                    sb.append("<ol>\n")
                    listTag = "ol"
                }
                sb.append("<li>").append(inlineToHtml(escapeHtml(numbered.groupValues[2]))).append("</li>\n")
            }
            quote != null -> {
                closeList()
                sb.append("<blockquote><p>")
                    .append(inlineToHtml(escapeHtml(quote.groupValues[1])))
                    .append("</p></blockquote>\n")
            }
            trimmed.isEmpty() -> closeList()
            else -> {
                closeList()
                sb.append("<p>").append(inlineToHtml(escapeHtml(line))).append("</p>\n")
            }
        }
        i++
    }
    if (inCode) sb.append("</code></pre>\n")
    closeList()
    return "$PREVIEW_SHELL_HEAD$MESSAGE_HTML_STYLE$sb</body></html>"
}
