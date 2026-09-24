package com.qwer654.wechatarchive

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

class ExportStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("export_storage", Context.MODE_PRIVATE)

    fun selectedTreeUri(): Uri? =
        prefs.getString(KEY_TREE_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun selectedLabel(): String {
        val uri = selectedTreeUri() ?: return "未选择"
        return DocumentFile.fromTreeUri(context, uri)?.name
            ?: uri.lastPathSegment
            ?: "已选择目录"
    }

    fun setTreeUri(uri: Uri, flags: Int) {
        val takeFlags = flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun clearTreeUri() {
        val uri = selectedTreeUri()
        if (uri != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    fun exportArticle(record: ArticleRecord): Boolean {
        val treeUri = selectedTreeUri() ?: return false
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        if (!root.canWrite()) return false

        val account = safePart(record.account.ifBlank { "未知公众号" })
        val year = record.publishDate.takeIf { it.length >= 4 }?.take(4) ?: "unknown"
        val accountDir = root.ensureDirectory(account) ?: return false
        val yearDir = accountDir.ensureDirectory(year) ?: return false

        val markdownFile = File(record.path)
        if (!markdownFile.exists()) return false
        val markdown = markdownFile.readText(Charsets.UTF_8)

        val targetMd = yearDir.replaceFile(markdownFile.name, "text/markdown") ?: return false
        context.contentResolver.openOutputStream(targetMd.uri, "w")?.use { output ->
            markdownFile.inputStream().use { input -> input.copyTo(output) }
        } ?: return false

        val refs = IMAGE_REF.findAll(markdown)
            .map { it.groupValues[1].trim() }
            .filter { it.startsWith("assets/") && !it.contains("..") }
            .distinct()
            .toList()

        refs.forEach { relative ->
            val source = File(markdownFile.parentFile, relative)
            if (!source.exists() || !source.isFile) return@forEach

            val parts = relative.split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return@forEach

            var dir = yearDir
            parts.dropLast(1).forEach { part ->
                dir = dir.ensureDirectory(safePart(part)) ?: return@forEach
            }

            val target = dir.replaceFile(parts.last(), mimeTypeFor(source.extension)) ?: return@forEach
            context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
        }
        return true
    }

    private fun DocumentFile.ensureDirectory(name: String): DocumentFile? =
        findFile(name)?.takeIf { it.isDirectory } ?: createDirectory(name)

    private fun DocumentFile.replaceFile(name: String, mime: String): DocumentFile? {
        findFile(name)?.delete()
        return createFile(mime, name)
    }

    private fun safePart(value: String): String =
        value.replace(Regex("""[\\/:*?"<>|\r\n]+"""), "_")
            .trim()
            .trim('.')
            .take(90)
            .ifBlank { "untitled" }

    private fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    companion object {
        private const val KEY_TREE_URI = "tree_uri"
        private val IMAGE_REF = Regex("""!\[[^\]]*]\((assets/[^)]+)\)""")
    }
}
