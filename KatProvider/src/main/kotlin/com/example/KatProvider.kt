package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KatProvider : MainAPI() {
    override var mainUrl = "https://katmoviehd.page"
    override var name = "Kat"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "hi"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/movies/page/" to "Movies",
        "$mainUrl/tvshows/page/" to "TV Shows",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val targetUrl = if (request.data.endsWith("/")) {
            "${request.data}$page"
        } else {
            "${request.data.trimEnd('/')}/$page"
        }
        val document = app.get(targetUrl).document
        val home = document
            .select("article.item, .items .item, .result-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document
        return document
            .select(".result-item, article.item, .items .item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(".data h1, .sheader .data h1, h1")?.text()?.trim()
            ?: return null
        val poster = fixUrlNull(
            document.selectFirst(".poster img, .sheader .poster img, .thumb img")?.attr("src")
                ?: document.selectFirst(".poster img, .sheader .poster img, .thumb img")
                    ?.attr("data-src")
        )
        val background = fixUrlNull(
            document.selectFirst(".g-item img, .backdrop img, .fanback img")?.attr("src")
        )
        val description = document.selectFirst(".wp-content p, .entry-content p, .contenido p")
            ?.text()
            ?.trim()
        val tags = document.select(".sgeneros a, .sgeneros span, .genres a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        val year = document.selectFirst("#info .date, .extra .date, .data .date")
            ?.text()
            ?.filter { it.isDigit() }
            ?.takeLast(4)
            ?.toIntOrNull()
        val actors = document.select("#cast .person, .persons .person")
            .mapNotNull { person ->
                val actorName = person.selectFirst(".name a, .data .name, .name")?.text()?.trim()
                    ?: return@mapNotNull null
                val image = fixUrlNull(person.selectFirst("img")?.attr("src"))
                Actor(actorName, image)
            }

        val episodes = document.select(".se-c .episodios li, #seasons .se-c li, li.mark-episode")
            .mapNotNull { it.toEpisode() }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url.toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = description
                this.year = year
                this.tags = tags
                addActors(actors)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
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
        val pageUrl = try {
            parseJson<String>(data)
        } catch (_: Throwable) {
            data
        }

        val document = app.get(pageUrl).document
        val playerOptions = document.select(".dooplay_player_option")

        playerOptions.forEach { player ->
            val post = player.attr("data-post")
            val nume = player.attr("data-nume")
            val type = player.attr("data-type").ifBlank { "movie" }

            if (post.isBlank() || nume.isBlank()) return@forEach

            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to post,
                    "nume" to nume,
                    "type" to type,
                ),
                referer = pageUrl,
            ).text

            val embedUrl = response.substringAfter("src=\"", "").substringBefore("\"").ifBlank { null }
                ?: response.substringAfter("data-src=\"", "").substringBefore("\"").ifBlank { null }
                ?: Regex("""https?://[^"'\\s<>]+""").find(response)?.value

            val fixed = embedUrl?.let { fixUrl(it) } ?: return@forEach
            loadExtractor(fixed, pageUrl, subtitleCallback, callback)
        }

        document.select("iframe").mapNotNull { it.attr("src").takeIf(String::isNotBlank) }.forEach {
            loadExtractor(fixUrl(it), pageUrl, subtitleCallback, callback)
        }

        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = selectFirst("a")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst("h3 a, .title a, .data h3 a, .data .title a, img")
            ?.attr("title")
            ?.takeIf { it.isNotBlank() }
            ?: selectFirst("h3 a, .title a, .data h3 a, .data .title a")
                ?.text()
                ?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val poster = fixUrlNull(
            selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: selectFirst("img")?.attr("src")
        )
        val typeText = (
            selectFirst(".type, .metadata, .flag")?.text()
                ?: attr("class")
                ?: href
            ).lowercase()

        return if (typeText.contains("tv") || typeText.contains("show") || href.contains("/tvshows/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private fun Element.toEpisode(): Episode? {
        val anchor = selectFirst("a") ?: return null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val episodeName = selectFirst(".episodiotitle a, .episode-title, .numerando + a")
            ?.text()
            ?.trim()
            ?.ifBlank { null }
            ?: anchor.text().trim().ifBlank { null }
        val meta = selectFirst(".numerando, .date")?.text().orEmpty()
        val season = Regex("""(\d+)\s*x\s*(\d+)""").find(meta)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episode = Regex("""(\d+)\s*x\s*(\d+)""").find(meta)?.groupValues?.getOrNull(2)?.toIntOrNull()
        val poster = fixUrlNull(selectFirst("img")?.attr("src"))

        return newEpisode(href.toJson()) {
            this.name = episodeName
            this.season = season
            this.episode = episode
            this.posterUrl = poster
        }
    }

    private fun fixUrlNull(url: String?): String? {
        return url?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
    }
}
