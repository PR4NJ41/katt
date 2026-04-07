package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KatProvider : MainAPI() {
    override var mainUrl = "https://new.katmoviehd.cymru"
    override var name = "Kat"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "hi"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/category/tv-shows/" to "TV Shows",
        "$mainUrl/category/movies/" to "Movies",
        "$mainUrl/category/dual-audio/" to "Dual Audio",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        val document = app.get(url).document
        val items = document.toSearchResults()
        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = hasNextPage(document)
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document
        return document.toSearchResults()
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val titleElement = document.selectFirst(
            "h1.entry-title, h1.post-title, h1, meta[property=og:title]"
        ) ?: return null
        val title = (if (titleElement.tagName() == "meta") {
            titleElement.attr("content")
        } else {
            titleElement.text()
        }).substringBefore(" - KatMovieHD").trim().ifBlank { return null }

        val descriptionElement = document.selectFirst(
            "meta[name=description], meta[property=og:description], .entry-content p, .post-content p"
        )
        val description = descriptionElement?.run {
            if (tagName() == "meta") attr("content") else text()
        }?.trim()

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".post-thumb img, .entry img, .entry-content img, img.wp-post-image")
                    ?.run { attr("src").ifBlank { attr("data-src") } }
        )

        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val tags = document.select(".tagcloud a, .post-meta a[rel=tag], .entry-tags a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val actors = extractActors(document)
        val episodes = extractEpisodes(document)
        val tvType = guessType(url, title, episodes.isNotEmpty())

        return if (tvType == TvType.TvSeries) {
            val finalEpisodes = if (episodes.isNotEmpty()) episodes else {
                listOf(newEpisode(url.toJson()) { name = title })
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url.toJson()) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val url = data.removeSurrounding("\"")
        val document = app.get(url).document

        val candidateLinks = linkedSetOf<String>()

        document.select("iframe[src]").forEach { frame: Element ->
            frame.attr("src").takeIf { it.isNotBlank() }?.let { candidateLinks.add(fixUrl(it)) }
        }

        document.select(".entry-content a[href], .post-content a[href], article a[href], .download a[href]")
            .forEach { anchor: Element ->
                val href = anchor.attr("href").trim()
                if (href.isBlank()) return@forEach
                if (href.startsWith("#")) return@forEach
                if (href.contains("telegram", true)) return@forEach
                if (href.contains("/tag/")) return@forEach
                if (href.contains("/category/")) return@forEach
                if (href.contains("/wp-content/")) return@forEach
                candidateLinks.add(fixUrl(href))
            }

        candidateLinks.forEach { link ->
            loadExtractor(link, url, subtitleCallback, callback)
        }

        return candidateLinks.isNotEmpty()
    }

    private fun Document.toSearchResults(): List<SearchResponse> {
        return select("article, .post, .posts .post, .blog_item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h2 a, h3 a, .entry-title a, .post-title a, a[rel=bookmark], a")
            ?: return null
        val href = anchor.attr("href").trim().takeIf { it.startsWith("http") } ?: return null

        val title = anchor.text().trim()
            .ifBlank {
                selectFirst("img")?.attr("alt")?.trim().orEmpty()
            }
            .ifBlank { return null }

        val poster = fixUrlNull(
            selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank {
                    img.attr("src").ifBlank {
                        img.attr("data-lazy-src")
                    }
                }
            }
        )

        val type = guessType(href, title, false)

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private fun extractEpisodes(document: Document): List<Episode> =
        document.select(".entry-content a[href], .post-content a[href], article a[href]")
            .mapNotNull { link ->
                val text = link.text().trim()
                val href = link.attr("href").trim()
                if (href.isBlank() || !href.startsWith("http")) return@mapNotNull null
                val match = Regex("""(?i)(?:s(?:eason)?\s*(\d+))?.*?(?:e(?:pisode)?\s*(\d+))""")
                    .find(text)
                    ?: return@mapNotNull null

                newEpisode(href.toJson()) {
                    name = text
                    season = match.groupValues.getOrNull(1)?.toIntOrNull()
                    episode = match.groupValues.getOrNull(2)?.toIntOrNull()
                }
            }
            .distinctBy { "${it.name}:${it.season}:${it.episode}" }

    private fun extractActors(document: Document): List<Actor> {
        val castText = document.selectFirst(
            ".entry-content, .post-content, article"
        )?.text().orEmpty()

        val castMatch = Regex("""(?i)(cast|starring)\s*[:\-]\s*([^.]+)""").find(castText)
        return castMatch?.groupValues?.getOrNull(2)
            ?.split(",", "/", "|")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.map { Actor(it) }
            ?: emptyList()
    }

    private fun guessType(url: String, title: String, hasEpisodes: Boolean): TvType {
        val lowered = "$url $title".lowercase()
        return if (
            hasEpisodes ||
            lowered.contains("season") ||
            lowered.contains("tv series") ||
            lowered.contains("s0") ||
            lowered.contains("/category/tv-shows/")
        ) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
    }

    private fun hasNextPage(document: Document): Boolean {
        return document.selectFirst("link[rel=next], a.next, .next.page-numbers") != null
    }

    private fun buildPagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return "${base.trimEnd('/')}/page/$page/"
    }

    private fun fixUrlNull(url: String?): String? {
        return url?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
    }
}
