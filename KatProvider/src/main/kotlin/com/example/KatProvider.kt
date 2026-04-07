package com.example

import android.util.Log
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
    private val debugNetwork = false
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
    private val defaultStreamTapeBase = "https://streamtape.com/e/"
    private val defaultStreamWishBase = "https://hanerix.com/e/"

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
        val apiResults = getPosts(search = query).mapNotNull { it.toSearchResponse() }
        if (apiResults.isNotEmpty()) return apiResults
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
            val resolvedEpisodeData = resolveEpisodeDataFromPlayPage(episodeData)
            emitEpisodeLinks(resolvedEpisodeData, subtitleCallback, callback)
            return resolvedEpisodeData.streamTapeId != null || resolvedEpisodeData.streamWishId != null
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
        return parseEpisodeDataFromPlayPage(text, playUrl)
            .mapNotNull { episodeData ->
                val seasonEpisode = Regex("""(?i)S(\d{1,2})E(\d{1,3})""").find(episodeData.fileName) ?: return@mapNotNull null
                newEpisode(episodeData.toJson()) {
                    name = Regex("""(?i)S\d{1,2}E\d{1,3}""").find(episodeData.fileName)?.value ?: episodeData.fileName
                    season = seasonEpisode.groupValues[1].toIntOrNull()
                    episode = seasonEpisode.groupValues[2].toIntOrNull()
                }
            }
            .distinctBy { "${it.season}:${it.episode}" }
            .toList()
    }

    private suspend fun resolveEpisodeDataFromPlayPage(episodeData: EpisodeData): EpisodeData {
        val playText = runCatching { getText(episodeData.playUrl, episodeData.playUrl) }.getOrNull() ?: return episodeData
        val token = Regex("""(?i)S\d{1,2}E\d{1,3}""").find(episodeData.fileName)?.value?.lowercase(Locale.ROOT)
        val target = episodeData.fileName.trim().lowercase(Locale.ROOT)
        val candidates = parseEpisodeDataFromPlayPage(playText, episodeData.playUrl)

        val matched = candidates.firstOrNull { candidate ->
            val normalized = candidate.fileName.trim().lowercase(Locale.ROOT)
            normalized == target ||
                normalized.contains(target) ||
                target.contains(normalized) ||
                (token != null && normalized.contains(token))
        } ?: return episodeData

        debugLog(
            "resolveEpisodeDataFromPlayPage file=${episodeData.fileName} matched=${matched.fileName} " +
                "streamTape=${matched.streamTapeId?.shortId()} streamWish=${matched.streamWishId?.shortId()}"
        )
        return episodeData.copy(
            streamTapeId = matched.streamTapeId ?: episodeData.streamTapeId,
            streamWishId = matched.streamWishId ?: episodeData.streamWishId
        )
    }

    private fun parseEpisodeDataFromPlayPage(playText: String, playUrl: String): List<EpisodeData> {
        val linksBlock = extractResolvedDataBlock(playText, 2) ?: playText
        val infoBlock = extractResolvedDataBlock(playText, 1) ?: playText
        val streamTapeBase = extractPlatformLink(linksBlock, "streamtape_res") ?: defaultStreamTapeBase
        val streamWishBase = extractPlatformLink(linksBlock, "streamwish_res") ?: defaultStreamWishBase

        val episodes = mutableListOf<EpisodeData>()
        
        // Match episode patterns: key:{name:"...",fields...}
        // Using a more permissive pattern that captures the key and name
        val episodePattern = Regex(
            """(\w+):\{name:"([^"]+)"[^}]*?\}""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        
        val matches = episodePattern.findAll(infoBlock)
        for (match in matches) {
            val episodeKey = match.groupValues.getOrNull(1) ?: continue
            val episodeName = match.groupValues.getOrNull(2)?.trim() ?: continue
            
            // Verify it's a valid episode (contains season/episode info)
            if (!Regex("""(?i)S\d{1,2}E\d{1,3}""").containsMatchIn(episodeName)) continue
            
            // Extract the full object content between the braces
            val bracesStart = match.range.first + match.value.indexOf('{')
            var braceCount = 0
            var bracesEnd = -1
            for (i in bracesStart until infoBlock.length) {
                when (infoBlock[i]) {
                    '{' -> braceCount++
                    '}' -> {
                        braceCount--
                        if (braceCount == 0) {
                            bracesEnd = i
                            break
                        }
                    }
                }
            }
            
            if (bracesEnd == -1) continue
            
            val objectContent = infoBlock.substring(bracesStart + 1, bracesEnd)
            
            // Extract streamtape_res ID
            val streamTapeMatch = Regex("""streamtape_res:"([^"]*)""").find(objectContent)
            val streamTapeId = streamTapeMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() && it != "null" }
            
            // Extract streamwish_res ID
            val streamWishMatch = Regex("""streamwish_res:"([^"]*)""").find(objectContent)
            val streamWishId = streamWishMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() && it != "null" }
            
            debugLog("Parsed episode: $episodeName key=$episodeKey st=$streamTapeId sw=$streamWishId")
            
            episodes.add(
                EpisodeData(
                    playUrl = playUrl,
                    fileName = episodeName,
                    streamTapeId = streamTapeId,
                    streamWishId = streamWishId,
                    streamTapeUrl = streamTapeId?.let { buildPlatformUrl(streamTapeBase, it) },
                    streamWishUrl = streamWishId?.let { buildPlatformUrl(streamWishBase, it) },
                )
            )
        }
        
        return episodes
    }

    private fun extractResolvedDataBlock(playText: String, id: Int): String? {
        return Regex(
            """__sveltekit_[a-z0-9]+\.resolve\(\{id:$id,data:\{(.*?)\},error:void 0\}\)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(playText)?.groupValues?.getOrNull(1)
    }

    private fun extractPlatformLink(block: String, key: String): String? {
        return Regex(
            """$key\s*:\s*\{[^{}]*link:"([^"]+)"""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(block)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeUnless { it.isBlank() }
    }

    private fun buildPlatformUrl(base: String, id: String): String {
        val normalizedBase = if (base.endsWith("/")) base else "$base/"
        return "$normalizedBase$id"
    }

    private suspend fun loadLinksFromHtml(
        contentHtml: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = Jsoup.parseBodyFragment(contentHtml, referer)
        val candidateLinks = linkedSetOf<String>()

        val iframes = document.select("iframe[src]")
        for (frame in iframes) {
            frame.absUrl("src").ifBlank { frame.attr("src").trim() }
                .takeIf { it.isNotBlank() }
                ?.let { candidateLinks.add(normalizeUrl(it)) }
        }

        val anchors = document.select("a[href]")
        for (anchor in anchors) {
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href").trim() }
            if (href.isBlank()) continue
            if (href.startsWith("#")) continue
            if (href.contains("telegram", true)) continue
            if (href.contains("/tag/")) continue
            if (href.contains("/category/")) continue
            if (href.contains("/wp-content/")) continue
            candidateLinks.add(normalizeUrl(href))
        }

        debugLog("loadLinksFromHtml referer=$referer candidateLinks=${candidateLinks.size}")
        for (link in candidateLinks) {
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
        val text = getResponse(url, referer).text
        debugLog("BODY url=$url len=${text.length} preview=${text.preview()}")
        return text
    }

    private suspend fun getJsonText(url: String, referer: String? = null): String {
        debugLog("GET JSON url=$url referer=${referer ?: "-"}")
        val text = try {
            app.get(
                url,
                headers = headersFor(referer, jsonHeaders)
            ).text
        } catch (t: Throwable) {
            errorLog("GET JSON FAILED url=$url referer=${referer ?: "-"}", t)
            throw t
        }
        debugLog("JSON BODY url=$url len=${text.length} preview=${text.preview()}")
        return text
    }

    private suspend fun getResponse(url: String, referer: String? = null) = try {
        debugLog("GET url=$url referer=${referer ?: "-"}")
        app.get(
            url,
            headers = headersFor(referer, siteHeaders),
            referer = referer
        )
    } catch (t: Throwable) {
        errorLog("GET FAILED url=$url referer=${referer ?: "-"}", t)
        throw t
    }

    private fun headersFor(referer: String?, base: Map<String, String>): Map<String, String> {
        return if (referer.isNullOrBlank()) base else base + mapOf("Referer" to referer)
    }

    private fun debugLog(message: String) {
        if (debugNetwork) Log.d("KatProvider", message)
    }

    private fun errorLog(message: String, throwable: Throwable) {
        if (debugNetwork) Log.e("KatProvider", message, throwable)
    }

    private fun String.preview(limit: Int = 200): String {
        return this.take(limit).replace("\n", " ").replace("\r", " ")
    }

    private fun String.shortId(): String {
        if (isBlank()) return "blank"
        if (length <= 8) return this
        return "${take(4)}...${takeLast(4)}"
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
                
                // Parse episodes from the play page structure
                val episodes = parseEpisodeDataFromPlayPage(text, link)
                debugLog("KMHD play parsed link=$link episodes=${episodes.size} quality=$quality")
                
                // Load all episode links
                for (episodeData in episodes) {
                    emitEpisodeLinks(episodeData, subtitleCallback, callback)
                }

                episodes.isNotEmpty()
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
                debugLog("KMHD file parsed link=$link directLinks=${directLinks.size}")

                for (out in directLinks) {
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
        debugLog(
            "emitEpisodeLinks playUrl=${episodeData.playUrl} " +
                "streamTapeId=${episodeData.streamTapeId?.shortId()} streamWishId=${episodeData.streamWishId?.shortId()} " +
                "streamTapeUrl=${episodeData.streamTapeUrl} streamWishUrl=${episodeData.streamWishUrl}"
        )
        val links = linkedSetOf<String>()
        // Use pre-built URLs if available, fallback to reconstructing from IDs
        episodeData.streamTapeUrl?.let { links.add(it) }
            ?: episodeData.streamTapeId?.let { links.add(buildPlatformUrl(defaultStreamTapeBase, it)) }
        episodeData.streamWishUrl?.let { links.add(it) }
            ?: episodeData.streamWishId?.let { links.add(buildPlatformUrl(defaultStreamWishBase, it)) }

        for (link in links) {
            loadExtractor(link, episodeData.playUrl, subtitleCallback, callback)
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
        val streamTapeUrl: String? = null,
        val streamWishUrl: String? = null,
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
