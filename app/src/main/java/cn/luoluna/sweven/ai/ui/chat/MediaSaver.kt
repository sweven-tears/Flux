package cn.luoluna.sweven.ai.ui.chat

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 保存图片（data URL）到系统相册 Pictures/Flux。
 * API 29+ 走 MediaStore 相对路径免权限；26-28 需 WRITE_EXTERNAL_STORAGE（调用方先申请）。
 */
object MediaSaver {

    data class SaveResult(val saved: Int, val failed: Int)

    /** 批量保存；非 data: 前缀的条目（如已清理的占位标记）自动跳过 */
    suspend fun saveDataUrls(context: Context, urls: List<String>): SaveResult = withContext(Dispatchers.IO) {
        var saved = 0
        var failed = 0
        urls.filter { it.startsWith("data:") }.distinct().forEach { url ->
            runCatching { saveOne(context, url) }
                .onSuccess { saved++ }
                .onFailure { failed++ }
        }
        SaveResult(saved, failed)
    }

    private fun saveOne(context: Context, dataUrl: String) {
        val mime = dataUrl.substringAfter("data:").substringBefore(";").ifBlank { "image/jpeg" }
        val base64 = dataUrl.substringAfter("base64,", "")
        require(base64.isNotEmpty()) { "不是 base64 data URL" }
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            else -> "jpg"
        }
        val name = "flux_${System.currentTimeMillis()}.$ext"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Flux")
            values.put(MediaStore.Images.Media.IS_PENDING, 1)
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("相册写入失败")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("相册写入失败")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            // API 26-28：直接写公共图片目录，再以 DATA 列登记进媒体库
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Flux",
            )
            dir.mkdirs()
            val file = File(dir, name)
            file.writeBytes(bytes)
            @Suppress("DEPRECATION")
            values.put(MediaStore.Images.Media.DATA, file.absolutePath)
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("相册写入失败")
        }
    }
}
