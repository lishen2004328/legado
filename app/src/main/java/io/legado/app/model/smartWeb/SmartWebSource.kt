package io.legado.app.model.smartWeb

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.util.concurrent.TimeUnit

object SmartWebSource {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun build(bookUrl: String): io.legado.app.data.entities.BookSource? {
        return runCatching {
            val html = fetch(bookUrl)
            if (html.length < 500) return@runCatching null

            val doc = Jsoup.parse(html, bookUrl)
            val baseUrl = URL(bookUrl)
            val chapterLinks = findChapterLinks(doc)
            val content = findContent(doc)
            if (chapterLinks.size < 3 || content == null) return@runCatching null

            val name = firstText(
                doc.selectFirst("meta[property=og:title]")?.attr("content"),
                doc.selectFirst("meta[name=title]")?.attr("content"),
                doc.selectFirst("h1")?.text(),
                doc.title()
            ) ?: return@runCatching null

            val author = firstText(
                doc.selectFirst("meta[property=og:novel:author]")?.attr("content"),
                doc.selectFirst("meta[name=author]")?.attr("content"),
                doc.selectFirst("[class*=author], [id*=author]")?.text()
            ).orEmpty()

            val intro = firstText(
                doc.selectFirst("meta[name=description]")?.attr("content"),
                doc.selectFirst("[class*=intro], [id*=intro], [class*=desc], [id*=desc]")?.text()
            ).orEmpty()

            val cover = firstText(
                doc.selectFirst("meta[property=og:image]")?.attr("content"),
                doc.selectFirst("img[class*=cover], img[id*=cover]")?.absUrl("src"),
                doc.selectFirst("img")?.absUrl("src")
            ).orEmpty()

            val tocSelector = chapterSelector(chapterLinks)
            val contentSelector = content.cssSelector()

            io.legado.app.data.entities.BookSource(
                bookSourceUrl = \${baseUrl.origin},
                bookSourceName = "智能网页源 · \${baseUrl.host}",
                bookSourceGroup = "智能网页导入",
                bookSourceType = 0,
                bookUrlPattern = Regex.escape(bookUrl),
                enabled = true,
                enabledExplore = false,
                ruleBookInfo = io.legado.app.data.entities.rule.BookInfoRule(
                    name = "meta[property=og:title]@content||h1@text||title@text",
                    author = "meta[property=og:novel:author]@content||meta[name=author]@content||[class*=author]@text",
                    intro = "meta[name=description]@content||[class*=intro]@text||[class*=desc]@text",
                    coverUrl = "meta[property=og:image]@content||img[class*=cover]@src",
                    tocUrl = bookUrl
                ),
                ruleToc = io.legado.app.data.entities.rule.TocRule(
                    chapterList = tocSelector,
                    chapterName = "text",
                    chapterUrl = "href"
                ),
                ruleContent = io.legado.app.data.entities.rule.ContentRule(
                    content = "@css:\${contentSelector}@html"
                ),
                bookSourceComment = "自动生成；探测章节\${chapterLinks.size}条，正文选择器：\${contentSelector}"
            )
        }.getOrNull()
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP \${response.code}")
            response.body?.string() ?: ""
        }
    }

    private fun firstText(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun findChapterLinks(doc: Document): List<Element> {
        val candidates = doc.select("a[href]").filter { a ->
            val text = a.text().trim()
            if (text.length !in 2..80) return@filter false
            val href = a.absUrl("href")
            if (href.isBlank() || !href.startsWith("http")) return@filter false

            val chapterWord = Regex(
                "(?i)(第\\s*[0-9一二三四五六七八九十百千万]+\\s*[章节话回卷]|chapter\\s*[0-9]+|番外|楔子|序章|正文)"
            ).containsMatchIn(text)
            val urlWord = Regex("(?i)(chapter|chap|read|book|novel|article)").containsMatchIn(href)
            chapterWord || urlWord
        }

        return candidates.distinctBy { it.absUrl("href") }.take(200)
    }

    private fun chapterSelector(links: List<Element>): String {
        if (links.isEmpty()) return "a[href]"

        val commonClass = links
            .flatMap { it.classNames() }
            .groupingBy { it }
            .eachCount()
            .filter { (_, count) -> count >= maxOf(3, links.size / 2) }
            .keys
            .firstOrNull()

        if (!commonClass.isNullOrBlank()) {
            val escaped = commonClass
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            return "a[class=\"$escaped\"][href]"
        }

        val parent = links
            .take(minOf(20, links.size))
            .map { it.parent() }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        return if (parent != null) {
            "\${parent.cssSelector()} a[href]"
        } else {
            "a[href]"
        }
    }

    private fun findContent(doc: Document): Element? {
        val candidates = doc.select(
            "article,main,[class*=content],[id*=content],[class*=chapter],[id*=chapter]," +
                "[class*=article],[id*=article],div"
        ).asSequence()
            .filter { it.tagName() != "body" }
            .filter { it.text().length >= 500 }
            .map { element ->
                val textLength = element.text().length.coerceAtMost(12000)
                val paragraphs = element.select("p").size
                val links = element.select("a").size
                val classHint = Regex("(?i)(content|chapter|article|read|text|body)")
                    .containsMatchIn("\${element.id()} \${element.className()}")
                val score =
                    textLength / 10 +
                        paragraphs * 80 +
                        if (classHint) 1500 else 0 -
                        links * 8
                element to score
            }
            .sortedByDescending { it.second }
            .toList()

        return candidates.firstOrNull()?.first
    }
}
