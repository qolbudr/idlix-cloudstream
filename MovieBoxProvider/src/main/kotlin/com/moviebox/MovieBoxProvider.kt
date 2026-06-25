package com.moviebox

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class MovieBoxProvider : MainAPI() {
    override var mainUrl = "https://moviebox-fastapi.vercel.app"
    override var name = "MovieBox"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    private val api = mainUrl

    private val apiHeaders = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    )

    /**
     * Homepage rows built from search queries since there's no browse endpoint.
     */
    override val mainPage = mainPageOf(
        "$api/search?query=action&original_language=en&limit=20" to "Action Movies",
        "$api/search?query=comedy&original_language=en&limit=20" to "Comedy Movies",
        "$api/search?query=drama&original_language=en&limit=20" to "Drama Movies",
        "$api/search?query=horror&original_language=en&limit=20" to "Horror Movies",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val res = app.get(url, headers = apiHeaders, timeout = 30L).parsedSafe<SearchResponseBody>()
        val items = res?.results?.mapNotNull {
            if (it.hasResource == true) it.toSearchResponse() else null
        } ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$api/search?query=$q&original_language=en&limit=30"
        val res = app.get(url, headers = apiHeaders, timeout = 30L).parsedSafe<SearchResponseBody>()
        return res?.results?.mapNotNull {
            if (it.hasResource == true) it.toSearchResponse() else null
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // URL format: $api/meta/$subjectId?title=...&poster=...&year=...&type=movie|series
        val uri = java.net.URI(url)
        val subjectId = uri.path.substringAfterLast("/")
        if (subjectId.isBlank()) throw ErrorLoadingException("Invalid subject ID")

        val qParams = uri.query?.split("&").orEmpty()
            .mapNotNull { param ->
                val eq = param.indexOf("=")
                if (eq < 0) return@mapNotNull null
                param.substring(0, eq) to java.net.URLDecoder.decode(param.substring(eq + 1), "UTF-8")
            }.toMap()

        val title = qParams["title"] ?: "Unknown"
        val poster = qParams["poster"]?.ifBlank { null }
        val year = qParams["year"]?.toIntOrNull()
        val contentType = qParams["type"]

        // Fetch download links for resolution/season info
        val linksUrl = "$api/get_download_links?subject_id=$subjectId"
        val linksRes = app.get(linksUrl, headers = apiHeaders, timeout = 30L)
            .parsedSafe<DownloadLinksResponse>()
            ?: throw ErrorLoadingException("No data for subject $subjectId")

        val isSeries = linksRes.seasonsFound?.isNotEmpty() == true || contentType == "series"

        return if (isSeries) {
            val episodes = buildEpisodes(subjectId, linksRes)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            val data = LoadData(subjectId = subjectId).toJson()
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun buildEpisodes(subjectId: String, res: DownloadLinksResponse): List<Episode> {
        val seen = mutableSetOf<Pair<Int, Int>>()
        val episodes = mutableListOf<Episode>()

        res.downloadLinks.orEmpty().forEach { link ->
            val season = link.season ?: 1
            val ep = link.episode ?: 1
            val key = Pair(season, ep)
            if (key in seen) return@forEach
            seen += key

            val data = LoadData(subjectId = subjectId, season = season, episode = ep).toJson()
            episodes += newEpisode(data) {
                this.name = "S${season}E${ep}"
                this.season = season
                this.episode = ep
            }
        }

        return episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 1 }))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsed = runCatching { parseJson<LoadData>(data) }.getOrNull() ?: return false
        if (parsed.subjectId.isBlank()) return false

        val linksUrl = "$api/get_download_links?subject_id=${parsed.subjectId}"
        val res = app.get(linksUrl, headers = apiHeaders, timeout = 30L)
            .parsedSafe<DownloadLinksResponse>() ?: return false

        val filtered = res.downloadLinks.orEmpty().filter { link ->
            if (parsed.season != null && parsed.episode != null) {
                link.season == parsed.season && link.episode == parsed.episode
            } else {
                true
            }
        }

        if (filtered.isEmpty()) return false

        filtered.forEach { link ->
            val streamUrl = link.url ?: return@forEach
            val label = if (link.resolution != null) "${link.resolution}p" else "Auto"
            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.quality = qualityForResolution(link.resolution ?: 0)
                    this.referer = "$api/"
                }
            )
        }

        // Emit subtitles — download links rarely include them,
        // so call /get_subtitles endpoint as fallback per resource.
        var subtitlesEmitted = false
        filtered.forEach { link ->
            val rid = link.resourceId ?: return@forEach

            // Check if inline subtitles exist first
            if (!link.allSubtitles.isNullOrEmpty()) {
                link.allSubtitles.forEach { sub ->
                    val subUrl = sub.url ?: return@forEach
                    val label = sub.languageName ?: sub.languageCode ?: "Subtitle"
                    subtitleCallback(newSubtitleFile(label, subUrl))
                }
                subtitlesEmitted = true
                return@forEach
            }

            // Fallback: fetch from /get_subtitles endpoint
            if (!subtitlesEmitted) {
                runCatching {
                    val subUrl = "$api/get_subtitles?subject_id=${parsed.subjectId}&resource_id=$rid"
                    val subRes = app.get(subUrl, headers = apiHeaders, timeout = 15L)
                        .parsedSafe<SubtitlesResponse>()
                    subRes?.allSubtitles?.forEach { sub ->
                        val sUrl = sub.url ?: return@forEach
                        val label = sub.languageName ?: sub.languageCode ?: "Subtitle"
                        subtitleCallback(newSubtitleFile(label, sUrl))
                    }
                }
                subtitlesEmitted = true
            }
        }

        return true
    }

    private fun qualityForResolution(resolution: Int): Int = when {
        resolution >= 2160 -> Qualities.P2160.value
        resolution >= 1440 -> Qualities.P1440.value
        resolution >= 1080 -> Qualities.P1080.value
        resolution >= 720 -> Qualities.P720.value
        resolution >= 480 -> Qualities.P480.value
        resolution >= 360 -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    /* ---------- mappers ---------- */

    private fun SearchResult.toSearchResponse(): SearchResponse? {
        val t = title ?: return null
        val id = subjectId ?: return null
        val posterUrl = poster
        val y = year?.substringBefore("-")?.toIntOrNull()

        // Store metadata in URL so load() can use it (no detail API endpoint)
        val detailUrl = buildString {
            append("$api/meta/$id")
            append("?title=${java.net.URLEncoder.encode(t, "UTF-8")}")
            if (posterUrl != null) append("&poster=${java.net.URLEncoder.encode(posterUrl, "UTF-8")}")
            if (y != null) append("&year=$y")
            append("&type=${type ?: "movie"}")
        }

        return if (type == "series") {
            newTvSeriesSearchResponse(t, detailUrl, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = y
            }
        } else {
            newMovieSearchResponse(t, detailUrl, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = y
            }
        }
    }
}
