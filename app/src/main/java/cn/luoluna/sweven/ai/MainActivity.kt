package cn.luoluna.sweven.ai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import cn.luoluna.sweven.ai.server.LanService
import cn.luoluna.sweven.ai.server.ServerState
import cn.luoluna.sweven.ai.ui.navigation.PhoneRouterApp
import cn.luoluna.sweven.ai.ui.theme.PhoneRouterTheme

class MainActivity : ComponentActivity() {

    private val openLanTick = mutableStateOf(0)
    private val openChatTick = mutableStateOf(0)
    private val openChatConversation = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        autoStartLanIfNeeded()
        readIntent(intent)
        setContent {
            PhoneRouterTheme {
                val openLan by openLanTick
                val openChat by openChatTick
                val openChatConv by openChatConversation
                PhoneRouterApp(
                    openLanTick = openLan,
                    openChatTick = openChat,
                    openChatConversation = openChatConv,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_LAN, false) == true) {
            openLanTick.value += 1
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true) {
            openChatConversation.value =
                intent.getLongExtra(EXTRA_OPEN_CHAT_CONVERSATION, -1L).takeIf { it > 0 }
            openChatTick.value += 1
        }
    }

    private fun autoStartLanIfNeeded() {
        val app = application as App
        if (app.settings.lanEnabled.value && !ServerState.running.value) {
            ContextCompat.startForegroundService(this, LanService.startIntent(this))
        }
    }

    companion object {
        const val EXTRA_OPEN_LAN = "open_lan"
        const val EXTRA_OPEN_CHAT = "open_chat"
        const val EXTRA_OPEN_CHAT_CONVERSATION = "open_chat_conversation"
    }
}
