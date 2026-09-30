package com.strike.web

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.graphics.Bitmap
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.strike.core.Logs
import com.strike.StrikeApp
import com.strike.server.ACCESS_PAGE
import com.strike.server.HttpServer
import com.strike.server.LOCK_PAGE
import com.strike.server.pagePath

private const val TAG = "WebUi"
private const val HOST = "127.0.0.1"
private const val STARTING_HTML = """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;height:100%;background:#0b0b0d;color:#f2f2f5;
font-family:system-ui,sans-serif}body{display:flex;align-items:center;justify-content:center}
p{margin:0;font-size:1.125rem;letter-spacing:.02em;opacity:.72}</style></head>
<body><p>Starting Strike…</p></body></html>"""

object WebUi {

    private var web: WebView? = null

    fun mount(view: WebView) {
        web = view
        val debuggable = view.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        view.overScrollMode = WebView.OVER_SCROLL_NEVER
        view.defaultFocusHighlightEnabled = false
        // Keep navigation inside the WebView rather than opening the external browser.
        val client = StrikeWebViewClient()
        view.webViewClient = client
        // Paint immediately so first-run ADB / dashboard attach is not a blank screen.
        client.showStarting(view)
        (view.context.applicationContext as StrikeApp).dashboard.observe { cookie, _ ->
            if (web === view) CookieManager.getInstance().setCookie(page("/"), cookie) { accepted ->
                if (accepted) client.connected(view)
                else Logs.w(TAG, "The car screen could not establish its session. Reopen Strike")
            }
        }
    }

    // A hidden WebView otherwise keeps animating and decoding video on the GPU.
    fun pause() {
        web?.onPause()
    }

    fun resume() {
        web?.onResume()
    }

    fun cover() {
        val view = web ?: return
        view.post { view.loadUrl(page(LOCK_PAGE)) }
    }
}

private fun page(path: String): String = "http://$HOST:${HttpServer.PORT}$path"

private class StrikeWebViewClient : WebViewClient() {
    private var destination = page("/")
    private var failed = false
    private var recovering = false

    fun showStarting(view: WebView) {
        view.visibility = View.VISIBLE
        view.loadDataWithBaseURL(
            null,
            STARTING_HTML,
            "text/html",
            Charsets.UTF_8.name(),
            null
        )
    }

    fun connected(view: WebView) {
        view.visibility = View.VISIBLE
        val path = pagePath(Uri.parse(destination).path ?: "")
        view.loadUrl(if (path == ACCESS_PAGE) page("/") else destination)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.scheme != "http" || request.url.host != HOST || request.url.port != HttpServer.PORT

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        val uri = Uri.parse(url)
        if (uri.scheme == "http" && uri.host == HOST && uri.port == HttpServer.PORT) destination = url
        failed = false
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame || request.url.scheme != "http" ||
            request.url.host != HOST || request.url.port != HttpServer.PORT) return
        failed = true
        if (recovering) {
            showStarting(view)
            Logs.w(TAG, "The dashboard could not load. Reopen Strike to retry")
            return
        }
        recovering = true
        destination = request.url.toString()
        showStarting(view)
        view.stopLoading()
        (view.context.applicationContext as StrikeApp).dashboard.reconnect { ready ->
            if (!ready && failed) showStarting(view)
        }
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        if (failed) return
        recovering = false
        view.visibility = View.VISIBLE
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if ((response.statusCode == 401 || response.statusCode == 403) && request.url.host == HOST &&
            request.url.port == HttpServer.PORT && request.url.path?.startsWith("/api/") == true) {
            (view.context.applicationContext as StrikeApp).dashboard.resume { }
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (pagePath(Uri.parse(url).path ?: "") == LOCK_PAGE) view.clearHistory()
    }

    // Handle renderer death so it does not terminate the host app.
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Logs.w(TAG, "the screen crashed and was reloaded, recording was not affected")
        view.loadUrl("http://$HOST:${HttpServer.PORT}/")
        return true
    }
}
