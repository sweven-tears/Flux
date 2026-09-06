package cn.luoluna.sweven.ai.ui.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.data.db.ProviderEntity
import cn.luoluna.sweven.ai.ui.common.ModalityTag

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderListScreen(
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as App
    val providers by remember { app.database.providerDao().observeAll() }
        .collectAsStateWithLifecycle(emptyList())
    val models by remember { app.database.modelDao().observeAll() }
        .collectAsStateWithLifecycle(emptyList())

    val modelCount = models.groupingBy { it.providerId }.eachCount()
    val enabledCount = models.filter { it.enabled }.groupingBy { it.providerId }.eachCount()

    Scaffold(
        topBar = { TopAppBar(title = { Text("模型厂商") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "添加厂商")
            }
        },
    ) { padding ->
        if (providers.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "还没有模型厂商",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "添加预设厂商（填 API Key 即用）或自定义厂商，\n随后即可拉取模型列表并开始对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(providers, key = { it.id }) { p ->
                    ProviderCard(p, modelCount[p.id] ?: 0, enabledCount[p.id] ?: 0) {
                        onOpen(p.id)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderCard(
    p: ProviderEntity,
    count: Int,
    enabled: Int,
    onClick: () -> Unit,
) {
    Card(onClick = onClick) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.alias, style = MaterialTheme.typography.titleMedium)
                    if (p.isPreset) {
                        ModalityTag(
                            "预设",
                            modifier = Modifier.padding(start = 6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        )
                    }
                }
                Text(
                    p.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    "模型 $count 个（启用 $enabled）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TestStatusDot(ok = p.lastTestOk, latencyMs = p.lastTestLatencyMs)
        }
    }
}

@Composable
fun TestStatusDot(ok: Boolean?, latencyMs: Long?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(10.dp)
                .background(
                    color = when (ok) {
                        true -> androidx.compose.ui.graphics.Color(0xFF22C55E)
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = CircleShape,
                )
        )
        if (ok == true && latencyMs != null) {
            Text(
                "${latencyMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
