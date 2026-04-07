package com.example

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale

class KatProvider : MainAPI() {
    override var mainUrl = "https://new.katmoviehd.cymru"
    override var name = "Kat"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "hi"
    override val hasMainPage = true

    private val apiUrl = "$mainUrl/wp-json/wp/v2"
    private val siteHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Upgrade-Insecure-Requests" to "1",
    )
    private val jsonHeaders = siteHeaders + mapOf(
        "Accept" to "application/json,text/plain,*/*"
    )

    private val mediaCache = linkedMapOf<Int, String?>()

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/movies/" to "Movies",
        "$mainUrl/category/tv-shows/" to "TV Shows",
        "$mainUrl/category/dual-audio/" to "Dual Audio",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        val document = getDocument(url)
        val items = document.toSearchResults()
        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = hasNextPage(document)
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return getDocument("$mainUrl/?s=$encoded").toSearchResults()
    }

    override suspend fun load(url: String): LoadResponse? {
        val initialData = parseLoadData(url)
        val targetUrl = initialData?.url ?: url
        val document = runCatching { getDocument(targetUrl) }.getOrNull()
        val post = if (document == null) {
            initialData?.postId?.let { getPostById(it) }
                ?: initialData?.slug?.let { getPostBySlug(it) }
                ?: getPostBySlug(targetUrl.substringAfterLast("/").substringBefore("?").trim('/'))
        } else {
            null
        }

        val pageTitle = document?.selectFirst(
            "h1.entry-title, h1.post-title, h1, meta[property=og:title]"
        )?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }

        val title = (pageTitle
            ?: post?.renderedTitle()
            ?: ""
            ).substringBefore(" - KatMovieHD").trim().ifBlank { return null }

        val description = document?.selectFirst(
            "meta[name=description], meta[property=og:description], .entry-content p, .post-content p"
        )?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }?.trim()
            ?: post?.renderedContentText()?.ifBlank { post.renderedExcerpt().ifBlank { null } }

        val poster = fixUrlNull(
            document?.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document?.selectFirst(".post-thumb img, .entry img, .entry-content img, img.wp-post-image")
                    ?.run { attr("src").ifBlank { attr("data-src") } }
                ?: post?.resolvePoster()
        )
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val contentHtml = document?.selectFirst("article, .entry-content, .post-content")?.html()
            ?: post?.renderedContentHtml()
            ?: initialData?.contentHtml
            ?: ""
        val loadData = (post?.toLoadData() ?: initialData ?: LoadData(url = targetUrl)).copy(
            url = targetUrl,
            contentHtml = contentHtml.ifBlank { null }
        )

        val playLink = extractPlayLink(contentHtml)
        val manifestEpisodes = playLink?.let { extractManifestEpisodes(it, targetUrl) }.orEmpty()
        val episodes = if (manifestEpisodes.isNotEmpty()) {
            manifestEpisodes
        } else {
            extractEpisodes(contentHtml, title, loadData)
        }
        val tvType = if (episodes.isNotEmpty()) TvType.TvSeries else (post?.let { guessType(it) } ?: guessType(targetUrl, title))

        return if (tvType == TvType.TvSeries) {
            val finalEpisodes = if (episodes.isNotEmpty()) episodes else {
                listOf(newEpisode(loadData.toJson()) { name = title })
            }
            newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, targetUrl, TvType.Movie, loadData.toJson()) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        parseEpisodeData(data)?.let { episodeData ->
            emitEpisodeLinks(episodeData, subtitleCallback, callback)
            return episodeData.streamTapeId != null || episodeData.streamWishId != null
        }

        val loadData = parseLoadData(data)
        val directEpisodeUrl = loadData?.episodeUrl?.takeIf { it.isNotBlank() }
        if (directEpisodeUrl != null) {
            return loadLinksFromUrl(directEpisodeUrl, loadData.url ?: directEpisodeUrl, subtitleCallback, callback)
        }

        val post = loadData?.postId?.let { getPostById(it) }
            ?: loadData?.slug?.let { getPostBySlug(it) }
        val referer = post?.canonicalUrl() ?: loadData?.url ?: data.removeSurrounding("\"")
        val contentHtml = post?.renderedContentHtml()
            ?: loadData?.contentHtml
            ?: getDocument(referer, referer).selectFirst("article, .entry-content, .post-content")?.html().orEmpty()

        if (contentHtml.isBlank()) return false

        return loadLinksFromHtml(contentHtml, referer, subtitleCallback, callback)
    }

    private suspend fun getPosts(
        page: Int = 1,
        search: String? = null,
        categoryId: Int? = null,
    ): List<WpPost> {
        val query = buildList {
            add("page=$page")
            add("per_page=20")
            add("orderby=date")
            add("order=desc")
            search?.takeIf { it.isNotBlank() }?.let {
                add("search=${URLEncoder.encode(it, "UTF-8")}")
            }
            categoryId?.let { add("categories=$it") }
        }.joinToString("&")

        return runCatching {
            getJsonText("$apiUrl/posts?$query").parsedSafe<List<WpPost>>() ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private suspend fun getPostById(id: Int): WpPost? {
        return runCatching {
            getJsonText("$apiUrl/posts/$id").parsedSafe<WpPost>()
        }.getOrNull()
    }

    private suspend fun getPostBySlug(slug: String): WpPost? {
        if (slug.isBlank()) return null
        val encodedSlug = URLEncoder.encode(slug, "UTF-8")
        return runCatching {
            getJsonText("$apiUrl/posts?slug=$encodedSlug&per_page=1").parsedSafe<List<WpPost>>()?.firstOrNull()
        }.getOrNull()
    }

    private suspend fun resolveMediaUrl(mediaId: Int?): String? {
        if (mediaId == null || mediaId <= 0) return null
        mediaCache[mediaId]?.let { return it }
        val url = runCatching {
            getJsonText("$apiUrl/media/$mediaId").parsedSafe<WpMedia>()?.sourceUrl
        }.getOrNull()
        mediaCache[mediaId] = url
        return url
    }

    private suspend fun WpPost.toSearchResponse(): SearchResponse? {
        val title = renderedTitle().substringBefore(" - KatMovieHD").trim().ifBlank { return null }
        val poster = resolvePoster()
        val type = guessType(this)
        val loadData = toLoadData()

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, canonicalUrl(), TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, canonicalUrl(), TvType.Movie) {
                this.posterUrl = poster
            }
        }
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
            .ifBlank { selectFirst("img")?.attr("alt")?.trim().orEmpty() }
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

        val type = guessType(href, title)

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

    private suspend fun WpPost.resolvePoster(): String? {
        return fixUrlNull(
            jetpackFeaturedMediaUrl
                ?: thumbnailUrl()
                ?: resolveMediaUrl(featuredMedia)
        )
    }

    private fun WpPost.thumbnailUrl(): String? {
        return embedded?.featuredMedia
            ?.firstOrNull()
            ?.mediaDetails
            ?.sizes
            ?.values
            ?.sortedByDescending { it.width ?: 0 }
            ?.firstOrNull()
            ?.sourceUrl
            ?: embedded?.featuredMedia?.firstOrNull()?.sourceUrl
    }

    private fun WpPost.renderedTitle(): String {
        return title?.rendered?.htmlToText().orEmpty()
    }

    private fun WpPost.renderedExcerpt(): String {
        return excerpt?.rendered?.htmlToText().orEmpty()
    }

    private fun WpPost.renderedContentText(): String {
        return content?.rendered?.htmlToText().orEmpty()
    }

    private fun WpPost.renderedContentHtml(): String {
        return content?.rendered.orEmpty()
    }

    private fun WpPost.canonicalUrl(): String {
        return normalizeUrl(link ?: "$mainUrl/${slug.orEmpty()}")
    }

    private fun WpPost.toLoadData(): LoadData {
        return LoadData(
            postId = id,
            slug = slug,
            url = canonicalUrl(),
            contentHtml = renderedContentHtml()
        )
    }

    private fun guessType(post: WpPost): TvType {
        val lowered = buildString {
            append(post.slug.orEmpty())
            append(' ')
            append(post.renderedTitle().lowercase(Locale.ROOT))
            append(' ')
            append(post.renderedExcerpt().lowercase(Locale.ROOT))
            append(' ')
            append(post.categories?.joinToString(",").orEmpty())
        }.lowercase(Locale.ROOT)

        return if (
            lowered.contains("tv-show") ||
            lowered.contains("tv series") ||
            lowered.contains("season") ||
            Regex("""\bs\d{1,2}\b""").containsMatchIn(lowered)
        ) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
    }

    private fun guessType(url: String, title: String): TvType {
        val lowered = "$url $title".lowercase(Locale.ROOT)
        return if (
            lowered.contains("season") ||
            lowered.contains("tv series") ||
            lowered.contains("tv-show") ||
            lowered.contains("s0") ||
            lowered.contains("/category/tv-shows/")
        ) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }
    }

    private fun extractEpisodes(contentHtml: String, title: String, loadData: LoadData): List<com.lagradost.cloudstream3.Episode> {
        val document = Jsoup.parseBodyFragment(contentHtml)
        val seasonEpisodePattern = Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b""")
        val episodePattern = Regex("""(?i)\b(?:episode|ep|e)\s*0*(\d{1,3})\b""")
        val seasonPattern = Regex("""(?i)\bseason\s*(\d{1,2})\b""")

        return document.select("a[href]")
            .mapNotNull { link ->
                val text = link.text().trim()
                val href = link.absUrl("href").ifBlank { link.attr("href").trim() }
                if (text.isBlank() || href.isBlank()) return@mapNotNull null

                val seasonEpisodeMatch = seasonEpisodePattern.find(text)
                val episodeMatch = seasonEpisodeMatch ?: episodePattern.find(text) ?: return@mapNotNull null

                val seasonNumber = seasonEpisodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: seasonPattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: extractSeasonFromTitle(title)
                val episodeNumber = seasonEpisodeMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                    ?: episodeMatch.groupValues.getOrNull(1)?.toIntOrNull()
                    ?: return@mapNotNull null

                newEpisode(
                    loadData.copy(
                        episodeUrl = normalizeUrl(href),
                        contentHtml = null,
                    ).toJson()
                ) {
                    name = text
                    season = seasonNumber
                    episode = episodeNumber
                }
            }
            .distinctBy { "${it.season}:${it.episode}:${it.name}" }
    }

    private fun extractPlayLink(contentHtml: String): String? {
        return Jsoup.parseBodyFragment(contentHtml)
            .select("a[href]")
            .mapNotNull { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href").trim() }
                href.takeIf { it.contains("links.kmhd.eu/play?") }?.let { normalizeUrl(it) }
            }
            .firstOrNull()
    }

    private suspend fun extractManifestEpisodes(playUrl: String, referer: String): List<com.lagradost.cloudstream3.Episode> {
        val text = getText(playUrl, referer)
        return Regex("""name:"([^"]+S\d{1,2}E\d{1,3}[^"]*)",([^}]*)\}""")
            .findAll(text)
            .mapNotNull { match ->
                val fileName = match.groupValues[1]
                val ids = match.groupValues[2]
                val seasonEpisode = Regex("""(?i)S(\d{1,2})E(\d{1,3})""").find(fileName) ?: return@mapNotNull null
                val episodeData = EpisodeData(
                    playUrl = playUrl,
                    fileName = fileName,
                    streamTapeId = Regex("""streamtape_res:"([^"]+)"""").find(ids)?.groupValues?.getOrNull(1)
                        ?.takeUnless { it.isBlank() || it == "null" },
                    streamWishId = Regex("""streamwish_res:"([^"]+)"""").find(ids)?.groupValues?.getOrNull(1)
                        ?.takeUnless { it.isBlank() || it == "null" },
                )

                newEpisode(episodeData.toJson()) {
                    name = Regex("""(?i)S\d{1,2}E\d{1,3}""").find(fileName)?.value ?: fileName
                    season = seasonEpisode.groupValues[1].toIntOrNull()
                    episode = seasonEpisode.groupValues[2].toIntOrNull()
                }
            }
            .distinctBy { "${it.season}:${it.episode}" }
            .toList()
    }

    private suspend fun loadLinksFromHtml(
        contentHtml: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = Jsoup.parseBodyFragment(contentHtml, referer)
        val candidateLinks = linkedSetOf<String>()

        document.select("iframe[src]").forEach { frame: Element ->
            frame.absUrl("src").ifBlank { frame.attr("src").trim() }
                .takeIf { it.isNotBlank() }
                ?.let { candidateLinks.add(normalizeUrl(it)) }
        }

        document.select("a[href]").forEach { anchor: Element ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href").trim() }
            if (href.isBlank()) return@forEach
            if (href.startsWith("#")) return@forEach
            if (href.contains("telegram", true)) return@forEach
            if (href.contains("/tag/")) return@forEach
            if (href.contains("/category/")) return@forEach
            if (href.contains("/wp-content/")) return@forEach
            candidateLinks.add(normalizeUrl(href))
        }

        candidateLinks.forEach { link ->
            if (!loadKmhdLink(link, referer, subtitleCallback, callback)) {
                loadExtractor(link, referer, subtitleCallback, callback)
            }
        }

        return candidateLinks.isNotEmpty()
    }

    private suspend fun loadLinksFromUrl(
        targetUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val normalizedTarget = normalizeUrl(targetUrl)

        if (loadKmhdLink(normalizedTarget, referer, subtitleCallback, callback)) {
            return true
        }

        if (
            normalizedTarget.contains("streamtape", true) ||
            normalizedTarget.contains("streamwish", true) ||
            normalizedTarget.contains("hglink.to", true) ||
            normalizedTarget.contains("1fichier.com", true) ||
            normalizedTarget.contains("send.cm", true)
        ) {
            loadExtractor(normalizedTarget, referer, subtitleCallback, callback)
            return true
        }

        val response = runCatching { getResponse(normalizedTarget, referer) }.getOrNull() ?: return false
        val html = response.document.selectFirst("article, .entry-content, .post-content, body")?.html().orEmpty()

        return if (html.isNotBlank()) {
            loadLinksFromHtml(html, normalizedTarget, subtitleCallback, callback)
        } else {
            false
        }
    }

    private fun fixUrlNull(url: String?): String? {
        return url?.takeIf { it.isNotBlank() }?.let { normalizeUrl(it) }
    }

    private suspend fun getDocument(url: String, referer: String? = null): Document {
        return getResponse(url, referer).document
    }

    private suspend fun getText(url: String, referer: String? = null): String {
        return getResponse(url, referer).text
    }

    private suspend fun getJsonText(url: String, referer: String? = null): String {
        return app.get(
            url,
            headers = headersFor(referer, jsonHeaders)
        ).text
    }

    private suspend fun getResponse(url: String, referer: String? = null) = app.get(
        url,
        headers = headersFor(referer, siteHeaders),
        referer = referer
    )

    private fun headersFor(referer: String?, base: Map<String, String>): Map<String, String> {
        return if (referer.isNullOrBlank()) base else base + mapOf("Referer" to referer)
    }

    private fun hasNextPage(document: Document): Boolean {
        return document.selectFirst("link[rel=next], a.next, .next.page-numbers") != null
    }

    private fun buildPagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return "${base.trimEnd('/')}/page/$page/"
    }

    private suspend fun loadKmhdLink(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!link.contains("links.kmhd.eu")) return false

        return when {
            link.contains("/play?") -> {
                val text = getText(link, referer)
                val releaseName = Regex("""name:"([^"]+)"""").find(text)?.groupValues?.getOrNull(1)
                val quality = getQualityFromName(releaseName)
                val streamtapeIds = Regex("""streamtape_res:"([^"]+)"""").findAll(text)
                    .map { it.groupValues[1] }
                    .filter { it.isNotBlank() && it != "null" }
                    .toSet()
                val streamwishIds = Regex("""streamwish_res:"([^"]+)"""").findAll(text)
                    .map { it.groupValues[1] }
                    .filter { it.isNotBlank() && it != "null" }
                    .toSet()

                streamtapeIds.forEach { id ->
                    loadExtractor("https://streamtape.com/e/$id", referer, subtitleCallback, callback)
                }
                streamwishIds.forEach { id ->
                    loadExtractor("https://hglink.to/e/$id", referer, subtitleCallback, callback)
                }

                streamtapeIds.isNotEmpty() || streamwishIds.isNotEmpty()
            }

            link.contains("/file/") -> {
                val text = getText(link, referer)
                val directLinks = Regex("""https?://[^"'\\s<>]+""").findAll(text)
                    .map { it.value.trimEnd('"', '\\') }
                    .filter {
                        it.contains("katdrive.eu/file/") ||
                            it.contains("gd.kmhd.eu/file/") ||
                            it.contains("hubcloud.foo/drive/") ||
                            it.contains("send.cm/") ||
                            it.contains("1fichier.com/") ||
                            it.contains("fuckingfast.net/")
                    }
                    .toSet()

                directLinks.forEach { out ->
                    loadExtractor(out, referer, subtitleCallback, callback)
                }
                false
            }

            else -> false
        }
    }

    private fun parseEpisodeData(data: String): EpisodeData? {
        return runCatching { parseJson<EpisodeData>(data) }.getOrNull()
    }

    private fun parseLoadData(data: String): LoadData? {
        return runCatching { parseJson<LoadData>(data) }.getOrNull()
    }

    private suspend fun emitEpisodeLinks(
        episodeData: EpisodeData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        episodeData.streamTapeId?.let { id ->
            loadExtractor("https://streamtape.com/e/$id", episodeData.playUrl, subtitleCallback, callback)
        }

        episodeData.streamWishId?.let { id ->
            loadExtractor("https://hglink.to/e/$id", episodeData.playUrl, subtitleCallback, callback)
        }
    }

    private fun extractSeasonFromTitle(title: String): Int? {
        return Regex("""(?i)season\s*(\d+)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(?i)\bs(\d{1,2})\b""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private inline fun <reified T> String.parsedSafe(): T? {
        return runCatching { parseJson<T>(this) }.getOrNull()
    }

    private fun String.htmlToText(): String {
        return Jsoup.parse(this).text().trim()
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "${mainUrl.trimEnd('/')}$url"
            else -> "${mainUrl.trimEnd('/')}/${url.trimStart('/')}"
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LoadData(
        val postId: Int? = null,
        val slug: String? = null,
        val url: String? = null,
        val episodeUrl: String? = null,
        val contentHtml: String? = null,
    )

    data class EpisodeData(
        val playUrl: String,
        val fileName: String,
        val streamTapeId: String? = null,
        val streamWishId: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpPost(
        val id: Int? = null,
        val slug: String? = null,
        val link: String? = null,
        val categories: List<Int>? = null,
        @param:JsonAlias("featured_media")
        val featuredMedia: Int? = null,
        @param:JsonAlias("jetpack_featured_media_url")
        val jetpackFeaturedMediaUrl: String? = null,
        val title: RenderedField? = null,
        val excerpt: RenderedField? = null,
        val content: RenderedField? = null,
        @param:JsonAlias("_embedded")
        val embedded: EmbeddedData? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RenderedField(
        val rendered: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EmbeddedData(
        @param:JsonAlias("wp:featuredmedia")
        val featuredMedia: List<WpMedia>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpMedia(
        @param:JsonAlias("source_url")
        val sourceUrl: String? = null,
        @param:JsonAlias("media_details")
        val mediaDetails: MediaDetails? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaDetails(
        val sizes: Map<String, MediaSize>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaSize(
        val width: Int? = null,
        @param:JsonAlias("source_url")
        val sourceUrl: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WpTerm(
        val id: Int? = null,
        val slug: String? = null,
        val name: String? = null,
    )
}
