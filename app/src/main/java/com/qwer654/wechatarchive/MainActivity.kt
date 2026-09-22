package com.qwer654.wechatarchive

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private var incomingText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readShare(intent)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    ArchiveScreen(this, incomingText)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShare(intent)
    }

    private fun readShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            incomingText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        }
    }
}

@Composable
private fun ArchiveScreen(activity: MainActivity, incomingText: String) {
    val repository = remember { ArchiveRepository(activity.applicationContext) }
    val collector = remember { ArticleCollector() }
    val historyClient = remember { WeReadHistoryClient(activity.applicationContext) }
    val scope = rememberCoroutineScope()
    var credential by remember { mutableStateOf(historyClient.savedCredential()) }
    var loginSession by remember { mutableStateOf<LoginSession?>(null) }

    var input by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf(LocalDate.now().minusMonths(12).toString()) }
    var end by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("粘贴公众号文章链接，或在微信中把文章分享到本 App。") }
    var records by remember { mutableStateOf(repository.listAll()) }
    var exportRecord by remember { mutableStateOf<ArticleRecord?>(null) }

    LaunchedEffect(incomingText) {
        if (incomingText.isNotBlank()) input = incomingText
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val record = exportRecord
        if (uri != null && record != null) {
            runCatching {
                activity.contentResolver.openOutputStream(uri)?.use {
                    it.write(repository.readMarkdown(record).toByteArray(Charsets.UTF_8))
                } ?: error("无法打开目标文件")
            }.onSuccess {
                Toast.makeText(activity, "已导出 Markdown", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(activity, "导出失败：" + it.message, Toast.LENGTH_LONG).show()
            }
        }
        exportRecord = null
    }

    val browserCapture = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            records = repository.listAll()
            status = "浏览器模式采集完成，已从实际加载的微信页面重新解析正文。"
        }
    }

    fun range(months: Long) {
        start = LocalDate.now().minusMonths(months).toString()
        end = LocalDate.now().toString()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("公众号典藏", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("每篇文章独立保存为 Markdown；本地已有文章不会重复下载。")
        }

        item {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("文章链接 / 多个链接 / 合集页面") },
                minLines = 4
            )
        }

        item {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("公众号或作者筛选（可选）") },
                singleLine = true
            )
        }

        item {
            Text("时间范围", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ range(1) }) { Text("1个月") }
                TextButton({ range(3) }) { Text("3个月") }
                TextButton({ range(6) }) { Text("6个月") }
                TextButton({ range(12) }) { Text("12个月") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("开始日期") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { end = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("结束日期") },
                    singleLine = true
                )
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !working,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val url = webUrls(input).firstOrNull()
                        if (url == null) {
                            status = "没有找到网址。"
                            return@OutlinedButton
                        }
                        scope.launch {
                            working = true
                            status = "正在发现当前页面内的文章链接…"
                            runCatching {
                                withContext(Dispatchers.IO) { collector.discover(url) }
                            }.onSuccess {
                                if (it.isEmpty()) {
                                    status = "页面中没有发现可直接访问的公众号文章链接。"
                                } else {
                                    input = it.joinToString("\n")
                                    status = "发现 " + it.size + " 个文章链接。"
                                }
                            }.onFailure {
                                status = "发现失败：" + it.message
                            }
                            working = false
                        }
                    }
                ) { Text("发现文章") }

                Button(
                    enabled = !working,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val from = runCatching { LocalDate.parse(start.trim()) }.getOrNull()
                        val to = runCatching { LocalDate.parse(end.trim()) }.getOrNull()
                        val urls = wechatUrls(input)
                        if (from == null || to == null || from.isAfter(to)) {
                            status = "日期格式应为 YYYY-MM-DD。"
                            return@Button
                        }
                        if (urls.isEmpty()) {
                            status = "没有找到微信公众号文章链接。"
                            return@Button
                        }

                        scope.launch {
                            working = true
                            var added = 0
                            var existed = 0
                            var skipped = 0
                            var failed = 0
                            var fallbackUrl: String? = null

                            urls.forEachIndexed { index, url ->
                                status = "正在处理 " + (index + 1) + "/" + urls.size
                                val local = withContext(Dispatchers.IO) { repository.findByUrl(url) }
                                if (local != null && repository.isUsable(local)) {
                                    existed++
                                    return@forEachIndexed
                                }

                                val parsed = runCatching {
                                    withContext(Dispatchers.IO) { collector.fetch(url) }
                                }.getOrElse {
                                    if (it is VerificationRequiredException && fallbackUrl == null) {
                                        fallbackUrl = url
                                    }
                                    failed++
                                    return@forEachIndexed
                                }

                                val dateOk = parsed.publishDate?.let { !it.isBefore(from) && !it.isAfter(to) } ?: true
                                val f = filter.trim()
                                val authorOk = f.isBlank() ||
                                    parsed.account.contains(f, true) ||
                                    parsed.author.contains(f, true)

                                if (!dateOk || !authorOk) {
                                    skipped++
                                    return@forEachIndexed
                                }

                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        repository.save(parsed, collector.markdown(parsed))
                                    }
                                }.onSuccess { added++ }.onFailure { failed++ }
                            }

                            records = withContext(Dispatchers.IO) { repository.listAll() }
                            status = "完成：新增 $added，已有 $existed，筛选跳过 $skipped，失败 $failed。"
                            working = false
                            val fallback = fallbackUrl
                            if (fallback != null && urls.size == 1) {
                                status = "检测到微信环境验证页，已切换到浏览器模式。完成页面验证并看到正文后，点击“采集当前页面”。"
                                browserCapture.launch(
                                    Intent(activity, WebViewCaptureActivity::class.java)
                                        .putExtra(WebViewCaptureActivity.EXTRA_URL, fallback)
                                )
                            }
                        }
                    }
                ) { Text(if (working) "处理中…" else "开始采集") }
            }
        }

        item {
            OutlinedButton(
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val url = wechatUrls(input).firstOrNull()
                    if (url == null) {
                        status = "请先输入一篇微信公众号文章链接。"
                    } else {
                        browserCapture.launch(
                            Intent(activity, WebViewCaptureActivity::class.java)
                                .putExtra(WebViewCaptureActivity.EXTRA_URL, url)
                        )
                    }
                }
            ) { Text("浏览器模式采集（验证页/内容异常时使用）") }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("公众号历史增量同步（可选）", fontWeight = FontWeight.Bold)
                    Text(
                        "使用微信读书二维码兼容服务。令牌只保存在本机；遇到登录失效或限流会停止，不做风控规避。",
                        style = MaterialTheme.typography.bodySmall
                    )

                    val cred = credential
                    if (cred == null) {
                        Button(
                            enabled = !working,
                            onClick = {
                                scope.launch {
                                    working = true
                                    status = "正在生成微信读书登录二维码…"
                                    runCatching {
                                        withContext(Dispatchers.IO) { historyClient.createLoginSession() }
                                    }.onSuccess {
                                        loginSession = it
                                        status = "请使用另一台设备上的微信扫描二维码，然后点“扫码完成”。"
                                    }.onFailure {
                                        status = "生成二维码失败：" + it.message
                                    }
                                    working = false
                                }
                            }
                        ) { Text("生成登录二维码") }

                        val session = loginSession
                        if (session != null) {
                            val bitmap = remember(session.scanUrl) { makeQrBitmap(session.scanUrl) }
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "微信读书登录二维码",
                                modifier = Modifier.fillMaxWidth().height(260.dp),
                                contentScale = ContentScale.Fit
                            )
                            Button(
                                enabled = !working,
                                onClick = {
                                    scope.launch {
                                        working = true
                                        status = "正在确认扫码登录…"
                                        runCatching {
                                            withContext(Dispatchers.IO) { historyClient.completeLogin(session.uuid) }
                                        }.onSuccess {
                                            credential = it
                                            loginSession = null
                                            status = "历史同步登录成功：" + it.username
                                        }.onFailure {
                                            status = "登录尚未完成或失败：" + it.message
                                        }
                                        working = false
                                    }
                                }
                            ) { Text("扫码完成，确认登录") }
                        }
                    } else {
                        Text("已登录：" + cred.username.ifBlank { cred.vid })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !working,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val sample = wechatUrls(input).firstOrNull()
                                    val from = runCatching { LocalDate.parse(start.trim()) }.getOrNull()
                                    val to = runCatching { LocalDate.parse(end.trim()) }.getOrNull()
                                    if (sample == null || from == null || to == null || from.isAfter(to)) {
                                        status = "请先放入该公众号任意一篇文章链接，并检查日期范围。"
                                        return@Button
                                    }

                                    scope.launch {
                                        working = true
                                        var added = 0
                                        var existed = 0
                                        var failed = 0
                                        var filtered = 0
                                        var page = 1
                                        var consecutiveExisting = 0
                                        var stop = false

                                        try {
                                            val mp = withContext(Dispatchers.IO) {
                                                historyClient.resolveAccount(sample, cred)
                                            }
                                            status = "已识别公众号：" + mp.name + "，开始同步历史索引…"

                                            while (page <= 100 && !stop) {
                                                val history = withContext(Dispatchers.IO) {
                                                    historyClient.historyPage(mp.id, page, cred)
                                                }
                                                if (history.isEmpty()) break

                                                var oldest: LocalDate? = null
                                                for (item in history) {
                                                    val date = Instant.ofEpochSecond(item.publishTime)
                                                        .atZone(ZoneId.systemDefault()).toLocalDate()
                                                    if (oldest == null || date.isBefore(oldest)) oldest = date

                                                    if (date.isAfter(to)) continue
                                                    if (date.isBefore(from)) continue

                                                    val local = withContext(Dispatchers.IO) {
                                                        repository.findByUrl(item.url)
                                                    }
                                                    if (local != null && repository.isUsable(local)) {
                                                        existed++
                                                        consecutiveExisting++
                                                        if (consecutiveExisting >= 20) {
                                                            stop = true
                                                            break
                                                        }
                                                        continue
                                                    }

                                                    consecutiveExisting = 0
                                                    status = "同步 " + mp.name + "：第 " + page + " 页，正在保存《" + item.title + "》"

                                                    val parsed = try {
                                                        withContext(Dispatchers.IO) { collector.fetch(item.url) }
                                                    } catch (_: Throwable) {
                                                        failed++
                                                        continue
                                                    }

                                                    val enriched = parsed.copy(
                                                        account = parsed.account.ifBlank { mp.name },
                                                        publishDate = parsed.publishDate ?: date
                                                    )
                                                    val fText = filter.trim()
                                                    val authorOk = fText.isBlank() ||
                                                        enriched.account.contains(fText, true) ||
                                                        enriched.author.contains(fText, true)
                                                    if (!authorOk) {
                                                        filtered++
                                                        continue
                                                    }

                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            repository.save(enriched, collector.markdown(enriched))
                                                        }
                                                        added++
                                                    } catch (_: Throwable) {
                                                        failed++
                                                    }
                                                }

                                                if (oldest != null && oldest.isBefore(from)) stop = true
                                                if (!stop) {
                                                    page++
                                                    delay(1200)
                                                }
                                            }

                                            records = withContext(Dispatchers.IO) { repository.listAll() }
                                            status = "历史同步完成：" + mp.name +
                                                "；新增 " + added + "，本地已有 " + existed +
                                                "，筛选跳过 " + filtered + "，失败 " + failed + "。"
                                        } catch (t: Throwable) {
                                            status = "历史同步停止：" + t.message
                                        }
                                        working = false
                                    }
                                }
                            ) { Text("同步该公众号历史") }

                            OutlinedButton(
                                enabled = !working,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    historyClient.clearCredential()
                                    credential = null
                                    loginSession = null
                                    status = "已清除本机历史同步登录信息。"
                                }
                            ) { Text("退出历史登录") }
                        }
                    }
                }
            }
        }

        item {
            Text(status)
            HorizontalDivider()
            Text("本地文章 " + records.size + " 篇", fontWeight = FontWeight.Bold)
        }

        items(records, key = { it.id }) { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(record.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOf(record.account, record.author, record.publishDate)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(record.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Button(onClick = {
                        exportRecord = record
                        exporter.launch(fileName(record.publishDate + "_" + record.title + ".md"))
                    }) { Text("导出单个 .md") }
                }
            }
        }

        item { Spacer(Modifier.height(28.dp)) }
    }
}

private fun webUrls(text: String): List<String> =
    Regex("""https?://[^\s<>"']+""").findAll(text)
        .map { it.value.trimEnd(')', '）', '。', ',', '，', ';', '；') }
        .distinct().toList()

private fun wechatUrls(text: String): List<String> =
    webUrls(text).filter {
        runCatching { java.net.URI(it).host.equals("mp.weixin.qq.com", true) }.getOrDefault(false)
    }

private fun fileName(value: String): String =
    value.replace(Regex("""[\\/:*?"<>|\r\n]+"""), "_").trim().take(120).ifBlank { "article.md" }
