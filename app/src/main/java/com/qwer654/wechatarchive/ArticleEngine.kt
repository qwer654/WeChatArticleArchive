package com.qwer654.wechatarchive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class ParsedArticle(
    val id: String,
    val url: String,
    val title: String,
    val account: String,
    val author: String,
    val publishDate: LocalDate?,
    val body: String,
    val hash: String
)

class VerificationRequiredException(val articleUrl: String) :
    IllegalStateException("微信返回了环境验证页面，请使用浏览器模式完成验证后采集")

data class ArticleRecord(
    val id: String,
    val url: String,
    val title: String,
    val account: String,
    val author: String,
    val publishDate: String,
    val path: String,
    val hash: String,
    val collectedAt: Long
)

class ArchiveRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = Db(appContext)
    private val root = File(appContext.filesDir, "archive").apply { mkdirs() }
    private val exportStorage = ExportStorage(appContext)
    private val mediaClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    fun findByUrl(url: String): ArticleRecord? = db.find(canonical(url))
    fun listAll(): List<ArticleRecord> = db.all()
    fun readMarkdown(record: ArticleRecord): String = File(record.path).readText(Charsets.UTF_8)
    fun exportArticle(record: ArticleRecord): Boolean = exportStorage.exportArticle(record)

    fun isUsable(record: ArticleRecord): Boolean {
        val file = File(record.path)
        if (!file.exists()) return false
        if (record.title.isBlank() || record.title == "未命名文章") return false
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
        return text.isNotBlank() && !isVerificationPage(text)
    }

    fun save(article: ParsedArticle, markdown: String): ArticleRecord {
        val previous = db.find(article.url)
        val date = article.publishDate?.toString().orEmpty()
        val year = article.publishDate?.year?.toString() ?: "unknown"
        val accountName = safe(article.account.ifBlank { "未知公众号" })
        val yearDir = File(File(root, accountName), year).apply { mkdirs() }
        val baseName = date.ifBlank { "unknown" } + "_" + article.title + "_" + article.id.take(8)
        val markdownFile = File(yearDir, safe(baseName) + ".md")

        val assetFolderName = safe(article.title + "_" + article.id.take(8))
        val assetDir = File(File(yearDir, "assets"), assetFolderName).apply { mkdirs() }
        val localizedMarkdown = localizeImages(
            article = article,
            markdown = markdown,
            assetDir = assetDir,
            relativePrefix = "assets/" + assetFolderName
        )

        markdownFile.writeText(localizedMarkdown, Charsets.UTF_8)

        val record = ArticleRecord(
            id = article.id,
            url = article.url,
            title = article.title,
            account = article.account,
            author = article.author,
            publishDate = date,
            path = markdownFile.absolutePath,
            hash = article.hash,
            collectedAt = System.currentTimeMillis()
        )
        db.upsert(record)

        if (previous != null && previous.path != markdownFile.absolutePath) {
            runCatching { File(previous.path).delete() }
        }

        if (exportStorage.selectedTreeUri() != null) {
            runCatching { exportStorage.exportArticle(record) }
        }
        return record
    }

    private fun localizeImages(
        article: ParsedArticle,
        markdown: String,
        assetDir: File,
        relativePrefix: String
    ): String {
        return REMOTE_IMAGE.replace(markdown) { match ->
            val alt = match.groupValues[1]
            val url = match.groupValues[2].replace("&amp;", "&").trim()
            val extension = imageExtension(url)
            val fileName = "img_" + sha(url).take(12) + "." + extension
            val target = File(assetDir, fileName)

            if (!target.exists() || target.length() == 0L) {
                runCatching { downloadImage(url, article.url, target) }
            }

            if (target.exists() && target.length() > 0L) {
                "![" + alt + "](" + relativePrefix + "/" + fileName + ")"
            } else {
                match.value
            }
        }
    }

    private fun downloadImage(url: String, referer: String, target: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) WeChatArticleArchive")
            .header("Referer", referer)
            .build()

        mediaClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("图片 HTTP " + response.code)
            val body = response.body ?: error("图片响应为空")
            val temp = File(target.parentFile, target.name + ".part")
            body.byteStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() <= 0L) {
                temp.delete()
                error("图片内容为空")
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }

    private fun imageExtension(url: String): String {
        val format = Regex("""(?:[?&](?:wx_fmt|tp)=)(jpeg|jpg|png|gif|webp)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.lowercase()
        if (!format.isNullOrBlank()) return if (format == "jpeg") "jpg" else format

        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpeg" -> "jpg"
            "jpg", "png", "gif", "webp" -> ext
            else -> "jpg"
        }
    }

    private fun safe(value: String): String =
        value.replace(Regex("""[\\/:*?"<>|\r\n]+"""), "_")
            .trim()
            .trim('.')
            .take(90)
            .ifBlank { "untitled" }

    companion object {
        private val REMOTE_IMAGE = Regex("""!\[([^\]]*)]\((https?://[^)]+)\)""")
    }
}

private class Db(context: Context) : SQLiteOpenHelper(context, "archive.db", null, 1) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE articles(" +
                "id TEXT PRIMARY KEY,url TEXT NOT NULL UNIQUE,title TEXT NOT NULL," +
                "account TEXT NOT NULL,author TEXT NOT NULL,publish_date TEXT NOT NULL," +
                "path TEXT NOT NULL,hash TEXT NOT NULL,collected_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_date ON articles(publish_date)")
        db.execSQL("CREATE INDEX idx_account ON articles(account)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun upsert(r: ArticleRecord) {
        writableDatabase.insertWithOnConflict(
            "articles",
            null,
            ContentValues().apply {
                put("id", r.id)
                put("url", r.url)
                put("title", r.title)
                put("account", r.account)
                put("author", r.author)
                put("publish_date", r.publishDate)
                put("path", r.path)
                put("hash", r.hash)
                put("collected_at", r.collectedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun find(url: String): ArticleRecord? {
        readableDatabase.query(
            "articles", null, "url=?", arrayOf(url), null, null, null, "1"
        ).use {
            return if (it.moveToFirst()) it.record() else null
        }
    }

    fun all(): List<ArticleRecord> {
        val result = mutableListOf<ArticleRecord>()
        readableDatabase.query(
            "articles",
            null,
            null,
            null,
            null,
            null,
            "CASE WHEN publish_date='' THEN 1 ELSE 0 END,publish_date DESC,collected_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.record()
        }
        return result
    }

    private fun android.database.Cursor.record() = ArticleRecord(
        getString(getColumnIndexOrThrow("id")),
        getString(getColumnIndexOrThrow("url")),
        getString(getColumnIndexOrThrow("title")),
        getString(getColumnIndexOrThrow("account")),
        getString(getColumnIndexOrThrow("author")),
        getString(getColumnIndexOrThrow("publish_date")),
        getString(getColumnIndexOrThrow("path")),
        getString(getColumnIndexOrThrow("hash")),
        getLong(getColumnIndexOrThrow("collected_at"))
    )
}

class ArticleCollector {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun fetch(input: String): ParsedArticle {
        val url = canonical(input)
        return parseHtml(url, get(url))
    }

    fun parseHtml(input: String, html: String): ParsedArticle {
        val url = canonical(input)
        val doc = Jsoup.parse(html, url)
        if (isVerificationPage(doc.text()) || isVerificationPage(html)) {
            throw VerificationRequiredException(url)
        }

        val title = first(
            doc.selectFirst("#activity-name")?.text(),
            doc.selectFirst("h1.rich_media_title")?.text(),
            doc.selectFirst(".rich_media_title")?.text(),
            doc.selectFirst("meta[property=og:title]")?.attr("content"),
            doc.title()
        ).ifBlank { "未命名文章" }

        val account = first(
            doc.selectFirst("#js_name")?.text(),
            doc.selectFirst("#js_profile_qrcode .profile_nickname")?.text(),
            doc.selectFirst(".profile_nickname")?.text(),
            doc.selectFirst(".rich_media_meta_nickname")?.text(),
            doc.selectFirst(".wx_follow_nickname")?.text(),
            regex(html, """var\s+nickname\s*=\s*["']([^"']+)["']""")
        )

        val metaTexts = doc.select(".rich_media_meta_text")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val author = first(
            doc.selectFirst("#js_author_name")?.text(),
            doc.selectFirst("meta[name=author]")?.attr("content"),
            regex(html, """var\s+author\s*=\s*["']([^"']+)["']"""),
            metaTexts.firstOrNull { it != account && !looksLikeDate(it) && it.length <= 60 }
        )

        val publishDate = parseDate(doc.selectFirst("#publish_time")?.text())
            ?: metaTexts.firstNotNullOfOrNull { parseDate(it) }
            ?: regex(
                html,
                """var\s+(?:ct|publish_time|ori_create_time)\s*=\s*["']?(\d{10,13})"""
            )?.toLongOrNull()?.let {
                val seconds = if (it > 99_999_999_999L) it / 1000L else it
                Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).toLocalDate()
            }

        val root = (
            doc.selectFirst("#js_content")
                ?: doc.selectFirst(".rich_media_content")
                ?: doc.selectFirst("article")
                ?: doc.body()
            ).clone()

        root.select("script,style,noscript").remove()

        root.select("img").forEach { image ->
            val src = first(image.attr("data-src"), image.attr("src"))
            if (src.isNotBlank()) {
                image.attr("src", normalizeResource(src))
            }
        }

        root.select("a[href]").forEach { link ->
            val absolute = link.attr("abs:href")
            if (absolute.isNotBlank()) link.attr("href", absolute)
        }

        root.select("video,iframe,mpvideo").forEach { media ->
            val childSource = media.selectFirst("source[src]")?.attr("src")
            val videoUrl = first(
                media.attr("data-src"),
                media.attr("src"),
                media.attr("data-url"),
                media.attr("href"),
                childSource,
                url
            )
            media.attr("data-archive-video-url", normalizeResource(videoUrl))
        }

        val body = Md.convert(root).trim()
        if (title == "未命名文章" && (body.isBlank() || isVerificationPage(body))) {
            throw VerificationRequiredException(url)
        }

        val hash = sha(title + "\n" + account + "\n" + author + "\n" + body)
        return ParsedArticle(
            id = sha(url),
            url = url,
            title = title,
            account = account,
            author = author,
            publishDate = publishDate,
            body = body,
            hash = hash
        )
    }

    fun markdown(a: ParsedArticle): String {
        val date = a.publishDate?.toString().orEmpty()
        return "---\n" +
            "title: \"" + yaml(a.title) + "\"\n" +
            "account: \"" + yaml(a.account) + "\"\n" +
            "author: \"" + yaml(a.author) + "\"\n" +
            "published: \"" + date + "\"\n" +
            "source: \"微信公众号\"\n" +
            "url: \"" + yaml(a.url) + "\"\n" +
            "article_id: \"" + a.id + "\"\n" +
            "content_hash: \"" + a.hash + "\"\n" +
            "---\n\n" +
            "# " + a.title + "\n\n" +
            a.body + "\n\n" +
            "## 采集来源\n\n" +
            "- 原文链接：[" + a.url + "](" + a.url + ")\n"
    }

    fun discover(page: String): List<String> {
        val base = canonical(page)
        val html = get(base)
        val doc = Jsoup.parse(html, base)
        val out = linkedSetOf<String>()

        doc.select("a[href],[data-link],[data-url]").forEach { element ->
            listOf(
                element.attr("abs:href"),
                element.attr("href"),
                element.attr("data-link"),
                element.attr("data-url")
            ).forEach { raw ->
                val normalized = normalize(raw)
                if (normalized != null && isWechat(normalized)) out += normalized
            }
        }

        val plain = html.replace("\\/", "/").replace("&amp;", "&")
        Regex("""https?://mp\.weixin\.qq\.com/[^\s"'<>\\]+""")
            .findAll(plain)
            .forEach {
                val normalized = normalize(it.value)
                if (normalized != null && isWechat(normalized)) out += normalized
            }

        return out.toList()
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "WeChatArticleArchive/0.1 Android")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()

        client.newCall(request).execute().use {
            if (!it.isSuccessful) error("HTTP " + it.code)
            return it.body?.string() ?: error("空响应")
        }
    }

    private fun normalize(raw: String): String? {
        val value = raw.trim().replace("&amp;", "&")
        if (!value.startsWith("http://") && !value.startsWith("https://")) return null
        return runCatching { canonical(value) }.getOrNull()
    }

    private fun normalizeResource(raw: String): String {
        val value = raw.trim()
        return when {
            value.startsWith("//") -> "https:" + value
            else -> value
        }
    }

    private fun isWechat(url: String): Boolean = runCatching {
        URI(url).host.equals("mp.weixin.qq.com", true)
    }.getOrDefault(false)

    private fun first(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    private fun regex(text: String, pattern: String): String? =
        Regex(pattern).find(text)?.groupValues?.getOrNull(1)?.trim()

    private fun looksLikeDate(text: String): Boolean = parseDate(text) != null

    private fun parseDate(text: String?): LocalDate? {
        val value = text.orEmpty()
        val formats = listOf(
            Regex("""\d{4}-\d{1,2}-\d{1,2}""") to DateTimeFormatter.ofPattern("yyyy-M-d"),
            Regex("""\d{4}/\d{1,2}/\d{1,2}""") to DateTimeFormatter.ofPattern("yyyy/M/d"),
            Regex("""\d{4}年\d{1,2}月\d{1,2}日""") to DateTimeFormatter.ofPattern("yyyy年M月d日")
        )

        for ((regex, formatter) in formats) {
            val match = regex.find(value)?.value ?: continue
            runCatching { LocalDate.parse(match, formatter) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun yaml(value: String): String =
        value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", " ")
            .replace("\n", " ")
}

private object Md {
    fun convert(root: Element): String =
        children(root).replace(Regex("""\n{3,}"""), "\n\n")

    private fun node(node: Node): String = when (node) {
        is TextNode -> node.text()
        is Element -> {
            val inner = children(node)
            when (node.normalName()) {
                "script", "style", "noscript" -> ""
                "br" -> "\n"
                "p", "div", "section", "article", "figure", "figcaption" -> inner.trim() + "\n\n"
                "h1" -> "# " + inner.trim() + "\n\n"
                "h2" -> "## " + inner.trim() + "\n\n"
                "h3" -> "### " + inner.trim() + "\n\n"
                "h4" -> "#### " + inner.trim() + "\n\n"
                "h5" -> "##### " + inner.trim() + "\n\n"
                "h6" -> "###### " + inner.trim() + "\n\n"
                "strong", "b" -> if (inner.isBlank()) "" else "**" + inner.trim() + "**"
                "em", "i" -> if (inner.isBlank()) "" else "*" + inner.trim() + "*"
                "del", "s" -> if (inner.isBlank()) "" else "~~" + inner.trim() + "~~"
                "a" -> {
                    val href = node.attr("href").trim()
                    val label = inner.trim().ifBlank { href }
                    if (href.isBlank()) label else "[" + label + "](" + href + ")"
                }
                "img" -> {
                    val src = node.attr("src").trim()
                    val alt = node.attr("alt").trim().replace("[", "").replace("]", "")
                    if (src.isBlank()) "" else "\n![" + alt + "](" + src + ")\n"
                }
                "video", "iframe", "mpvideo" -> {
                    val link = node.attr("data-archive-video-url").trim()
                    if (link.isBlank()) {
                        "\n> 🎬 视频占位符：原文中包含视频，请打开采集来源查看。\n\n"
                    } else {
                        "\n> 🎬 视频占位符：[打开视频或原文](" + link + ")\n\n"
                    }
                }
                "blockquote" ->
                    inner.trim().lines().joinToString("\n") { "> " + it } + "\n\n"
                "li" -> "- " + inner.trim() + "\n"
                "ul", "ol" -> "\n" + inner.trimEnd() + "\n\n"
                "hr" -> "\n---\n\n"
                "table" -> "\n" + node.outerHtml() + "\n\n"
                else -> inner
            }
        }
        else -> ""
    }

    private fun children(node: Node): String =
        node.childNodes().joinToString("") { node(it) }
}

fun isVerificationPage(text: String): Boolean {
    val normalized = text.replace("\u00a0", " ")
    return normalized.contains("当前环境异常") ||
        (normalized.contains("环境异常") && normalized.contains("完成验证")) ||
        (normalized.contains("去验证") && normalized.contains("继续访问")) ||
        normalized.contains("访问过于频繁") ||
        normalized.contains("请完成验证后继续访问")
}

fun canonical(value: String): String {
    val uri = URI(value.trim())
    return URI(
        uri.scheme?.lowercase(),
        uri.userInfo,
        uri.host?.lowercase(),
        uri.port,
        uri.path,
        uri.query,
        null
    ).toString()
}

private fun sha(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
