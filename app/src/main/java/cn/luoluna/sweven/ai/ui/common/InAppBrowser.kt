package cn.luoluna.sweven.ai.ui.common

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 内置网页查看器：全屏弹层内用 WebView 打开链接，不再跳转外部浏览器。
 * 传 [url] 加载网址；传 [content] 则直接渲染 HTML/SVG 内容（用于代码块一键预览）。
 * 顶栏提供网页后退 / 刷新 / 交给外部浏览器 / 关闭，系统返回键优先回退网页历史。
 */
@Composable
fun InAppBrowser(
    url: String? = null,
    content: String? = null,
    onDismiss: () -> Unit,
) {
    check(url != null || content != null) { "InAppBrowser 需要 url 或 content 之一" }
    val context = LocalContext.current
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf(content?.let { "内置预览" } ?: url.orEmpty()) }
    var pageUrl by remember { mutableStateOf(if (content != null) "data:text/html" else url.orEmpty()) }
    var canGoBack by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(100) }

    fun navigateBackOrClose() {
        val wv = webViewRef.value
        if (wv != null && wv.canGoBack()) wv.goBack() else onDismiss()
    }

    BackHandler { navigateBackOrClose() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Bottom
                        )
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    IconButton(onClick = { navigateBackOrClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            pageTitle,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            pageUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { webViewRef.value?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    if (content == null) {
                        IconButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)))
                                }
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "用外部浏览器打开")
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                if (progress < 100) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AndroidView(
                    modifier = Modifier.weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            webViewClient = object : WebViewClient() {
                                override fun doUpdateVisitedHistory(
                                    view: WebView,
                                    url: String?,
                                    isReload: Boolean,
                                ) {
                                    canGoBack = view.canGoBack()
                                    // data 加载时地址为 about:blank，不覆盖"内置预览"标签
                                    url?.takeIf { it.isNotBlank() && !it.startsWith("about:") }
                                        ?.let { pageUrl = it }
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    pageTitle = view.title?.takeIf { it.isNotBlank() && it != "about:blank" }
                                        ?: if (content == null) url.orEmpty() else "内置预览"
                                    canGoBack = view.canGoBack()
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            if (content != null) {
                                loadDataWithBaseURL(null, content, "text/html", "UTF-8", null)
                            } else {
                                loadUrl(url.orEmpty())
                            }
                        }.also { webViewRef.value = it }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }
}
