package cn.luoluna.sweven.ai.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.luoluna.sweven.ai.App
import cn.luoluna.sweven.ai.tools.ToolRegistry
import cn.luoluna.sweven.ai.ui.common.FullPageScaffold
import cn.luoluna.sweven.ai.ui.common.InfoIconWithPopup
import cn.luoluna.sweven.ai.ui.common.SectionCard
import kotlinx.serialization.encodeToString
import cn.luoluna.sweven.ai.net.ChatJson

/**
 * 工具中心：管理本地工具（开关即全局默认，新建会话默认勾选）。
 * 每个工具带问号即时说明弹层；后续 MCP 服务器列表也进本页。
 */
@Composable
fun ToolsCenterScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val defaultIdsJson by app.settings.defaultToolIds.collectAsStateWithLifecycle()
    val enabled: Set<String> = defaultIdsJson
        ?.let { raw -> runCatching { ChatJson.json.decodeFromString<List<String>>(raw).toSet() }.getOrNull() }
        ?: emptySet()
    val tools = ToolRegistry.all()

    fun setEnabled(name: String, on: Boolean) {
        val next = if (on) enabled + name else enabled - name
        app.settings.setDefaultToolIds(ChatJson.json.encodeToString(next.sorted()))
    }

    FullPageScaffold(title = "工具中心", onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "本地工具") {
                Text(
                    "开关即全局默认：新建会话默认勾选这些工具；单个会话可在聊天输入栏的扳手按钮里单独调整。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (tools.isEmpty()) {
                    Text(
                        "暂无已注册工具",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                tools.forEach { tool ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tool.displayName, style = MaterialTheme.typography.bodyLarge)
                                InfoIconWithPopup(
                                    title = tool.displayName,
                                    text = buildString {
                                        append(tool.description)
                                        append("\n\n函数名：")
                                        append(tool.name)
                                        if (tool.needsApproval) append("\n该工具涉及隐私或设备状态变更，建议仅在需要时开启")
                                    },
                                )
                            }
                            Text(
                                tool.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = tool.name in enabled,
                            onCheckedChange = { setEnabled(tool.name, it) },
                        )
                    }
                }
            }

            SectionCard(title = "说明") {
                Text(
                    buildString {
                        append("1. 工具调用仅对支持「工具调用」能力的模型生效（模型编辑器里勾选），且厂商协议为 OpenAI 兼容。\n")
                        append("2. 勾选工具后，模型会在需要时自动调用，结果以「工具」消息显示在对话里。\n")
                        append("3. JS 执行器为沙箱执行（无网络、无文件、无 DOM），单次执行最长 10 秒。")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
