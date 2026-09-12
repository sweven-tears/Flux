package cn.luoluna.sweven.ai.ui.chat

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import cn.luoluna.sweven.ai.data.db.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 可保存条目：图片 data URL 或视频文件引用（flux-video: 前缀） */
private fun isSavable(url: String): Boolean =
    url.startsWith("data:") || url.startsWith(MessageEntity.VIDEO_PREFIX)

/**
 * 保存媒体到系统相册：图片（data URL）→ Pictures/Flux，视频（flux-video: 文件路径）→ Movies/Flux。
 * API 29+ 走 MediaStore 相对路径免权限；26-28 需 WRITE_EXTERNAL_STORAGE（调用方先申请）。
 */
object MediaSaver {

    data class SaveResult(val saved: Int, val failed: Int)

    /** 批量保存；非 data:/视频前缀的条目（如已清理的占位标记）自动跳过 */
    suspend fun saveDataUrls(context: Context, urls: List<String>): SaveResult = withContext(Dispatchers.IO) {
        var saved = 0
        var failed = 0
        urls.filter { isSavable(it) }.distinct().forEach { url ->
            runCatching { saveOne(context, url) }
                .onSuccess { saved++ }
                .onFailure { failed++ }
        }
        SaveResult(saved, failed)
    }

    private fun saveOne(context: Context, url: String) {
        if (url.startsWith(MessageEntity.VIDEO_PREFIX)) {
            saveVideoOne(context, url.removePrefix(MessageEntity.VIDEO_PREFIX))
        } else {
            saveImageOne(context, url)
        }
    }

    private fun saveVideoOne(context: Context, path: String) {
        val file = File(path)
        require(file.exists()) { "视频文件不存在" }
        val name = "flux_${System.currentTimeMillis()}.mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Flux")
            values.put(MediaStore.Video.Media.IS_PENDING, 1)
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("相册写入失败")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("相册写入失败")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            // API 26-28：直接写公共视频目录，再以 DATA 列登记进媒体库
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "Flux",
            )
            dir.mkdirs()
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            @Suppress("DEPRECATION")
            values.put(MediaStore.Video.Media.DATA, dest.absolutePath)
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("相册写入失败")
        }
    }

    private fun saveImageOne(context: Context, dataUrl: String) {
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

private fun toastSaveResult(context: Context, result: MediaSaver.SaveResult) {
    val msg = when {
        result.failed == 0 && result.saved > 0 -> "已保存到相册"
        result.saved > 0 -> "已保存 ${result.saved} 张，失败 ${result.failed} 张"
        else -> "保存失败"
    }
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}

/**
 * 带权限处理的「保存到相册」入口：API ≤ 28 先弹运行时权限，授权后自动继续保存。
 * 返回的函数可在任意回调里直接调用；非 data: 前缀的条目（已清理占位标记）自动跳过。
 */
@Composable
fun rememberGallerySaver(): (List<String>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<String>?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val urls = pending
        pending = null
        if (granted && !urls.isNullOrEmpty()) {
            scope.launch { toastSaveResult(context, MediaSaver.saveDataUrls(context, urls)) }
        } else if (!granted) {
            Toast.makeText(context, "未授予存储权限，无法保存到相册", Toast.LENGTH_SHORT).show()
        }
    }
    return { urls ->
        val targets = urls.filter { isSavable(it) }
        if (targets.isEmpty()) {
            Toast.makeText(context, "没有可保存的媒体", Toast.LENGTH_SHORT).show()
        } else {
            val needPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
            if (needPermission) {
                pending = targets
                launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                scope.launch { toastSaveResult(context, MediaSaver.saveDataUrls(context, targets)) }
            }
        }
    }
}
