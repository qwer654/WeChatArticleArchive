package com.qwer654.wechatarchive

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class WebViewCaptureActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private val collector = ArticleCollector()
    private val repository by lazy { ArchiveRepository(applicationContext) }
    private var originalUrl: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        originalUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (originalUrl.isBlank()) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        statusView = TextView(this).apply {
            text = "正在打开微信文章。若出现验证，请按页面提示完成验证后再采集。"
            setPadding(24, 18, 24, 18)
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    statusView.text = "页面已加载。确认正文显示正确后，点击“采集当前页面”。"
                }
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 16)
        }

        val reload = Button(this).apply {
            text = "重新加载"
            setOnClickListener { webView.reload() }
        }

        val capture = Button(this).apply {
            text = "采集当前页面"
            setOnClickListener { captureCurrentPage() }
        }

        val cancel = Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        }

        controls.addView(reload, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(capture, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        controls.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        webView.loadUrl(originalUrl)
    }

    private fun captureCurrentPage() {
        statusView.text = "正在提取当前页面正文…"
        webView.evaluateJavascript(
            "(function(){return document.documentElement ? document.documentElement.outerHTML : '';})()"
        ) { encoded ->
            val html = runCatching {
                if (encoded == null || encoded == "null") "" else JSONArray("[$encoded]").getString(0)
            }.getOrDefault("")

            if (html.isBlank()) {
                statusView.text = "没有读取到页面内容，请重新加载后再试。"
                return@evaluateJavascript
            }

            val currentUrl = webView.url?.takeIf { it.startsWith("http") } ?: originalUrl
            lifecycleScope.launch {
                runCatching {
                    val parsed = withContext(Dispatchers.Default) {
                        collector.parseHtml(currentUrl, html)
                    }
                    withContext(Dispatchers.IO) {
                        repository.save(parsed, collector.markdown(parsed))
                    }
                    parsed
                }.onSuccess {
                    statusView.text = "采集成功：《" + it.title + "》"
                    Toast.makeText(this@WebViewCaptureActivity, "文章已保存为 Markdown", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }.onFailure {
                    statusView.text = when (it) {
                        is VerificationRequiredException ->
                            "当前仍是微信验证页。请先完成页面验证，并确认已经看到文章正文后再点击采集。"
                        else -> "采集失败：" + (it.message ?: it.javaClass.simpleName)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
