package cn.luoluna.sweven.ai.ui.common

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

private fun Context.grantAndStart(mime: String, file: File, failHint: String) {
    val uri = FileProvider.getUriForFile(this, packageName + FILE_PROVIDER_SUFFIX, file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { startActivity(intent) }
        .onFailure { Toast.makeText(this, failHint, Toast.LENGTH_SHORT).show() }
}

/** 视频交给系统播放器播放（私有目录文件经 FileProvider 共享） */
fun openVideoExternally(context: Context, file: File) {
    if (!file.exists()) {
        Toast.makeText(context, "视频文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    context.grantAndStart("video/mp4", file, "无法打开：未找到可用的视频播放器")
}

private val DATA_URL_MIME = Regex("^data:image/(\\w+);base64,")

/**
 * data URL 图片导出到 cacheDir/external_media/ 后交给系统查看器；
 * 导出前清空该目录，避免缓存堆积。
 */
fun openImageExternally(context: Context, dataUrl: String) {
    runCatching {
        val mime = DATA_URL_MIME.find(dataUrl)?.groupValues?.get(1)?.lowercase() ?: "png"
        val ext = if (mime == "jpeg") "jpg" else mime
        val payload = dataUrl.substringAfter("base64,", "")
        if (payload.isEmpty()) error("不是 base64 图片")
        val dir = File(context.cacheDir, "external_media").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "flux_media_${System.currentTimeMillis()}.$ext")
        file.writeBytes(Base64.decode(payload, Base64.DEFAULT))
        context.grantAndStart("image/$mime", file, "无法打开：未找到可用的图片查看器")
    }.onFailure {
        Toast.makeText(context, "无法打开图片: ${it.message}", Toast.LENGTH_SHORT).show()
    }
}
