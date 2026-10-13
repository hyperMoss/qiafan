package com.example.qiafan.fanfou

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class WebPageActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var address: TextView
    private lateinit var error: TextView
    private lateinit var progress: ProgressBar
    private var originalUrl = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!isWebUrl(url)) {
            finish()
            return
        }
        originalUrl = url
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(TextView(this).apply {
            text = "‹ 返回"
            textSize = 16f
            setTextColor(Color.rgb(31, 36, 42))
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { onBackPressed() }
        })
        address = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(107, 113, 121))
            maxLines = 2
            setPadding(0, dp(8), dp(12), dp(8))
        }
        toolbar.addView(address, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(TextView(this).apply {
            text = "浏览器"
            textSize = 14f
            setTextColor(Color.rgb(0, 119, 181))
            contentDescription = "用本地浏览器打开"
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(16), dp(16))
            setOnClickListener {
                val currentUrl = webView.url ?: url
                if (isWebUrl(currentUrl)) {
                    val browserUrl = if (currentUrl == httpsUrl(originalUrl)) originalUrl else currentUrl
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl)))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this@WebPageActivity, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
        column.addView(toolbar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        column.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        error = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(167, 43, 43))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
            setOnClickListener { webView.reload() }
        }
        column.addView(error)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, value: Int) {
                    this@WebPageActivity.progress.progress = value
                    this@WebPageActivity.progress.visibility = if (value == 100) View.GONE else View.VISIBLE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    address.text = url
                    error.visibility = View.GONE
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    navigate(request.url.toString())

                @Deprecated("Deprecated in Android")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = navigate(url)

                private fun navigate(url: String): Boolean {
                    if (!isWebUrl(url)) {
                        Toast.makeText(this@WebPageActivity, "仅支持打开网页链接", Toast.LENGTH_SHORT).show()
                        return true
                    }
                    val secure = httpsUrl(url)
                    if (secure == url) return false
                    loadUrl(secure)
                    return true
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                    if (request.isForMainFrame) showError("网页加载失败。" + failure.description)
                }
                override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, failure: android.net.http.SslError) {
                    handler.cancel()
                    showError("网页证书验证失败")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) showError("网页返回 HTTP " + response.statusCode)
                }
            }
        }
        column.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(column)
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(httpsUrl(url))
        }
    }

    private fun showError(message: String) {
        val httpHint = if (Uri.parse(originalUrl).scheme.equals("http", ignoreCase = true))
            "\n内置网页使用 HTTPS，可点右上角浏览器访问原地址。" else ""
        error.text = "$message$httpHint\n点击重试"
        error.visibility = View.VISIBLE
        progress.visibility = View.GONE
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URL = "web_url"
        fun isWebUrl(value: String): Boolean {
            val uri = Uri.parse(value)
            return uri.scheme?.lowercase() in listOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.userInfo == null
        }
        private fun httpsUrl(value: String): String =
            Uri.parse(value).buildUpon().scheme("https").build().toString()
    }
}
