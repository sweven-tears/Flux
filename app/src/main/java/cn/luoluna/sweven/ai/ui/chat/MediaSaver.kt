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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        val targets = urls.filter { it.startsWith("data:") }
        if (targets.isEmpty()) {
            Toast.makeText(context, "没有可保存的图片", Toast.LENGTH_SHORT).show()
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
