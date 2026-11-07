package cn.luoluna.sweven.ai.ui.chat

import cn.luoluna.sweven.ai.data.db.ref

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
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

    /** 路由分组的归属判定：按分组对外声明的能力（启用成员交集），与分组卡片上那行摘要同口径 */
    fun matches(cap: SmartAggregateCapability): Boolean = when (this) {
        ALL -> true
        TEXT -> cap.inputText || cap.outputText
        IMAGE -> cap.inputImage || cap.outputImage
        VIDEO -> cap.inputVideo || cap.outputVideo
    }
}

private data class PickerRow(val item: PickerModel, val showHeader: Boolean)

/** 选择器里的路由分组：随行附上对外声明能力（启用成员交集），类型筛选据此判断它属于哪一档 */
data class PickerRouteGroup(
    val group: SmartRouteGroupEntity,
    val capability: SmartAggregateCapability,
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
    /** 路由分组（flux-xxx）：列在 flux-auto 之后，组内成员自动切换；筛选了厂商时隐藏，类型筛选按声明能力过滤 */
    routeGroups: List<PickerRouteGroup> = emptyList(),
    selectedGroupKey: String? = null,
    onSelectGroup: (SmartRouteGroupEntity) -> Unit = {},
) {
    // 当前模型所属厂商：打开选择器时直接按它筛选（芯片同时处于选中态）。
    // 只把芯片滚到最左端不够——列表里其它厂商的模型仍然混在一起，等于没选中厂商
    val currentAlias = remember(models, selectedProviderId, selectedModelName) {
        models.firstOrNull {
            it.provider.id == selectedProviderId && it.model.ref == selectedModelName
        }?.provider?.alias
    }
    var typeFilter by remember { mutableStateOf(TypeFilter.ALL) }
    var providerFilter by remember { mutableStateOf(currentAlias) }
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
    val filtered = models.filter { m ->
        typeFilter.matches(m.model) && (providerFilter == null || m.provider.alias == providerFilter)
    }
    // 分组：类型筛选按「成员交集」声明能力过滤（无能力项时不占位），筛厂商时整个分组区隐藏
    val visibleGroups = routeGroups.filter { typeFilter.matches(it.capability) }
    val showRouteGroups = visibleGroups.isNotEmpty() && providerFilter == null
    val rows = remember(filtered) {
        buildList {
            var lastAlias: String? = null
            filtered.forEach { m ->
                add(PickerRow(m, m.provider.alias != lastAlias))
                lastAlias = m.provider.alias
            }
        }
    }
    // 打开时把「当前已选模型」顶到列表最上面；它前面还有聚合路由与路由分组条目，需要加偏移
    val selectedRowIndex = remember(rows, selectedProviderId, selectedModelName) {
        rows.indexOfFirst {
            it.item.provider.id == selectedProviderId && it.item.model.ref == selectedModelName
        }
    }
    val headerCount = (if (smartAiAvailable) 1 else 0) + (if (showRouteGroups) visibleGroups.size else 0)
    val listState = rememberLazyListState()
    // 只定位一次：之后用户切换类型/厂商筛选时不再强行跳回（避免抢滚动）
    var located by remember { mutableStateOf(false) }
    LaunchedEffect(selectedRowIndex, headerCount) {
        if (!located && selectedRowIndex >= 0) {
            listState.scrollToItem(headerCount + selectedRowIndex)
            located = true
        }
    }

    // 点击 tab 直接整屏展开（skipPartiallyExpanded），列表才能内部滚动、把当前模型顶到顶部
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
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
                // 厂商快速定位：单选过滤，点「全部」恢复完整列表；
                // 打开时把当前模型所属厂商的 chip 顶到最左侧，省得横向翻找
                val selectedChipIndex = providerAliases.indexOf(currentAlias)
                val chipState = rememberLazyListState(
                    initialFirstVisibleItemIndex = (selectedChipIndex + 1).coerceAtLeast(0),
                )
                LazyRow(
                    state = chipState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    item(key = "all_providers") {
                        FilterChip(
                            selected = providerFilter == null,
                            onClick = { providerFilter = null },
                            label = { Text("全部厂商") },
                        )
                    }
                    itemsIndexed(providerAliases, key = { _, alias -> alias }) { _, alias ->
                        FilterChip(
                            selected = providerFilter == alias,
                            onClick = { providerFilter = if (providerFilter == alias) null else alias },
                            label = { Text(alias, maxLines = 1) },
                        )
                    }
                }
            }
        }
        if (rows.isEmpty() && !showRouteGroups) {
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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 24.dp),
            ) {
                // 聚合路由，固定置顶（不参与类型筛选：它的能力是全模型并集，几乎总能匹配）
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
                                Text(SmartAi.MODEL_NAME, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    buildList {
                                        add("全部模型聚合 · 按请求择优选发")
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
                if (showRouteGroups) visibleGroups.forEach { entry ->
                    val group = entry.group
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
                                row.item.model.ref == selectedModelName,
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
            }
        }
        }
    }
}
