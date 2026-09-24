package com.qwer654.wechatarchive

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private var incomingText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readShare(intent)
        setContent {
            ArchiveTheme {
                ArchiveApp(this, incomingText)
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

private enum class AppTab(val title: String, val mark: String) {
    CAPTURE("采集", "✦"),
    ARCHIVE("归档", "库"),
    SYNC("同步", "↻"),
    ABOUT("关于", "ⓘ")
}

private data class ArchiveSnapshot(
    val records: List<ArticleRecord>,
    val invalidIds: Set<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchiveApp(activity: MainActivity, incomingText: String) {
    val repository = remember { ArchiveRepository(activity.applicationContext) }
    val collector = remember { ArticleCollector() }
    val historyClient = remember { WeReadHistoryClient(activity.applicationContext) }
    val workflow = remember { ArchiveWorkflow(repository, collector, historyClient) }
    val scope = rememberCoroutineScope()

    var selectedTab by rememberSaveable { mutableStateOf(AppTab.CAPTURE) }
    var input by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf(LocalDate.now().minusMonths(12).toString()) }
    var end by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var status by remember { mutableStateOf("准备就绪。可粘贴文章链接，或从微信直接分享给本 App。") }
    var captureWorking by remember { mutableStateOf(false) }
    var syncWorking by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf<List<ArticleRecord>>(emptyList()) }
    var invalidIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var exportRecord by remember { mutableStateOf<ArticleRecord?>(null) }
    var credential by remember { mutableStateOf(historyClient.savedCredential()) }
    var loginSession by remember { mutableStateOf<LoginSession?>(null) }

    val packageInfo = remember { activity.packageManager.getPackageInfo(activity.packageName, 0) }
    val appVersionName = packageInfo.versionName ?: "unknown"
    val appVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }

    suspend fun refreshArchive() {
        val snapshot = withContext(Dispatchers.IO) {
            val list = repository.listAll()
            ArchiveSnapshot(
                records = list,
                invalidIds = list.asSequence()
                    .filterNot { repository.isUsable(it) }
                    .map { it.id }
                    .toSet()
            )
        }
        records = snapshot.records
        invalidIds = snapshot.invalidIds
    }

    LaunchedEffect(Unit) {
        refreshArchive()
    }

    LaunchedEffect(incomingText) {
        if (incomingText.isNotBlank()) {
            input = incomingText
            selectedTab = AppTab.CAPTURE
        }
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val record = exportRecord
        if (uri != null && record != null) {
            runCatching {
                activity.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(repository.readMarkdown(record).toByteArray(Charsets.UTF_8))
                } ?: error("无法打开目标文件")
            }.onSuccess {
                Toast.makeText(activity, "Markdown 已导出", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(activity, "导出失败：" + (it.message ?: "未知错误"), Toast.LENGTH_LONG).show()
            }
        }
        exportRecord = null
    }

    val browserCapture = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                refreshArchive()
                status = "浏览器自动采集完成，归档列表已刷新。"
                selectedTab = AppTab.ARCHIVE
            }
        }
    }

    fun openBrowserCapture(url: String) {
        browserCapture.launch(
            Intent(activity, WebViewCaptureActivity::class.java)
                .putExtra(WebViewCaptureActivity.EXTRA_URL, url)
        )
    }

    fun setRange(months: Long) {
        end = LocalDate.now().toString()
        start = LocalDate.now().minusMonths(months).toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("公众号典藏", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            selectedTab.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Text(tab.mark, fontWeight = FontWeight.Bold) },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { inner ->
        when (selectedTab) {
            AppTab.CAPTURE -> CaptureScreen(
                modifier = Modifier.padding(inner),
                input = input,
                onInputChange = { input = it },
                filter = filter,
                onFilterChange = { filter = it },
                start = start,
                onStartChange = { start = it },
                end = end,
                onEndChange = { end = it },
                working = captureWorking,
                status = status,
                localCount = records.size,
                invalidCount = invalidIds.size,
                onRange = ::setRange,
                onDiscover = {
                    val url = webUrls(input).firstOrNull()
                    if (url == null) {
                        status = "没有找到可用网址。"
                    } else {
                        scope.launch {
                            captureWorking = true
                            status = "正在发现页面中的公众号文章链接…"
                            runCatching {
                                withContext(Dispatchers.IO) { collector.discover(url) }
                            }.onSuccess { found ->
                                if (found.isEmpty()) {
                                    status = "当前页面没有发现可直接访问的公众号文章链接。"
                                } else {
                                    input = found.joinToString("\n")
                                    status = "已发现 " + found.size + " 个文章链接。"
                                }
                            }.onFailure {
                                status = "发现文章失败：" + (it.message ?: "未知错误")
                            }
                            captureWorking = false
                        }
                    }
                },
                onCapture = {
                    val from = runCatching { LocalDate.parse(start.trim()) }.getOrNull()
                    val to = runCatching { LocalDate.parse(end.trim()) }.getOrNull()
                    val urls = wechatUrls(input)
                    when {
                        from == null || to == null || from.isAfter(to) ->
                            status = "日期格式不正确，请使用 YYYY-MM-DD。"
                        urls.isEmpty() ->
                            status = "没有找到微信公众号文章链接。"
                        else -> scope.launch {
                            captureWorking = true
                            val result = workflow.captureBatch(
                                urls = urls,
                                from = from,
                                to = to,
                                filter = filter
                            ) { message -> status = message }
                            refreshArchive()
                            captureWorking = false

                            val blocked = result.verificationUrls.distinct()
                            status = "采集完成：新增 " + result.added +
                                "，已有 " + result.existed +
                                "，筛选跳过 " + result.filtered +
                                "，需浏览器处理 " + blocked.size +
                                "，其他失败 " + result.failed + "。"

                            if (blocked.size == 1 && urls.size == 1) {
                                status = "微信要求页面验证，已切换浏览器模式；验证完成后会自动采集。"
                                openBrowserCapture(blocked.first())
                            } else if (blocked.isNotEmpty()) {
                                input = blocked.joinToString("\n")
                                status += " 需浏览器处理的链接已放回输入框。"
                            }
                        }
                    }
                },
                onBrowserCapture = {
                    val url = wechatUrls(input).firstOrNull()
                    if (url == null) status = "请先输入一篇微信公众号文章链接。"
                    else openBrowserCapture(url)
                }
            )

            AppTab.ARCHIVE -> ArchiveScreen(
                modifier = Modifier.padding(inner),
                records = records,
                invalidIds = invalidIds,
                onExport = { record ->
                    exportRecord = record
                    exporter.launch(fileName(record.publishDate + "_" + record.title + ".md"))
                },
                onRecapture = { record -> openBrowserCapture(record.url) },
                onRefresh = {
                    scope.launch {
                        refreshArchive()
                        status = "归档列表已刷新。"
                    }
                }
            )

            AppTab.SYNC -> SyncScreen(
                modifier = Modifier.padding(inner),
                input = input,
                onInputChange = { input = it },
                filter = filter,
                onFilterChange = { filter = it },
                start = start,
                onStartChange = { start = it },
                end = end,
                onEndChange = { end = it },
                credential = credential,
                loginSession = loginSession,
                working = syncWorking,
                status = status,
                onCreateLogin = {
                    scope.launch {
                        syncWorking = true
                        status = "正在生成微信读书登录二维码…"
                        runCatching {
                            withContext(Dispatchers.IO) { historyClient.createLoginSession() }
                        }.onSuccess {
                            loginSession = it
                            status = "二维码已生成，请扫码后确认登录。"
                        }.onFailure {
                            status = "二维码生成失败：" + (it.message ?: "未知错误")
                        }
                        syncWorking = false
                    }
                },
                onCompleteLogin = {
                    val session = loginSession
                    if (session != null) {
                        scope.launch {
                            syncWorking = true
                            status = "正在确认扫码登录…"
                            runCatching {
                                withContext(Dispatchers.IO) { historyClient.completeLogin(session.uuid) }
                            }.onSuccess {
                                credential = it
                                loginSession = null
                                status = "历史同步登录成功：" + it.username.ifBlank { it.vid }
                            }.onFailure {
                                status = "登录尚未完成或失败：" + (it.message ?: "未知错误")
                            }
                            syncWorking = false
                        }
                    }
                },
                onLogout = {
                    historyClient.clearCredential()
                    credential = null
                    loginSession = null
                    status = "已清除本机历史同步登录信息。"
                },
                onSync = {
                    val cred = credential
                    val sample = wechatUrls(input).firstOrNull()
                    val from = runCatching { LocalDate.parse(start.trim()) }.getOrNull()
                    val to = runCatching { LocalDate.parse(end.trim()) }.getOrNull()
                    when {
                        cred == null -> status = "请先扫码登录历史同步服务。"
                        sample == null -> status = "请先输入该公众号任意一篇文章链接。"
                        from == null || to == null || from.isAfter(to) ->
                            status = "日期格式不正确，请使用 YYYY-MM-DD。"
                        else -> scope.launch {
                            syncWorking = true
                            val result = workflow.syncHistory(
                                sampleUrl = sample,
                                from = from,
                                to = to,
                                filter = filter,
                                credential = cred
                            ) { message -> status = message }
                            refreshArchive()
                            syncWorking = false

                            val blocked = result.verificationUrls.distinct()
                            if (blocked.isNotEmpty()) input = blocked.joinToString("\n")
                            status = "历史同步完成：" + result.accountName +
                                "；新增 " + result.added +
                                "，已有 " + result.existed +
                                "，筛选跳过 " + result.filtered +
                                "，需浏览器采集 " + blocked.size +
                                "，其他失败 " + result.failed + "。" +
                                if (blocked.isNotEmpty()) " 验证链接已放回输入框。" else ""
                        }
                    }
                }
            )

            AppTab.ABOUT -> AboutScreen(
                modifier = Modifier.padding(inner),
                versionName = appVersionName,
                versionCode = appVersionCode,
                onOpenRepository = {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/qwer654/WeChatArticleArchive"))
                    )
                }
            )
        }
    }
}

@Composable
private fun CaptureScreen(
    modifier: Modifier,
    input: String,
    onInputChange: (String) -> Unit,
    filter: String,
    onFilterChange: (String) -> Unit,
    start: String,
    onStartChange: (String) -> Unit,
    end: String,
    onEndChange: (String) -> Unit,
    working: Boolean,
    status: String,
    localCount: Int,
    invalidCount: Int,
    onRange: (Long) -> Unit,
    onDiscover: () -> Unit,
    onCapture: () -> Unit,
    onBrowserCapture: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "把公众号文章变成自己的 Markdown 资料库",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text("本地索引去重 · 验证页自动切换浏览器 · 单篇独立导出")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatPill("已归档", localCount.toString())
                        StatPill("需重采", invalidCount.toString())
                    }
                }
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("来源", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("文章链接 / 多个链接 / 合集页面") },
                        minLines = 4,
                        maxLines = 8,
                        supportingText = { Text("多个链接可一行一个，也可以直接从微信“分享”到本 App。") }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onDiscover, enabled = !working, modifier = Modifier.weight(1f)) {
                            Text("发现文章")
                        }
                        OutlinedButton(onClick = onBrowserCapture, enabled = !working, modifier = Modifier.weight(1f)) {
                            Text("浏览器采集")
                        }
                    }
                }
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("筛选条件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1L, 3L, 6L, 12L).forEach { months ->
                            FilterChip(
                                selected = false,
                                onClick = { onRange(months) },
                                label = { Text(months.toString() + " 个月") }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = start,
                            onValueChange = onStartChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("开始日期") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = end,
                            onValueChange = onEndChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("结束日期") },
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = filter,
                        onValueChange = onFilterChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("公众号或作者（可选）") },
                        singleLine = true
                    )
                }
            }
        }

        item { StatusCard(status, working) }

        item {
            Button(onClick = onCapture, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                if (working) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在处理")
                } else {
                    Text("开始采集")
                }
            }
        }
    }
}

@Composable
private fun ArchiveScreen(
    modifier: Modifier,
    records: List<ArticleRecord>,
    invalidIds: Set<String>,
    onExport: (ArticleRecord) -> Unit,
    onRecapture: (ArticleRecord) -> Unit,
    onRefresh: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(records, query) {
        val q = query.trim()
        if (q.isBlank()) records else records.filter {
            it.title.contains(q, true) ||
                it.account.contains(q, true) ||
                it.author.contains(q, true) ||
                it.publishDate.contains(q)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本地归档", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        records.size.toString() + " 篇文章 · " + invalidIds.size + " 篇需要重新采集",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onRefresh) { Text("刷新") }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索标题 / 公众号 / 作者 / 日期") },
                singleLine = true
            )
        }

        if (filtered.isEmpty()) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("暂无匹配文章", fontWeight = FontWeight.SemiBold)
                        Text("采集后的文章会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(filtered, key = { it.id }) { record ->
                ArticleCard(
                    record = record,
                    usable = record.id !in invalidIds,
                    onExport = { onExport(record) },
                    onRecapture = { onRecapture(record) }
                )
            }
        }
    }
}

@Composable
private fun ArticleCard(
    record: ArticleRecord,
    usable: Boolean,
    onExport: () -> Unit,
    onRecapture: () -> Unit
) {
    OutlinedCard(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                record.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOf(record.account, record.author, record.publishDate)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { "元数据待补全" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                record.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!usable) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "这条记录是异常页或内容不完整，建议重新采集。",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onExport, enabled = usable, modifier = Modifier.weight(1f)) {
                    Text("导出 .md")
                }
                if (!usable) {
                    OutlinedButton(onClick = onRecapture, modifier = Modifier.weight(1f)) {
                        Text("自动重采")
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncScreen(
    modifier: Modifier,
    input: String,
    onInputChange: (String) -> Unit,
    filter: String,
    onFilterChange: (String) -> Unit,
    start: String,
    onStartChange: (String) -> Unit,
    end: String,
    onEndChange: (String) -> Unit,
    credential: WeReadCredential?,
    loginSession: LoginSession?,
    working: Boolean,
    status: String,
    onCreateLogin: () -> Unit,
    onCompleteLogin: () -> Unit,
    onLogout: () -> Unit,
    onSync: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("公众号历史增量同步", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("先读取历史索引，再只下载本地缺失文章；遇到验证页会交给浏览器模式。")
                }
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("登录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (credential == null) {
                        Button(onClick = onCreateLogin, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                            Text("生成微信读书登录二维码")
                        }
                        if (loginSession != null) {
                            val bitmap = remember(loginSession.scanUrl) { makeQrBitmap(loginSession.scanUrl) }
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "微信读书登录二维码",
                                modifier = Modifier.fillMaxWidth().height(260.dp),
                                contentScale = ContentScale.Fit
                            )
                            Button(onClick = onCompleteLogin, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                                Text("扫码完成，确认登录")
                            }
                        }
                    } else {
                        Text("已登录：" + credential.username.ifBlank { credential.vid })
                        OutlinedButton(onClick = onLogout, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                            Text("退出历史登录")
                        }
                    }
                }
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("同步范围", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("该公众号任意一篇文章链接") },
                        minLines = 2,
                        maxLines = 5
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = start,
                            onValueChange = onStartChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("开始日期") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = end,
                            onValueChange = onEndChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("结束日期") },
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = filter,
                        onValueChange = onFilterChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("作者筛选（可选）") },
                        singleLine = true
                    )
                }
            }
        }

        item { StatusCard(status, working) }

        item {
            Button(
                onClick = onSync,
                enabled = credential != null && !working,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (working) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在同步")
                } else {
                    Text("开始增量同步")
                }
            }
        }

        item {
            Text(
                "历史同步使用第三方兼容服务，可不启用。登录令牌仅保存在本机；遇到 401 / 429 会停止，不尝试绕过平台限制。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AboutScreen(
    modifier: Modifier,
    versionName: String,
    versionCode: Long,
    onOpenRepository: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("公众号典藏", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Android 微信公众号文章本地 Markdown 归档工具")
                    Text("v" + versionName + " · versionCode " + versionCode, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AboutLine("应用 ID", "com.qwer654.wechatarchive")
                    HorizontalDivider()
                    AboutLine("签名通道", "Stable Dev")
                    HorizontalDivider()
                    AboutLine("仓库地址", "https://github.com/qwer654/WeChatArticleArchive")
                    OutlinedButton(onClick = onOpenRepository, modifier = Modifier.fillMaxWidth()) {
                        Text("打开 GitHub 仓库")
                    }
                }
            }
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("数据原则", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("• 文章以独立 Markdown 保存，数据库只管理索引与同步状态。")
                    Text("• 已有有效文章不会重复下载。")
                    Text("• 微信返回验证页时不保存错误正文，正常验证后自动采集。")
                    Text("• 不伪装微信客户端，不窃取 Cookie，不绕过平台风控。")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: String, working: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatPill(label: String, value: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun webUrls(text: String): List<String> =
    Regex("""https?://[^\s<>"']+""").findAll(text)
        .map { it.value.trimEnd(')', '）', '。', ',', '，', ';', '；') }
        .distinct()
        .toList()

private fun wechatUrls(text: String): List<String> =
    webUrls(text).filter {
        runCatching { java.net.URI(it).host.equals("mp.weixin.qq.com", true) }.getOrDefault(false)
    }

private fun fileName(value: String): String =
    value.replace(Regex("""[\\/:*?"<>|\r\n]+"""), "_")
        .trim()
        .take(120)
        .ifBlank { "article.md" }
