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
    private val db = Db(context)
    private val root = File(context.filesDir, "archive").apply { mkdirs() }

    fun findByUrl(url: String): ArticleRecord? = db.find(canonical(url))
    fun listAll(): List<ArticleRecord> = db.all()
    fun readMarkdown(record: ArticleRecord): String = File(record.path).readText(Charsets.UTF_8)

    fun isUsable(record: ArticleRecord): Boolean {
        val file = File(record.path)
        if (!file.exists()) return false
        if (record.title.isBlank() || record.title == "未命名文章") return false
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
        return !isVerificationPage(text)
    }

    fun save(article: ParsedArticle, markdown: String) {
        val date = article.publishDate?.toString().orEmpty()
        val year = article.publishDate?.year?.toString() ?: "unknown"
        val account = safe(article.account.ifBlank { "未知公众号" })
        val dir = File(File(root, account), year).apply { mkdirs() }
        val base = date.ifBlank { "unknown" } + "_" + article.title + "_" + article.id.take(8)
        val file = File(dir, safe(base) + ".md")
        file.writeText(markdown, Charsets.UTF_8)
        db.upsert(
            ArticleRecord(
                article.id, article.url, article.title, article.account, article.author,
                date, file.absolutePath, article.hash, System.currentTimeMillis()
            )
        )
    }

    private fun safe(value: String): String =
        value.replace(Regex("""[\\/:*?"<>|\r\n]+"""), "_").trim().trim('.').take(90).ifBlank { "untitled" }
}

private class Db(context: Context) : SQLiteOpenHelper(context, "archive.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE articles(" +
                "id TEXT PRIMARY KEY,url TEXT NOT NULL UNIQUE,title TEXT NOT NULL," +
                "account TEXT NOT NULL,author TEXT NOT NULL,publish_date TEXT NOT NULL," +
                "path TEXT NOT NULL,hash TEXT NOT NULL,collected_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_date ON articles(publish_date)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun upsert(r: ArticleRecord) {
        writableDatabase.insertWithOnConflict(
            "articles", null,
            ContentValues().apply {
                put("id", r.id); put("url", r.url); put("title", r.title); put("account", r.account)
                put("author", r.author); put("publish_date", r.publishDate); put("path", r.path)
                put("hash", r.hash); put("collected_at", r.collectedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun find(url: String): ArticleRecord? {
        readableDatabase.query("articles", null, "url=?", arrayOf(url), null, null, null, "1").use {
            return if (it.moveToFirst()) it.record() else null
        }
    }

    fun all(): List<ArticleRecord> {
        val result = mutableListOf<ArticleRecord>()
        readableDatabase.query(
            "articles", null, null, null, null, null,
            "CASE WHEN publish_date='' THEN 1 ELSE 0 END,publish_date DESC,collected_at DESC"
        ).use { c ->
            while (c.moveToNext()) result += c.record()
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
            doc.selectFirst("meta[property=og:title]")?.attr("content"),
            doc.title()
        ).ifBlank { "未命名文章" }

        val account = first(
            doc.selectFirst("#js_name")?.text(),
            doc.selectFirst(".profile_nickname")?.text(),
            doc.selectFirst(".rich_media_meta_nickname")?.text(),
            regex(html, """var\s+nickname\s*=\s*["']([^"']+)["']""")
        )

        val author = first(
            doc.selectFirst("#js_author_name")?.text(),
            doc.selectFirst("meta[name=author]")?.attr("content")
        )

        val publishDate = parseDate(doc.selectFirst("#publish_time")?.text())
            ?: regex(html, """var\s+ct\s*=\s*["']?(\d{10,13})""")?.toLongOrNull()?.let {
                val seconds = if (it > 99999999999L) it / 1000L else it
                Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).toLocalDate()
            }

        val root = (doc.selectFirst("#js_content") ?: doc.selectFirst(".rich_media_content") ?: doc.selectFirst("article") ?: doc.body()).clone()
        root.select("script,style,noscript").remove()
        root.select("img").forEach {
            val src = first(it.attr("data-src"), it.attr("src"))
            if (src.isNotBlank()) it.attr("src", if (src.startsWith("//")) "https:" + src else src)
        }
        root.select("a[href]").forEach {
            val abs = it.attr("abs:href")
            if (abs.isNotBlank()) it.attr("href", abs)
        }

        val body = Md.convert(root).trim()
        if (title == "未命名文章" && (body.isBlank() || isVerificationPage(body))) {
            throw VerificationRequiredException(url)
        }
        val hash = sha(title + "\n" + account + "\n" + author + "\n" + body)
        return ParsedArticle(sha(url), url, title, account, author, publishDate, body, hash)
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
            "---\n\n# " + a.title + "\n\n" + a.body + "\n"
    }

    fun discover(page: String): List<String> {
        val base = canonical(page)
        val html = get(base)
        val doc = Jsoup.parse(html, base)
        val out = linkedSetOf<String>()
        doc.select("a[href],[data-link],[data-url]").forEach { e ->
            listOf(e.attr("abs:href"), e.attr("href"), e.attr("data-link"), e.attr("data-url")).forEach { raw ->
                val u = normalize(raw)
                if (u != null && isWechat(u)) out += u
            }
        }
        val plain = html.replace("\\/", "/").replace("&amp;", "&")
        Regex("""https?://mp\.weixin\.qq\.com/[^\s"'<>\\]+""").findAll(plain).forEach {
            val u = normalize(it.value)
            if (u != null && isWechat(u)) out += u
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

    private fun isWechat(url: String): Boolean = runCatching {
        URI(url).host.equals("mp.weixin.qq.com", true)
    }.getOrDefault(false)

    private fun first(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

    private fun regex(text: String, p: String): String? =
        Regex(p).find(text)?.groupValues?.getOrNull(1)?.trim()

    private fun parseDate(text: String?): LocalDate? {
        val v = text.orEmpty()
        val formats = listOf(
            Regex("""\d{4}-\d{1,2}-\d{1,2}""") to DateTimeFormatter.ofPattern("yyyy-M-d"),
            Regex("""\d{4}/\d{1,2}/\d{1,2}""") to DateTimeFormatter.ofPattern("yyyy/M/d"),
            Regex("""\d{4}年\d{1,2}月\d{1,2}日""") to DateTimeFormatter.ofPattern("yyyy年M月d日")
        )
        for ((r, f) in formats) {
            val m = r.find(v)?.value ?: continue
            runCatching { LocalDate.parse(m, f) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun yaml(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ")
}

private object Md {
    fun convert(root: Element): String = children(root).replace(Regex("""\n{3,}"""), "\n\n")

    private fun node(n: Node): String = when (n) {
        is TextNode -> n.text()
        is Element -> {
            val inner = children(n)
            when (n.normalName()) {
                "script", "style", "noscript" -> ""
                "br" -> "\n"
                "p", "div", "section", "article", "figure" -> inner.trim() + "\n\n"
                "h1" -> "# " + inner.trim() + "\n\n"
                "h2" -> "## " + inner.trim() + "\n\n"
                "h3" -> "### " + inner.trim() + "\n\n"
                "strong", "b" -> "**" + inner.trim() + "**"
                "em", "i" -> "*" + inner.trim() + "*"
                "a" -> {
                    val href = n.attr("href").trim()
                    val label = inner.trim().ifBlank { href }
                    if (href.isBlank()) label else "[" + label + "](" + href + ")"
                }
                "img" -> {
                    val src = n.attr("src").trim()
                    val alt = n.attr("alt").replace("[", "").replace("]", "")
                    if (src.isBlank()) "" else "\n![" + alt + "](" + src + ")\n"
                }
                "blockquote" -> inner.trim().lines().joinToString("\n") { "> " + it } + "\n\n"
                "li" -> "- " + inner.trim() + "\n"
                "ul", "ol" -> "\n" + inner.trimEnd() + "\n\n"
                "hr" -> "\n---\n\n"
                else -> inner
            }
        }
        else -> ""
    }

    private fun children(n: Node): String = n.childNodes().joinToString("") { node(it) }
}

fun isVerificationPage(text: String): Boolean {
    val normalized = text.replace("\u00a0", " ")
    return normalized.contains("当前环境异常") ||
        normalized.contains("环境异常") && normalized.contains("完成验证") ||
        normalized.contains("去验证") && normalized.contains("继续访问") ||
        normalized.contains("访问过于频繁") ||
        normalized.contains("请完成验证后继续访问")
}

fun canonical(value: String): String {
    val u = URI(value.trim())
    return URI(u.scheme?.lowercase(), u.userInfo, u.host?.lowercase(), u.port, u.path, u.query, null).toString()
}

private fun sha(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
