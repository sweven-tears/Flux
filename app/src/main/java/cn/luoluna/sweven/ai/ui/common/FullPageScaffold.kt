package cn.luoluna.sweven.ai.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 全屏页面统一模板：标准 Scaffold + TopAppBar（ArrowBack 返回）。
 * 供自由复制 / 请求详情 / 内置浏览器等由弹层改造而来的独立页面共用；
 * insets 全部交给 M3 默认行为（TopAppBar 吃状态栏、Scaffold 吃导航栏），不再手动处理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPageScaffold(
    onBack: () -> Unit,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = actions,
            )
        },
    ) { padding ->
        content(padding)
    }
}

/** 纯文本标题的便捷重载 */
@Composable
fun FullPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    FullPageScaffold(
        onBack = onBack,
        title = { Text(title) },
        modifier = modifier,
        actions = actions,
        content = content,
    )
}
