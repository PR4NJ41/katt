package com.example

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale

class KatProvider : MainAPI() {
    override var mainUrl = "https://katmoviehd.run"
    override var name = "Kat"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "hi"
    override val hasMainPage = true

    private val apiUrl = "$mainUrl/wp-json/wp/v2"

    private val categoryCache = linkedMapOf<String, Int?>()
    private val mediaCache = linkedMapOf<Int, String?>()

    override val mainPage = mainPageOf(
        "latest" to "Latest",
        "movies" to "Movies",
        "tv-shows" to "TV Shows",
        "dual-audio" to "Dual Audio",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val posts = when (request.data) {
            "latest" -> getPosts(page = page)
            else -> {
                val categoryId = getCategoryId(request.data) ?: return newHomePageResponse(
                    listOf(HomePageList(request.name, emptyList())),
                    hasNext = false
                )
                getPosts(page = page, categoryId = categoryId)
            }
        }

        val items = if (posts.isNotEmpty()) {
            posts.mapNotNull { it.toSearchResponse() }
        } else {
            loadHtmlMainPage(page, request.data)
        }
        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apiResults = getPosts(search = query).mapNotNull { it.toSearchResponse() }
        return apiResults.ifEmpty { loadHtmlSearch(query) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val initialData = parseLoadData(url)
        val post = initialData?.postId?.let { getPostById(it) }
            ?: initialData?.slug?.let { getPostBySlug(it) }
            ?: getPostBySlug(url.substringAfterLast("/").substringBefore("?").trim('/'))
            ?: return null

        val loadData = post.toLoadData()
        val title = post.renderedTitle().substringBefore(" - KatMovieHD").trim().ifBlank { return null }
        val description = post.renderedContentText().ifBlank {
            post.renderedExcerpt().ifBlank { null }
        }
        val poster = post.resolvePoster()
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()

        val playLink = extractPlayLink(post.renderedContentHtml())
        val manifestEpisodes = playLink?.let { extractManifestEpisodes(it, post.link ?: mainUrl) }.orEmpty()
        val episodes = if (manifestEpisodes.isNotEmpty()) {
            manifestEpisodes
        } else {
            extractEpisodes(post.renderedContentHtml(), title, loadData)
        }
        val tvType = if (episodes.isNotEmpty()) TvType.TvSeries else guessType(post)

        return if (tvType == TvType.TvSeries) {
            val finalEpisodes = if (episodes.isNotEmpty()) episodes else {
                listOf(newEpisode(loadData.toJson()) { name = title })
            }
            newTvSeriesLoadResponse(title, post.canonicalUrl(), TvType.TvSeries, finalEpisodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, post.canonicalUrl(), TvType.Movie, loadData.toJson()) {
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
            emitEpisodeLinks(episodeData, callback)
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
            ?: app.get(referer).document.selectFirst("article, .entry-content, .post-content")?.html().orEmpty()

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
            app.get("$apiUrl/posts?$query").text.parsedSafe<List<WpPost>>() ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private suspend fun getPostById(id: Int): WpPost? {
        return runCatching {
            app.get("$apiUrl/posts/$id").text.parsedSafe<WpPost>()
        }.getOrNull()
    }

    private suspend fun getPostBySlug(slug: String): WpPost? {
        if (slug.isBlank()) return null
        val encodedSlug = URLEncoder.encode(slug, "UTF-8")
        return runCatching {
            app.get("$apiUrl/posts?slug=$encodedSlug&per_page=1").text.parsedSafe<List<WpPost>>()?.firstOrNull()
        }.getOrNull()
    }

    private suspend fun getCategoryId(slug: String): Int? {
        if (categoryCache.containsKey(slug)) return categoryCache[slug]
        val encodedSlug = URLEncoder.encode(slug, "UTF-8")
        val id = runCatching {
            app.get("$apiUrl/categories?slug=$encodedSlug&per_page=1").text.parsedSafe<List<WpTerm>>()?.firstOrNull()?.id
        }.getOrNull()
        categoryCache[slug] = id
        return id
    }

    private suspend fun resolveMediaUrl(mediaId: Int?): String? {
        if (mediaId == null || mediaId <= 0) return null
        mediaCache[mediaId]?.let { return it }
        val url = runCatching {
            app.get("$apiUrl/media/$mediaId").text.parsedSafe<WpMedia>()?.sourceUrl
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

    private suspend fun loadHtmlMainPage(page: Int, section: String): List<SearchResponse> {
        val baseUrl = when (section) {
            "movies" -> "$mainUrl/category/movies/"
            "tv-shows" -> "$mainUrl/category/tv-shows/"
            "dual-audio" -> "$mainUrl/category/dual-audio/"
            else -> "$mainUrl/"
        }
        val target = if (page <= 1) baseUrl else "${baseUrl.trimEnd('/')}/page/$page/"
        return runCatching { app.get(target).document.toSearchResults() }.getOrElse { emptyList() }
    }

    private suspend fun loadHtmlSearch(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return runCatching { app.get("$mainUrl/?s=$encoded").document.toSearchResults() }.getOrElse { emptyList() }
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
        val text = app.get(playUrl, referer = referer).text
        return Regex("""name:"([^"]+S\d{1,2}E\d{1,3}[^"]*)",([^}]*)}""")
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

        val response = runCatching { app.get(normalizedTarget, referer = referer) }.getOrNull() ?: return false
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

    private suspend fun loadKmhdLink(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!link.contains("links.kmhd.eu")) return false

        return when {
            link.contains("/play?") -> {
                val text = app.get(link, referer = referer).text
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
                    callback(
                        newExtractorLink(
                            source = "StreamTape",
                            name = "StreamTape",
                            url = "https://streamtape.com/e/$id"
                        ) {
                            this.referer = referer
                            this.quality = quality
                        }
                    )
                }
                streamwishIds.forEach { id ->
                    callback(
                        newExtractorLink(
                            source = "StreamWish",
                            name = "StreamWish",
                            url = "https://hglink.to/e/$id"
                        ) {
                            this.referer = referer
                            this.quality = quality
                        }
                    )
                }

                streamtapeIds.isNotEmpty() || streamwishIds.isNotEmpty()
            }

            link.contains("/file/") -> {
                val text = app.get(link, referer = referer).text
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
        callback: (ExtractorLink) -> Unit,
    ) {
        val quality = getQualityFromName(episodeData.fileName)

        episodeData.streamTapeId?.let { id ->
            callback(
                newExtractorLink(
                    source = "StreamTape",
                    name = "StreamTape",
                    url = "https://streamtape.com/e/$id"
                ) {
                    this.referer = episodeData.playUrl
                    this.quality = quality
                }
            )
        }

        episodeData.streamWishId?.let { id ->
            callback(
                newExtractorLink(
                    source = "StreamWish",
                    name = "StreamWish",
                    url = "https://hglink.to/e/$id"
                ) {
                    this.referer = episodeData.playUrl
                    this.quality = quality
                }
            )
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
