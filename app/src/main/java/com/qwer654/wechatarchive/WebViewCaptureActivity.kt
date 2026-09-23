package com.qwer654.wechatarchive

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val autoHandler = Handler(Looper.getMainLooper())
    private var captureInProgress = false
    private var captureCompleted = false
    private var lastAutoState = ""

    private val autoCheck = object : Runnable {
        override fun run() {
            if (!captureCompleted && ::webView.isInitialized) {
                checkPageForAutoCapture()
            }
        }
    }

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
            text = "正在打开微信文章。正文加载完成后会自动采集；若出现验证，请正常完成验证，之后无需再点采集。"
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
                    if (!captureCompleted) {
                        statusView.text = "页面已加载，正在自动检测真实文章正文…"
                        scheduleAutoCheck(350)
                    }
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
            text = "立即采集（备用）"
            setOnClickListener { captureCurrentPage(auto = false) }
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
        scheduleAutoCheck(900)
    }

    private fun scheduleAutoCheck(delayMs: Long = 1100L) {
        autoHandler.removeCallbacks(autoCheck)
        if (!captureCompleted) autoHandler.postDelayed(autoCheck, delayMs)
    }

    private fun decodeJsString(value: String?): String =
        runCatching {
            if (value == null || value == "null") "" else JSONArray("[" + value + "]").getString(0)
        }.getOrDefault("")

    private fun checkPageForAutoCapture() {
        if (captureCompleted || captureInProgress) {
            if (!captureCompleted) scheduleAutoCheck()
            return
        }

        val script = """
            (function() {
                var bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';
                var blocked =
                    bodyText.indexOf('当前环境异常') >= 0 ||
                    (bodyText.indexOf('环境异常') >= 0 && bodyText.indexOf('完成验证') >= 0) ||
                    (bodyText.indexOf('去验证') >= 0 && bodyText.indexOf('继续访问') >= 0) ||
                    bodyText.indexOf('请完成验证后继续访问') >= 0;
                var title =
                    document.querySelector('#activity-name') ||
                    document.querySelector('h1.rich_media_title') ||
                    document.querySelector('.rich_media_title');
                var content =
                    document.querySelector('#js_content') ||
                    document.querySelector('.rich_media_content') ||
                    document.querySelector('article');
                var titleText = title ? (title.innerText || title.textContent || '').trim() : '';
                var contentText = content ? (content.innerText || content.textContent || '').trim() : '';
                if (blocked) return 'blocked';
                if (document.readyState === 'complete' && titleText.length > 0 && contentText.length >= 20) return 'ready';
                return 'wait';
            })()
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            when (decodeJsString(result)) {
                "ready" -> {
                    lastAutoState = "ready"
                    statusView.text = "检测到真实文章正文，正在自动采集…"
                    captureCurrentPage(auto = true)
                }
                "blocked" -> {
                    if (lastAutoState != "blocked") {
                        statusView.text = "当前是微信验证页。请正常完成验证；正文出现后 App 会自动采集。"
                    }
                    lastAutoState = "blocked"
                    scheduleAutoCheck()
                }
                else -> {
                    if (lastAutoState != "wait") {
                        statusView.text = "正在等待文章正文完整加载，加载完成后会自动采集…"
                    }
                    lastAutoState = "wait"
                    scheduleAutoCheck()
                }
            }
        }
    }

    private fun captureCurrentPage(auto: Boolean = true) {
        if (captureInProgress || captureCompleted) return
        captureInProgress = true
        statusView.text = if (auto) "正在自动提取文章正文…" else "正在提取当前页面正文…"
        webView.evaluateJavascript(
            "(function(){return document.documentElement ? document.documentElement.outerHTML : '';})()"
        ) { encoded ->
            val html = runCatching {
                if (encoded == null || encoded == "null") "" else JSONArray("[$encoded]").getString(0)
            }.getOrDefault("")

            if (html.isBlank()) {
                captureInProgress = false
                statusView.text = "没有读取到页面内容，继续等待页面加载…"
                scheduleAutoCheck()
                return@evaluateJavascript
            }

            val currentUrl = originalUrl
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
                    captureCompleted = true
                    captureInProgress = false
                    autoHandler.removeCallbacks(autoCheck)
                    statusView.text = "自动采集成功：《" + it.title + "》"
                    Toast.makeText(this@WebViewCaptureActivity, "文章已自动保存为 Markdown", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }.onFailure {
                    captureInProgress = false
                    statusView.text = when (it) {
                        is VerificationRequiredException ->
                            "当前仍是微信验证页。完成验证后会自动重新检测并采集。"
                        else -> "暂未能解析完整正文：" + (it.message ?: it.javaClass.simpleName) + "，继续自动检测…"
                    }
                    scheduleAutoCheck(1300)
                }
            }
        }
    }

    override fun onDestroy() {
        autoHandler.removeCallbacks(autoCheck)
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
