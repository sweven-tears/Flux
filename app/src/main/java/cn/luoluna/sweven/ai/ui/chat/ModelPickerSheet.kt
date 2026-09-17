package cn.luoluna.sweven.ai.ui.chat

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.luoluna.sweven.ai.data.db.ModelEntity
import cn.luoluna.sweven.ai.data.db.SmartRouteGroupEntity
import cn.luoluna.sweven.ai.data.repo.SmartAggregateCapability
import cn.luoluna.sweven.ai.data.repo.SmartAi
import cn.luoluna.sweven.ai.ui.common.ModelBadgesRow
import cn.luoluna.sweven.ai.ui.common.paramsSummary

/** 模型类型筛选：输入或输出任一命中即算——能看图的、能产图的都归「图像」档 */
private enum class TypeFilter(val label: String) {
    ALL("全部"), TEXT("文本"), IMAGE("图像"), VIDEO("视频");

    fun matches(m: ModelEntity): Boolean = when (this) {
        ALL -> true
        TEXT -> m.inputText || m.outputText
        IMAGE -> m.inputImage || m.outputImage
        VIDEO -> m.inputVideo || m.outputVideo
    }
}

private data class PickerRow(val item: PickerModel, val showHeader: Boolean)

/** 搜索归一化：忽略大小写与分隔符，「gpt4o」也能命中「GPT-4o」 */
private fun searchKey(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

/** 单条模型的搜索命中串（厂商别名/显示 id/聚合 id/模型 id/模型别名） */
private fun haystack(m: PickerModel): String = searchKey(
    m.provider.alias + m.displayId + m.aggregateId + m.model.name + m.model.alias.orEmpty()
)

/** smartAi 聚合能力摘要：「文图→文图 · 支持工具/推理」 */
private fun smartAiCapabilitySummary(cap: SmartAggregateCapability?): String {
    if (cap == null) return ""
    fun mods(text: Boolean, image: Boolean, video: Boolean) = buildList {
        if (text) add("文")
        if (image) add("图")
        if (video) add("视")
    }.joinToString("").ifEmpty { "—" }
    val base = "${mods(cap.inputText, cap.inputImage, cap.inputVideo)}→" +
        mods(cap.outputText, cap.outputImage, cap.outputVideo)
    val extras = buildList {
        if (cap.tools) add("工具")
        if (cap.reasoning) add("推理")
    }
    return if (extras.isEmpty()) base else "$base · 支持${extras.joinToString("/")}"
}

/** 列表区最大高度：搜索框贴底，弹层不因模型多而一上来铺满整屏 */
private val LIST_MAX_HEIGHT = 360.dp

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
    smartAiCapability: SmartAggregateCapability? = null,
    /** 路由分组（flux-xxx）：列在 smartAi 之后，组内成员自动切换；筛选了厂商时隐藏 */
    routeGroups: List<SmartRouteGroupEntity> = emptyList(),
    selectedGroupKey: String? = null,
    onSelectGroup: (SmartRouteGroupEntity) -> Unit = {},
) {
    var typeFilter by remember { mutableStateOf(TypeFilter.ALL) }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // 厂商 chip 只列在当前类型筛选下仍有模型的厂商——筛出的结果里没有模型的厂商不出现
    val providerAliases = remember(models, typeFilter) {
        buildList {
            models.forEach { m ->
                if (typeFilter.matches(m.model) && m.provider.alias !in this) add(m.provider.alias)
            }
        }
    }
    // 切换类型后原选中的厂商已无模型：回到「全部厂商」，避免只剩空列表
    LaunchedEffect(providerAliases) {
        if (providerFilter != null && providerFilter !in providerAliases) providerFilter = null
    }
    val needle = searchKey(query)
    val filtered = models.filter { m ->
        typeFilter.matches(m.model) &&
            (providerFilter == null || m.provider.alias == providerFilter) &&
            (needle.isEmpty() || haystack(m).contains(needle))
    }
    // 路由分组与聚合路由不是普通模型：筛厂商时分组隐藏（只看该厂商的模型），搜索时按路由名过滤
    val visibleGroups = routeGroups.filter { needle.isEmpty() || searchKey(it.routeName).contains(needle) }
    val showRouteGroups = visibleGroups.isNotEmpty() && providerFilter == null
    val showSmartAi = smartAiAvailable && (needle.isEmpty() || searchKey(SmartAi.MODEL_NAME).contains(needle))
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
                TypeFilter.entries.forEach { f ->
                    FilterChip(
                        selected = typeFilter == f,
                        onClick = { typeFilter = f },
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
        if (models.isEmpty()) {
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
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT)) {
                // 聚合路由，固定置顶
                if (showSmartAi) {
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
                                Text(SmartAi.MODEL_NAME, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    buildList {
                                        add("智能路由")
                                        smartAiCapabilitySummary(smartAiCapability)
                                            .takeIf { it.isNotEmpty() }
                                            ?.let { add(it) }
                                        add("失败自动切换候选")
                                    }.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
                // 路由分组（flux-<groupKey>）：同族模型聚合，组内自动切换
                if (showRouteGroups) visibleGroups.forEach { group ->
                    item(key = "route_group_${group.id}") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectGroup(group) }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            RadioButton(
                                selected = group.groupKey == selectedGroupKey,
                                onClick = { onSelectGroup(group) },
                            )
                            Icon(
                                Icons.Filled.Hub,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(
                                    group.routeName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    modifier = Modifier.basicMarquee(),
                                )
                                Text(
                                    "路由分组 · 同族模型跨厂商自动切换",
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
                                    row.item.displayId + if (row.item.enabled) "" else "（已禁用）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    modifier = Modifier.basicMarquee(),
                                )
                            if (row.item.model.alias?.isNotBlank() == true) {
                                    Text(
                                        row.item.aggregateId,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.basicMarquee(),
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
                if (rows.isEmpty() && !showRouteGroups && !showSmartAi) {
                    item(key = "no_match") {
                        Text(
                            "没有匹配的模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 20.dp),
                        )
                    }
                }
            }
            // 搜索框贴底：列表在上方按内容高度收着，输字即时过滤（结果在当前类型/厂商筛选之上再收敛）
            HorizontalDivider()
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索模型 / 厂商") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
