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
import com.lagradost.cloudstream3.getQualityFromString
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

    /**
     * Homepage rows built from search queries since there's no browse endpoint.
     */
    override val mainPage = mainPageOf(
        "$api/search?query=action&original_language=en&limit=20" to "Action",
        "$api/search?query=comedy&original_language=en&limit=20" to "Comedy",
        "$api/search?query=drama&original_language=en&limit=20" to "Drama",
        "$api/search?query=thriller&original_language=en&limit=20" to "Thriller",
        "$api/search?query=horror&original_language=en&limit=20" to "Horror",
        "$api/search?query=romance&original_language=en&limit=20" to "Romance",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val res = app.get(url, timeout = 30L).parsedSafe<SearchResponseBody>()
        val items = res?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$api/search?query=$q&original_language=en&limit=30"
        val res = app.get(url, timeout = 30L).parsedSafe<SearchResponseBody>()
        return res?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val subjectId = url.substringAfterLast("/")
        if (subjectId.isBlank()) throw ErrorLoadingException("Invalid subject ID")

        // Fetch download links to distinguish movie vs series + get metadata
        val linksUrl = "$api/get_download_links?subject_id=$subjectId"
        val linksRes = app.get(linksUrl, timeout = 30L).parsedSafe<DownloadLinksResponse>()
            ?: throw ErrorLoadingException("No data for subject $subjectId")

        // Get metadata from search
        val meta = runCatching {
            val searchUrl = "$api/search?query=$subjectId&original_language=en&limit=5"
            app.get(searchUrl, timeout = 30L).parsedSafe<SearchResponseBody>()
                ?.results?.firstOrNull { it.subject_id == subjectId }
        }.getOrNull()

        val title = meta?.title ?: "Unknown"
        val poster = meta?.poster
        val year = meta?.year?.substringBefore("-")?.toIntOrNull()
        val score = meta?.rating?.toString()?.let { Score.from10(it) }
        val tags = meta?.genre.orEmpty()
        val plot = meta?.description

        val isSeries = linksRes.seasons_found?.isNotEmpty() == true

        return if (isSeries) {
            val episodes = buildEpisodes(subjectId, linksRes)
            newTvSeriesLoadResponse(title, "$api/subject/$subjectId", TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
            }
        } else {
            val data = LoadData(subject_id = subjectId).toJson()
            newMovieLoadResponse(title, "$api/subject/$subjectId", TvType.Movie, data) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = score
            }
        }
    }

    /**
     * Build episode list from download links response.
     * Groups links by (season, episode) and creates one Episode per pair.
     */
    private fun buildEpisodes(subjectId: String, res: DownloadLinksResponse): List<Episode> {
        val seen = mutableSetOf<Pair<Int, Int>>()
        val episodes = mutableListOf<Episode>()

        res.download_links.orEmpty().forEach { link ->
            val season = link.season ?: 1
            val ep = link.episode ?: 1
            val key = Pair(season, ep)
            if (key in seen) return@forEach
            seen += key

            val data = LoadData(subject_id = subjectId, season = season, episode = ep).toJson()
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
        if (parsed.subject_id.isBlank()) return false

        // Fetch all download links for this subject
        val linksUrl = "$api/get_download_links?subject_id=${parsed.subject_id}"
        val res = app.get(linksUrl, timeout = 30L).parsedSafe<DownloadLinksResponse>() ?: return false

        // Filter by season/episode for series, or take all for movies
        val filtered = res.download_links.orEmpty().filter { link ->
            if (parsed.season != null && parsed.episode != null) {
                link.season == parsed.season && link.episode == parsed.episode
            } else {
                true // movie — all resolutions
            }
        }

        if (filtered.isEmpty()) return false

        // Emit one ExtractorLink per resolution
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

        // Emit subtitles from the first link that has them
        filtered.firstOrNull { !it.all_subtitles.isNullOrEmpty() }?.all_subtitles?.forEach { sub ->
            val subUrl = sub.url ?: return@forEach
            val label = sub.language_name ?: sub.language_code ?: "Subtitle"
            subtitleCallback(newSubtitleFile(label, subUrl))
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
        val id = subject_id ?: return null
        val posterUrl = poster
        val y = year?.substringBefore("-")?.toIntOrNull()

        return if (type == "series") {
            newTvSeriesSearchResponse(t, "$api/subject/$id", TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = y
            }
        } else {
            newMovieSearchResponse(t, "$api/subject/$id", TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = y
            }
        }
    }
}
