package cn.luoluna.sweven.ai.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.ui.common.ModelBadgesRow
import cn.luoluna.sweven.ai.ui.common.paramsSummary

private enum class InputFilter(val label: String) {
    ALL("全部"), TEXT("文本"), IMAGE("图像"), VIDEO("视频");

    fun matches(m: ModelEntity): Boolean = when (this) {
        ALL -> true
        TEXT -> m.inputText
        IMAGE -> m.inputImage
        VIDEO -> m.inputVideo
    }
}

private data class PickerRow(val item: PickerModel, val showHeader: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    onDismiss: () -> Unit,
    models: List<PickerModel>,
    selectedProviderId: Long?,
    selectedModelName: String?,
    onSelect: (PickerModel) -> Unit,
    onGoProviders: () -> Unit,
    smartAiAvailable: Boolean = false,
    smartAiSelected: Boolean = false,
    onSelectSmartAi: () -> Unit = {},
) {
    var filter by remember { mutableStateOf(InputFilter.ALL) }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    // 厂商 chip：按传入顺序去重（列表已按厂商别名排序）
    val providerAliases = remember(models) {
        buildList {
            models.forEach { m -> if (m.provider.alias !in this) add(m.provider.alias) }
        }
    }
    val filtered = models.filter {
        filter.matches(it.model) && (providerFilter == null || it.provider.alias == providerFilter)
    }
    val rows = remember(filtered) {
        buildList {
            var lastAlias: String? = null
            filtered.forEach { m ->
                add(PickerRow(m, m.provider.alias != lastAlias))
                lastAlias = m.provider.alias
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("选择模型", style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                InputFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label) },
                    )
                }
            }
            if (providerAliases.size > 1) {
                // 厂商快速定位：单选过滤，点「全部」恢复完整列表
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                ) {
                    FilterChip(
                        selected = providerFilter == null,
                        onClick = { providerFilter = null },
                        label = { Text("全部厂商") },
                    )
                    providerAliases.forEach { alias ->
                        FilterChip(
                            selected = providerFilter == alias,
                            onClick = { providerFilter = if (providerFilter == alias) null else alias },
                            label = { Text(alias, maxLines = 1) },
                        )
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "暂无可用模型",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "先到「厂商」页添加厂商并选择模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                TextButton(onClick = { onDismiss(); onGoProviders() }) { Text("去添加") }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                // 智能路由聚合模型，固定置顶
                if (smartAiAvailable) {
                    item(key = "smart_ai") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSmartAi() }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            RadioButton(selected = smartAiSelected, onClick = onSelectSmartAi)
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text("smartAi", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "智能路由 · 失败自动切换候选",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
                items(rows, key = { it.item.model.id }) { row ->
                    if (row.showHeader) {
                        if (rows.indexOf(row) != 0) HorizontalDivider(Modifier.padding(top = 8.dp))
                        Text(
                            row.item.provider.alias,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(row.item) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = row.item.provider.id == selectedProviderId &&
                                row.item.model.name == selectedModelName,
                            onClick = { onSelect(row.item) },
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .widthIn(max = 320.dp)
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                row.item.displayId,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                            if (row.item.model.alias?.isNotBlank() == true) {
                                Text(
                                    row.item.aggregateId,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            ModelBadgesRow(row.item.model, Modifier.padding(top = 2.dp))
                            paramsSummary(row.item.model)?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
