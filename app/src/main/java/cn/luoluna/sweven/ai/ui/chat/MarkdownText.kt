package cn.luoluna.sweven.ai.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

private val INLINE_PATTERN = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")
private val HEADER_PATTERN = Regex("""^(#{1,4})\s+(.*)$""")
private val BULLET_PATTERN = Regex("""^[-*+]\s+(.*)$""")
private val NUMBERED_PATTERN = Regex("""^(\d+)[.)]\s+(.*)$""")

/** 轻量 Markdown 渲染：标题/加粗/行内代码/代码块/列表 */
fun renderMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var inCodeBlock = false
    text.lines().forEachIndexed { index, raw ->
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
                when {
                    header != null -> {
                        val size = when (header.groupValues[1].length) {
                            1 -> 19.sp
                            2 -> 17.sp
                            else -> 15.5.sp
                        }
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size)) {
                            inline(header.groupValues[2])
                        }
                    }
                    bullet != null -> {
                        append("•  ")
                        inline(bullet.groupValues[1])
                    }
                    numbered != null -> {
                        append("${numbered.groupValues[1]}. ")
                        inline(numbered.groupValues[2])
                    }
                    else -> inline(line)
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.inline(line: String) {
    var last = 0
    for (match in INLINE_PATTERN.findAll(line)) {
        append(line.substring(last, match.range.first))
        val bold = match.groupValues[1]
        val code = match.groupValues[2]
        if (bold.isNotEmpty()) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        } else if (code.isNotEmpty()) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
        }
        last = match.range.last + 1
    }
    if (last < line.length) append(line.substring(last))
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(text) { renderMarkdown(text) }
    Text(
        annotated,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
    )
}
