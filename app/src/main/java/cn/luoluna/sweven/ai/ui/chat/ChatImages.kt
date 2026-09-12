package cn.luoluna.sweven.ai.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object ChatImages {

    /**
     * 将相册图片压缩为 JPEG base64 data URL（持久化到消息里，无需保留临时权限）。
     */
    suspend fun uriToDataUrl(
        context: Context,
        uri: Uri,
        maxDim: Int = 1024,
        quality: Int = 82,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching null
            val scaled = if (maxOf(src.width, src.height) > maxDim) {
                val scale = maxDim.toFloat() / maxOf(src.width, src.height)
                Bitmap.createScaledBitmap(
                    src,
                    (src.width * scale).toInt().coerceAtLeast(1),
                    (src.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else src
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            if (scaled !== src) scaled.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }

    /** 将生成的图片字节压缩为 JPEG data URL（与 uriToDataUrl 同一存储形态，直接落库消息） */
    suspend fun bytesToDataUrl(
        bytes: ByteArray,
        maxDim: Int = 1536,
        quality: Int = 88,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching null
            val scaled = if (maxOf(src.width, src.height) > maxDim) {
                val scale = maxDim.toFloat() / maxOf(src.width, src.height)
                Bitmap.createScaledBitmap(
                    src,
                    (src.width * scale).toInt().coerceAtLeast(1),
                    (src.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else src
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            if (scaled !== src) scaled.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        }.getOrNull()
    }
}

/** 解码 data URL 图片用于展示 */
@Composable
fun rememberDataUrlBitmap(url: String?): State<ImageBitmap?> = produceState<ImageBitmap?>(null, url) {
    value = null
    if (url.isNullOrBlank()) return@produceState
    value = withContext(Dispatchers.IO) {
        runCatching {
            val base64 = url.substringAfter("base64,", "")
            if (base64.isEmpty()) return@runCatching null
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
}

/** 解码 data URL 缩略图（inSampleSize 降采样到 maxDim 内，创作网格等列表场景防 OOM） */
@Composable
fun rememberDataUrlThumb(url: String?, maxDim: Int = 384): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, url) {
        value = null
        if (url.isNullOrBlank() || !url.startsWith("data:")) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val base64 = url.substringAfter("base64,", "")
                if (base64.isEmpty()) return@runCatching null
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }

/** 解码本地 content:// 图片用于展示（待发送附件） */
@Composable
fun produceUriBitmap(context: Context, uri: Uri): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, uri) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }


/** 视频封面帧：取首帧降采样为 ImageBitmap；文件缺失/解码失败为 null（渲染占位块） */
@Composable
fun rememberVideoFrame(path: String): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(path)
                val frame = retriever.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                // 封面帧缩到 480px 宽即可，避免长视频首帧过大
                val maxW = 480
                val scaled = if (frame != null && frame.width > maxW) {
                    val h = frame.height * maxW / frame.width
                    android.graphics.Bitmap.createScaledBitmap(frame, maxW, h, true)
                } else {
                    frame
                }
                scaled?.asImageBitmap()
            }.getOrNull()
        }
    }.value
