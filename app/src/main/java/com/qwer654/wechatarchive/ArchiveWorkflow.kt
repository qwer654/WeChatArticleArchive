package com.qwer654.wechatarchive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class BatchCaptureResult(
    val added: Int,
    val existed: Int,
    val filtered: Int,
    val verificationUrls: List<String>,
    val failed: Int
)

data class HistorySyncResult(
    val accountName: String,
    val added: Int,
    val existed: Int,
    val filtered: Int,
    val verificationUrls: List<String>,
    val failed: Int
)

class ArchiveWorkflow(
    private val repository: ArchiveRepository,
    private val collector: ArticleCollector,
    private val historyClient: WeReadHistoryClient
) {
    suspend fun captureBatch(
        urls: List<String>,
        from: LocalDate,
        to: LocalDate,
        filter: String,
        onProgress: (String) -> Unit
    ): BatchCaptureResult = withContext(Dispatchers.IO) {
        var added = 0
        var existed = 0
        var filtered = 0
        var failed = 0
        val verification = linkedSetOf<String>()
        val uniqueUrls = urls.distinct()

        uniqueUrls.forEachIndexed { index, url ->
            currentCoroutineContext().ensureActive()
            notify(onProgress, "正在处理 " + (index + 1) + "/" + uniqueUrls.size)

            val local = runCatching { repository.findByUrl(url) }.getOrNull()
            if (local != null && repository.isUsable(local)) {
                existed++
                return@forEachIndexed
            }

            val parsed = try {
                collector.fetch(url)
            } catch (t: Throwable) {
                if (t is VerificationRequiredException) verification += url else failed++
                return@forEachIndexed
            }

            val dateOk = parsed.publishDate?.let { !it.isBefore(from) && !it.isAfter(to) } ?: true
            val f = filter.trim()
            val authorOk = f.isBlank() || parsed.account.contains(f, true) || parsed.author.contains(f, true)

            if (!dateOk || !authorOk) {
                filtered++
                return@forEachIndexed
            }

            runCatching {
                repository.save(parsed, collector.markdown(parsed))
            }.onSuccess { added++ }.onFailure { failed++ }
        }

        BatchCaptureResult(added, existed, filtered, verification.toList(), failed)
    }

    suspend fun syncHistory(
        sampleUrl: String,
        from: LocalDate,
        to: LocalDate,
        filter: String,
        credential: WeReadCredential,
        onProgress: (String) -> Unit
    ): HistorySyncResult = withContext(Dispatchers.IO) {
        var added = 0
        var existed = 0
        var filtered = 0
        var failed = 0
        val verification = linkedSetOf<String>()
        var page = 1
        var consecutiveExisting = 0
        var stop = false

        val mp = historyClient.resolveAccount(sampleUrl, credential)
        notify(onProgress, "已识别公众号：" + mp.name + "，开始读取历史索引…")

        while (page <= 100 && !stop) {
            currentCoroutineContext().ensureActive()
            val history = historyClient.historyPage(mp.id, page, credential)
            if (history.isEmpty()) break

            var oldest: LocalDate? = null
            for (item in history) {
                currentCoroutineContext().ensureActive()
                val date = Instant.ofEpochSecond(item.publishTime)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()

                if (oldest == null || date.isBefore(oldest)) oldest = date
                if (date.isAfter(to) || date.isBefore(from)) continue

                val local = runCatching { repository.findByUrl(item.url) }.getOrNull()
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
                notify(onProgress, "同步 " + mp.name + " · 第 " + page + " 页 · " + item.title.take(28))

                val parsed = try {
                    collector.fetch(item.url)
                } catch (t: Throwable) {
                    if (t is VerificationRequiredException) verification += item.url else failed++
                    continue
                }

                val enriched = parsed.copy(
                    account = parsed.account.ifBlank { mp.name },
                    publishDate = parsed.publishDate ?: date
                )

                val f = filter.trim()
                val authorOk = f.isBlank() ||
                    enriched.account.contains(f, true) ||
                    enriched.author.contains(f, true)

                if (!authorOk) {
                    filtered++
                    continue
                }

                runCatching {
                    repository.save(enriched, collector.markdown(enriched))
                }.onSuccess { added++ }.onFailure { failed++ }
            }

            if (oldest != null && oldest.isBefore(from)) stop = true
            if (!stop) {
                page++
                delay(1200)
            }
        }

        HistorySyncResult(mp.name, added, existed, filtered, verification.toList(), failed)
    }

    private suspend fun notify(onProgress: (String) -> Unit, message: String) {
        withContext(Dispatchers.Main.immediate) { onProgress(message) }
    }
}
