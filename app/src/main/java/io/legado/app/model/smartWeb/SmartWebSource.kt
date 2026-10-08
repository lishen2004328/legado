package io.legado.app.model.smartWeb

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.util.concurrent.TimeUnit

object SmartWebSource {
    data class Result(val source: io.legado.app.data.entities.BookSource, val resolvedUrl: String)

    private val httpClient by lazy {
        OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true).build()
    }

    suspend fun build(bookUrl: String): Result? = runCatching {
        val firstHtml = fetch(bookUrl)
        if (firstHtml.length < 500) return@runCatching null
        val firstDoc = Jsoup.parse(firstHtml, bookUrl)
        val detail = analyzeDetail(firstDoc, bookUrl) ?: findDetailPage(firstDoc, bookUrl)
            ?: return@runCatching null
        val doc = detail.document
        val chapterLinks = findChapterLinks(doc)
        val content = findContent(doc) ?: return@runCatching null
        if (chapterLinks.size < 3 && content.text().length < 1200) return@runCatching null

        val baseUrl = URL(detail.url)
        val name = firstText(
            doc.selectFirst("meta[property=og:title]")?.attr("content"),
            doc.selectFirst("meta[name=title]")?.attr("content"),
            doc.selectFirst("h1")?.text(), doc.title()
        ) ?: return@runCatching null
        val tocSelector = chapterSelector(chapterLinks)
        val contentSelector = content.cssSelector()

        Result(
            io.legado.app.data.entities.BookSource(
                bookSourceUrl = "${baseUrl.protocol}://${baseUrl.authority}",
                bookSourceName = "智能网页源 · ${baseUrl.host}",
                bookSourceGroup = "智能网页导入",
                bookSourceType = 0,
                bookUrlPattern = Regex.escape(detail.url),
                enabled = true, enabledExplore = false,
                ruleBookInfo = io.legado.app.data.entities.rule.BookInfoRule(
                    name = "meta[property=og:title]@content||meta[name=title]@content||h1@text||title@text",
                    author = "meta[property=og:novel:author]@content||meta[name=author]@content||[class*=author]@text||[id*=author]@text",
                    intro = "meta[name=description]@content||[class*=intro]@text||[id*=intro]@text||[class*=desc]@text",
                    coverUrl = "meta[property=og:image]@content||img[class*=cover]@src||img[id*=cover]@src",
                    tocUrl = detail.url
                ),
                ruleToc = io.legado.app.data.entities.rule.TocRule(
                    chapterList = tocSelector, chapterName = "text", chapterUrl = "href"
                ),
                ruleContent = io.legado.app.data.entities.rule.ContentRule(
                    content = "${contentSelector}@html"
                ),
                bookSourceComment = "智能识别；入口：$bookUrl；详情：${detail.url}；书名：$name；章节：${chapterLinks.size}；正文：$contentSelector"
            ),
            detail.url
        )
    }.getOrNull()

    private data class Detail(val url: String, val document: Document)

    private fun analyzeDetail(doc: Document, url: String): Detail? {
        val chapters = findChapterLinks(doc)
        val content = findContent(doc)
        return if (chapters.size >= 3 || (content != null && content.text().length >= 1200)) Detail(url, doc) else null
    }

    private fun findDetailPage(doc: Document, sourceUrl: String): Detail? {
        val source = URL(sourceUrl)
        val candidates = doc.select("a[href]").asSequence().mapNotNull { a ->
            val href = a.absUrl("href")
            val text = a.text().trim()
            val target = runCatching { URL(href) }.getOrNull()
            if (href.isBlank() || text.length !in 2..120 || target == null ||
                target.host != source.host || href == sourceUrl) return@mapNotNull null
            val signal = Regex("(?i)(小说|章节|正文|阅读|详情|read|chapter|novel|book|article|post)").containsMatchIn("$text $href")
            val penalty = Regex("(?i)(login|register|tag|category|author|search|page=|comment)").containsMatchIn(href)
            if (!signal || penalty) return@mapNotNull null
            val score = (if (text.length in 4..60) 3 else 0) +
                (if (Regex("(?i)(chapter|read|novel|book|article|post)").containsMatchIn(href)) 2 else 0)
            score to href
        }.sortedByDescending { it.first }.take(8).toList()

        for ((_, href) in candidates) {
            val html = runCatching { fetch(href) }.getOrNull() ?: continue
            if (html.length < 500) continue
            analyzeDetail(Jsoup.parse(html, href), href)?.let { return it }
        }
        return null
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml").build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body?.string() ?: ""
        }
    }

    private fun firstText(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun findChapterLinks(doc: Document): List<Element> = doc.select("a[href]").filter { a ->
        val text = a.text().trim()
        if (text.length !in 2..100) return@filter false
        val href = a.absUrl("href")
        if (href.isBlank() || !href.startsWith("http")) return@filter false
        Regex("(?i)(第\s*[0-9一二三四五六七八九十百千万]+\s*[章节话回卷]|chapter\s*[0-9]+|番外|楔子|序章|正文)").containsMatchIn(text) ||
            Regex("(?i)(chapter|chap|read|book|novel|article|post)").containsMatchIn(href)
    }.distinctBy { it.absUrl("href") }.take(500)

    private fun chapterSelector(links: List<Element>): String {
        if (links.isEmpty()) return "a[href]"
        val commonClass = links.flatMap { it.classNames() }.groupingBy { it }.eachCount()
            .filter { (_, count) -> count >= maxOf(3, links.size / 2) }.keys.firstOrNull()
        if (!commonClass.isNullOrBlank()) return "a.${commonClass.replace("\\", "\\\\").replace(" ", ".")}[href]"
        val parent = links.take(minOf(30, links.size)).map { it.parent() }.groupingBy { it.cssSelector() }
            .eachCount().maxByOrNull { it.value }?.key
        return if (!parent.isNullOrBlank()) "$parent a[href]" else "a[href]"
    }

    private fun findContent(doc: Document): Element? = doc.select(
        "article,main,[class*=content],[id*=content],[class*=chapter],[id*=chapter],[class*=article],[id*=article],[class*=read],[id*=read],div"
    ).asSequence().filter { it.tagName() !in setOf("body", "html") }.filter { it.text().length >= 500 }
        .map { element ->
            val textLength = element.text().length.coerceAtMost(16000)
            val paragraphs = element.select("p").size
            val links = element.select("a").size
            val hint = Regex("(?i)(content|chapter|article|read|text|body)").containsMatchIn("${element.id()} ${element.className()}")
            element to (textLength / 10 + paragraphs * 80 + if (hint) 1500 else 0 - links * 8)
        }.maxByOrNull { it.second }?.first
}
