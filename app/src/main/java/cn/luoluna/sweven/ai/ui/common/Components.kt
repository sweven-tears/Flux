package cn.luoluna.sweven.ai.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cn.luoluna.sweven.ai.data.db.ModelEntity

object ModalityLabel {
    const val TEXT = "文本"
    const val IMAGE = "图像"
    const val VIDEO = "视频"
}

fun inputTags(m: ModelEntity): List<String> = buildList {
    if (m.inputText) add(ModalityLabel.TEXT)
    if (m.inputImage) add(ModalityLabel.IMAGE)
    if (m.inputVideo) add(ModalityLabel.VIDEO)
}

fun outputTags(m: ModelEntity): List<String> = buildList {
    if (m.outputText) add(ModalityLabel.TEXT)
    if (m.outputImage) add(ModalityLabel.IMAGE)
    if (m.outputVideo) add(ModalityLabel.VIDEO)
}

/** 高级参数摘要，如 "温度 0.7 · top_p 0.9 · max_tokens 4096"，无配置返回 null */
fun paramsSummary(m: ModelEntity): String? = buildList {
    m.temperature?.let { add("温度 $it") }
    m.topP?.let { add("top_p $it") }
    m.maxTokens?.let { add("max_tokens $it") }
    m.presencePenalty?.let { add("presence $it") }
    m.frequencyPenalty?.let { add("frequency $it") }
}.takeIf { it.isNotEmpty() }?.joinToString(" · ")

@Composable
fun ModalityTag(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = color,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 模型的输入/输出类型徽标行 */
@Composable
fun ModelBadgesRow(m: ModelEntity, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("入", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        inputTags(m).forEach { ModalityTag(it) }
        Text("出", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
        outputTags(m).forEach { ModalityTag(it, color = MaterialTheme.colorScheme.tertiaryContainer) }
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                actions()
            }
            content()
        }
    }
}

fun maskKey(key: String): String = when {
    key.length <= 8 -> key.take(2) + "****"
    else -> key.take(6) + "****" + key.takeLast(4)
}

val MonoFont = FontFamily.Monospace
