package cn.luoluna.sweven.ai.ui.chat

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * 内嵌视频播放器（系统 VideoView + MediaController）：按 App 私有目录文件路径播放，
 * prepared 后自动播放；控制条支持暂停/拖动进度，离开组合即停止释放。
 */
@Composable
fun FluxVideoPlayer(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(path) {
        VideoView(context).apply {
            val controller = MediaController(context)
            controller.setAnchorView(this)
            setMediaController(controller)
            setVideoURI(Uri.fromFile(File(path)))
            setOnPreparedListener { it.start() }
        }
    }
    DisposableEffect(path) {
        onDispose { player.stopPlayback() }
    }
    AndroidView(modifier = modifier, factory = { player })
}
