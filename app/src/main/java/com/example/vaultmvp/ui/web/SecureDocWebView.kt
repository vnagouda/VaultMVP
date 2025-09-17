// app/src/main/java/com/example/vaultmvp/ui/web/SecureDocWebView.kt
package com.example.vaultmvp.ui.web

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

private class VaultJsBridge(private val data: ByteArray) {
    @JavascriptInterface
    fun readBase64(): String = Base64.encodeToString(data, Base64.NO_WRAP)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SecureDocWebView(
    htmlAssetPath: String,  // e.g. "viewers/pptx/index.html"
    bytes: ByteArray,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val pageUrl = "file:///android_asset/$htmlAssetPath"

    AndroidView(
        modifier = modifier,
        factory = {
            WebView(ctx).apply {
                // ✅ Make sure the WebView fills its parent
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                setBackgroundColor(Color.TRANSPARENT)
                overScrollMode = View.OVER_SCROLL_NEVER

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.allowFileAccessFromFileURLs = true
                settings.allowUniversalAccessFromFileURLs = true
                settings.blockNetworkLoads = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE

                // Helpful if HTML uses meta viewport + our layout()
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false // let our JS compute slide size

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        android.util.Log.d(
                            "VaultMVP",
                            "WV CONSOLE: ${message.message()} @${message.sourceId()}:${message.lineNumber()}"
                        )
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    private val allowedHosts = setOf(
                        "cdn.jsdelivr.net",
                        "cdn.jsdelivr.org",
                        "cdnjs.cloudflare.com",
                        "unpkg.com"
                    )
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val u = request?.url ?: return null
                        return when (u.scheme) {
                            "file" -> null
                            "https" -> if (u.host in allowedHosts) null else block()
                            "http", "content", "data" -> block()
                            else -> block()
                        }
                    }
                    private fun block(): WebResourceResponse =
                        WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), null)

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        // Start viewer
                        view.evaluateJavascript("typeof window.__vaultStart === 'function'") { exists ->
                            if (exists == "true") {
                                view.evaluateJavascript("window.__vaultStart()") { /* ignore */ }
                            } else {
                                android.util.Log.e("VaultMVP", "Viewer page missing __vaultStart()")
                            }
                        }
                        // 🔔 Nudge the page to recompute layout with real size
                        post {
                            view.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
                        }
                    }
                }

                // Bridge: give the page the file bytes
                addJavascriptInterface(VaultJsBridge(bytes), "VaultBridge")

                loadUrl(pageUrl)
            }
        },
        update = { webView ->
            // If Compose recomposes with same bytes, nothing to do.
            // If you ever pass different bytes for same page, you can re-trigger:
            // webView.evaluateJavascript("window.__vaultStart && window.__vaultStart()", null)
        }
    )
}
