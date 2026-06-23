package com.moviebox

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile

class MovieBoxProvider : MainAPI() {
    override var mainUrl = "https://moviebox-fastapi.vercel.app"
    override var name = "MovieBox"
    override val hasMainPage = false
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        return newHomePageResponse(request.name, emptyList<SearchResponse>())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search?query=$encoded&original_language=en&limit=20"
        val res = app.get(url, timeout = 30L)
            .parsedSafe<MovieBoxSearchResponse>()
        return res?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val path = url.substringAfter(mainUrl).trimStart('/')
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) throw ErrorLoadingException("Invalid URL: $url")
        if (segments[0] !in listOf("movie", "series")) throw ErrorLoadingException("Unknown type: ${segments[0]}")

        val type = segments[0]
        val subjectId = segments[1]
        val isSeries = type == "series"

        if (isSeries) {
            val downloadRes = app.get(
                "$mainUrl/get_download_links?subject_id=$subjectId",
                timeout = 30L,
            ).parsedSafe<MovieBoxDownloadResponse>()

            val episodeData = downloadRes ?: MovieBoxDownloadResponse()
            val episodes = buildEpisodes(subjectId, episodeData)

            return newTvSeriesLoadResponse("Series", url, TvType.TvSeries, episodes) {
                // API provides no detail endpoint — metadata shown from search
            }
        } else {
            val loadData = MovieBoxLoadData(subjectId = subjectId, type = "movie").toJson()
            return newMovieLoadResponse("Movie", url, TvType.Movie, loadData) {
                // API provides no detail endpoint — metadata shown from search
            }
        }
    }

    private fun buildEpisodes(subjectId: String, res: MovieBoxDownloadResponse): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seasons = res.seasons_found.orEmpty()
            .ifEmpty { listOf(1) } // fallback if seasons_found empty
        val epPerSeason = res.episodes_per_season.orEmpty()

        for (seasonNum in seasons) {
            val epCount = epPerSeason[seasonNum.toString()]?.takeIf { it > 0 } ?: 1
            for (epNum in 1..epCount) {
                val data = MovieBoxLoadData(
                    subjectId = subjectId,
                    type = "series",
                    season = seasonNum,
                    episode = epNum,
                ).toJson()
                episodes += newEpisode(data) {
                    this.season = seasonNum
                    this.episode = epNum
                    this.name = "E$epNum"
                }
            }
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsed = runCatching { parseJson<MovieBoxLoadData>(data) }.getOrNull() ?: return false

        val res = app.get(
            "$mainUrl/get_download_links?subject_id=${parsed.subjectId}",
            timeout = 30L,
        ).parsedSafe<MovieBoxDownloadResponse>() ?: return false

        val links = res.download_links.orEmpty().filter { link ->
            if (parsed.type == "series" && parsed.season > 0) {
                link.season == parsed.season && link.episode == parsed.episode
            } else if (parsed.type == "series") {
                // Fallback: no season filter — return all
                true
            } else {
                // Movie — return all resolutions (season/episode is null in API)
                true
            }
        }

        for (link in links) {
            val videoUrl = link.url ?: continue
            val quality = link.resolution ?: 0
            val label = "${quality}p"
            val sizeStr = link.size?.toLongOrNull()
                ?.let { bytes ->
                    val mb = bytes / (1024 * 1024)
                    if (mb > 0) " ($mb MB)" else ""
                } ?: ""

            callback(
                newExtractorLink(
                    source = name,
                    name = "$label$sizeStr",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.quality = qualityForHeight(quality)
                    this.referer = "$mainUrl/"
                }
            )

            // Subtitles from download link response
            link.all_subtitles?.forEach { sub ->
                val subUrl = sub.url ?: return@forEach
                val label = sub.language_name
                    ?: sub.language_code
                    ?: "Subtitle"
                subtitleCallback(newSubtitleFile(label, subUrl))
            }
        }

        return links.isNotEmpty()
    }

    private fun qualityForHeight(height: Int): Int = when {
        height >= 2160 -> Qualities.P2160.value
        height >= 1440 -> Qualities.P1440.value
        height >= 1080 -> Qualities.P1080.value
        height >= 720 -> Qualities.P720.value
        height >= 480 -> Qualities.P480.value
        height >= 360 -> Qualities.P360.value
        height >= 240 -> Qualities.P240.value
        height >= 144 -> Qualities.P144.value
        else -> Qualities.Unknown.value
    }

    /* ---------- mappers ---------- */

    private fun MovieBoxSearchResult.toSearchResponse(): SearchResponse? {
        val subjId = subject_id ?: return null
        val t = title ?: return null
        val poster = poster
        val y = year?.toIntOrNull()
        val isMovie = type == "movie"

        val searchUrl = "$mainUrl/${type ?: "movie"}/$subjId"
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

        return if (isMovie) {
            newMovieSearchResponse(t, searchUrl, tvType) {
                this.posterUrl = poster
                this.year = y
            }
        } else {
            newTvSeriesSearchResponse(t, searchUrl, tvType) {
                this.posterUrl = poster
                this.year = y
            }
        }
    }
}
