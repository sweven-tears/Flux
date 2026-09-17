package cn.luoluna.sweven.ai.ui.common

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** 内置浏览器页的载荷：[url] 加载网址；[content] 直接渲染 HTML/SVG（代码块一键预览等） */
data class InAppBrowserPayload(
    val url: String? = null,
    val content: String? = null,
)

/**
 * 打开内置浏览器页的入口，由 PhoneRouterApp 提供实现（暂存 payload 后导航）。
 * MarkdownText 等深层组件经此打开浏览器页，避免穿透调用链传参。
 */
val LocalInAppBrowser = staticCompositionLocalOf<(InAppBrowserPayload) -> Unit> {
    {}
}

/**
 * 内置网页查看器：独立页面内用 WebView 打开链接，不再跳转外部浏览器。
 * 顶栏提供网页后退 / 刷新 / 交给外部浏览器 / 关闭，系统返回键优先回退网页历史。
 */
@Composable
fun InAppBrowserScreen(payload: InAppBrowserPayload?, onBack: () -> Unit) {
    if (payload == null) {
        // 进程被杀后恢复时载荷丢失（payload 无法进路由参数持久化）
        FullPageScaffold(title = "内置浏览器", onBack = onBack) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("预览内容已失效", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val context = LocalContext.current
    val url = payload.url
    val content = payload.content
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember(payload) { mutableStateOf(content?.let { "内置预览" } ?: url.orEmpty()) }
    var pageUrl by remember(payload) { mutableStateOf(if (content != null) "data:text/html" else url.orEmpty()) }
    var progress by remember { mutableIntStateOf(100) }

    fun navigateBackOrClose() {
        val wv = webViewRef.value
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    BackHandler { navigateBackOrClose() }

    FullPageScaffold(
        onBack = { navigateBackOrClose() },
        title = {
            Column {
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
        },
        actions = {
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
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.Close, contentDescription = "关闭")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
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
                                url?.takeIf { it.isNotBlank() && !it.startsWith("about:") }
                                    ?.let { pageUrl = it }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                pageTitle = view.title?.takeIf { it.isNotBlank() && it != "about:blank" }
                                    ?: if (content == null) url.orEmpty() else "内置预览"
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
