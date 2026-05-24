/*
 * Ported from https://github.com/qolbudr/idlix-addons-stremio
 * Original Stremio addon by qolbudr — released under MIT.
 *
 * The play-info → wait(unlockAt) → claim → redeem(majorplay) flow follows
 * `src/wrapper/idlix.ts#getPlayData`. We keep cookies and User-Agent across
 * the chain manually because the server binds the playback session to
 * (cookie, IP) — see `proxyClient.ts#ProxySession` for the original notes.
 */
package com.idlix

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
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
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z1.idlixku.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
    )

    private val tmdbImg = "https://image.tmdb.org/t/p/w342"
    private val tmdbImgLarge = "https://image.tmdb.org/t/p/w500"
    private val tmdbImgEpisode = "https://image.tmdb.org/t/p/w300"
    private val tmdbImgActor = "https://image.tmdb.org/t/p/w185"

    /**
     * z1.idlixku.com is fronted by Cloudflare. On real devices we let
     * Cloudstream's WebView-based killer fetch cf_clearance once per
     * session and reuse it for every subsequent request.
     */
    private val cfKiller by lazy { CloudflareKiller() }

    /** Catalogs surface up as homepage rows; mirrors the Stremio addon manifest. */
    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?page=%d&limit=30&sort=createdAt" to "Movie Terbaru",
        "$mainUrl/api/series?page=%d&limit=30&sort=createdAt" to "TV Series Terbaru",
        "$mainUrl/api/browse?page=%d&limit=30&sort=latest&network=netflix" to "Netflix",
        "$mainUrl/api/browse?page=%d&limit=30&sort=latest&network=disney-plus" to "Disney+",
        "$mainUrl/api/browse?page=%d&limit=30&sort=latest&network=hbo" to "HBO",
        "$mainUrl/api/browse?page=%d&limit=30&sort=latest&network=prime-video" to "Amazon Prime",
        "$mainUrl/api/browse?page=%d&limit=30&sort=latest&network=apple-tv-plus" to "Apple TV+",
    )

    private fun apiHeaders(referer: String = "$mainUrl/") = mapOf(
        "Origin" to mainUrl,
        "Referer" to referer,
        "Accept" to "application/json, text/plain, */*",
    )

    /**
     * Accepts either the API URL we set on SearchResponse
     * ("$mainUrl/api/(movies|series)/$slug") or the canonical web URL
     * stored on LoadResponse ("$mainUrl/(movie|series)/$slug") and
     * returns the API endpoint. Cloudstream re-enters [load] with the
     * web URL when an item is opened from the featured slider, history,
     * bookmarks, or any other surface that round-trips through the
     * stored LoadResponse.url.
     */
    private fun toApiUrl(url: String): String {
        if (url.contains("/api/")) return url
        val path = url.substringAfter(mainUrl).trimStart('/')
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return url
        val slug = segments[1].substringBefore('?').substringBefore('#')
        return when (segments[0]) {
            "movie", "movies" -> "$mainUrl/api/movies/$slug"
            "series", "tv", "tv-series" -> "$mainUrl/api/series/$slug"
            else -> url
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val res = app.get(url, headers = apiHeaders(), interceptor = cfKiller, timeout = 30L)
            .parsedSafe<ApiResponse>()
            ?: return newHomePageResponse(request.name, emptyList<SearchResponse>())

        val items = res.data.mapNotNull { it.toSearchResponse() }
        val hasNext = res.pagination?.let { p ->
            val cur = p.page ?: page
            val total = p.totalPages ?: Int.MAX_VALUE
            cur < total
        } ?: items.isNotEmpty()

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // /api/search returns movies and TV series interleaved by relevance
        // when no `type` filter is set. Calling once without `type` keeps
        // the server-side ranking intact — splitting the request into two
        // typed calls would surface every movie before any series, which
        // looks like "no series found" if the user only scans the top
        // rows.
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/search?q=$encoded&page=1&limit=40"
        val res = app.get(url, headers = apiHeaders(), interceptor = cfKiller)
            .parsedSafe<SearchApiResponse>()
        return res?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // Cloudstream may call load() with either the API URL we set on
        // SearchResponse ("$mainUrl/api/(movies|series)/$slug") or the web
        // URL stored as the LoadResponse's canonical url
        // ("$mainUrl/(movie|series)/$slug") — featured slider, history and
        // bookmarks all use the latter. Normalize both shapes to the API
        // endpoint before fetching.
        val apiUrl = toApiUrl(url)
        val res = app.get(apiUrl, headers = apiHeaders(), interceptor = cfKiller)
            .parsedSafe<DetailResponse>()
            ?: throw ErrorLoadingException("Invalid response from $apiUrl")

        val isSeries = !res.seasons.isNullOrEmpty() || res.numberOfSeasons != null
        val title = res.title ?: res.slug ?: "Unknown"
        val poster = res.posterPath?.let { "$tmdbImgLarge$it" }
        val backdrop = res.backdropPath?.let { "$tmdbImgLarge$it" }
        val logo = res.logoPath?.let { "$tmdbImgLarge$it" }
        val year = (res.releaseDate ?: res.firstAirDate)
            ?.substringBefore("-")
            ?.toIntOrNull()
        val tags = res.genres?.mapNotNull { it.name }.orEmpty()
        val actors = res.cast.orEmpty().mapNotNull { c ->
            val actorName = c.name ?: return@mapNotNull null
            Actor(actorName, c.profilePath?.let { "$tmdbImgActor$it" })
        }
        val score = res.voteAverage?.toString()?.let { Score.from10(it) }
        val webUrl = if (isSeries) "$mainUrl/series/${res.slug}" else "$mainUrl/movie/${res.slug}"

        return if (isSeries) {
            val episodes = collectEpisodes(res)
            newTvSeriesLoadResponse(title, webUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster ?: logo
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = res.overview
                this.tags = tags
                this.score = score
                this.duration = res.runtime
                addActors(actors)
                addTrailer(res.trailerUrl)
                addImdbId(res.imdbId)
                addTMDbId(res.tmdbId)
            }
        } else {
            val movieData = LoadData(id = res.id ?: "", type = "movie").toJson()
            newMovieLoadResponse(title, webUrl, TvType.Movie, movieData) {
                this.posterUrl = poster ?: logo
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = res.overview
                this.tags = tags
                this.score = score
                this.duration = res.runtime
                addActors(actors)
                addTrailer(res.trailerUrl)
                addImdbId(res.imdbId)
                addTMDbId(res.tmdbId)
            }
        }
    }

    /**
     * Pulls every season's episode list. The first season is usually inlined
     * as `firstSeason`; the remaining seasons need a separate
     * `/api/series/{slug}/season/{n}` call each.
     */
    private suspend fun collectEpisodes(res: DetailResponse): List<Episode> {
        val out = mutableListOf<Episode>()
        val slug = res.slug ?: return out

        fun addAll(seasonNum: Int?, eps: List<IdlixEpisode>?) {
            val n = seasonNum ?: return
            eps?.forEach { ep ->
                val epId = ep.id ?: return@forEach
                val data = LoadData(id = epId, type = "episode").toJson()
                out += newEpisode(data) {
                    this.name = ep.name
                    this.season = n
                    this.episode = ep.episodeNumber
                    this.description = ep.overview
                    this.posterUrl = ep.stillPath?.let { "$tmdbImgEpisode$it" }
                }
            }
        }

        addAll(res.firstSeason?.seasonNumber, res.firstSeason?.episodes)

        res.seasons.orEmpty().forEach { season ->
            val n = season.seasonNumber ?: return@forEach
            if (n == res.firstSeason?.seasonNumber) return@forEach
            runCatching {
                app.get(
                    "$mainUrl/api/series/$slug/season/$n",
                    headers = apiHeaders(),
                    interceptor = cfKiller,
                )
                    .parsedSafe<SeasonWrapper>()
                    ?.season
                    ?.let { addAll(it.seasonNumber ?: n, it.episodes) }
            }
        }

        return out
    }

    /**
     * Stremio addon: getStreams + getSubtitles share `getPlayData`. In
     * Cloudstream we fold both into `loadLinks`.
     *
     * Flow (see `src/wrapper/idlix.ts#getPlayData`):
     *   1. GET  /api/watch/play-info/{movie|episode}/{id}
     *      → sets a playback session cookie + returns gateToken,
     *        serverNow (unix-ms), unlockAt (unix-ms).
     *   2. wait at least (unlockAt - serverNow) ms — server enforces it.
     *   3. POST /api/watch/session/claim {gateToken}, same cookies
     *      → returns claim + redeemUrl (default https://e2e.majorplay.net/api/play).
     *   4. POST redeemUrl {claim, mode:"browser"} as application/json
     *      → returns {url: <hls>, subtitles: [...]}.
     *
     * NB — the original TS addon sends step 4 with contentType "text/plain",
     * but the upstream actually requires JSON; the live API rejects raw
     * text with `{code:"invalid-body", error:"expected { claim: string,
     * mode?: \"browser\" | \"cast\" }"}`. Verified 2026-05-23.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsed = runCatching { parseJson<LoadData>(data) }.getOrNull() ?: return false
        if (parsed.id.isBlank()) return false

        val redeem = fetchPlayData(parsed) ?: return false
        val streamUrl = redeem.url?.takeIf { it.isNotBlank() } ?: return false

        // Parse the master HLS playlist ourselves so each variant gets a
        // proper quality label like "Idlix 1080p" / "Idlix 720p" instead
        // of duplicate "Idlix" entries (the default M3u8Helper behavior
        // when a variant has BANDWIDTH but no RESOLUTION).
        val emitted = emitVariants(streamUrl, callback)
        if (!emitted) {
            // Fallback: hand the master URL to the player and let it pick.
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        redeem.subtitles.forEach { sub ->
            val label = sub.label ?: sub.lang ?: "Subtitle"
            subtitleCallback(newSubtitleFile(label, sub.path))
        }

        return true
    }

    /**
     * Internal: runs the play-info → claim → redeem chain and returns the
     * raw redeem payload. Pulled out so smoke tests can exercise the same
     * code path without going through `loadLinks`.
     */
    internal suspend fun fetchPlayData(parsed: LoadData): RedeemRes? {
        val baseHeaders = apiHeaders()
        val jsonMedia = "application/json".toMediaType()

        // 1) play-info — capture cookies for the rest of the chain
        val playInfoRes = app.get(
            "$mainUrl/api/watch/play-info/${parsed.type}/${parsed.id}",
            headers = baseHeaders,
            interceptor = cfKiller,
        )
        val cookies = playInfoRes.cookies
        val playInfo = playInfoRes.parsedSafe<PlayInfoRes>() ?: return null
        if (playInfo.gateToken.isBlank()) return null

        // 2) Wait for the gate. unlockAt and serverNow are unix-ms.
        val waitMs = (playInfo.unlockAt - playInfo.serverNow).coerceAtLeast(0L)
        if (waitMs > 0) delay(waitMs + 250)

        // 3) Claim
        val claimBody = """{"gateToken":"${playInfo.gateToken}"}""".toRequestBody(jsonMedia)
        val claim = app.post(
            "$mainUrl/api/watch/session/claim",
            headers = baseHeaders,
            cookies = cookies,
            requestBody = claimBody,
            interceptor = cfKiller,
        ).parsedSafe<ClaimRes>() ?: return null

        if (claim.claim.isBlank()) return null
        val redeemUrl = claim.redeemUrl ?: "https://e2e.majorplay.net/api/play"

        // 4) Redeem — JSON body, server explicitly rejects text/plain.
        val redeemBody = """{"claim":"${claim.claim}","mode":"browser"}"""
            .toRequestBody(jsonMedia)
        return app.post(
            redeemUrl,
            headers = baseHeaders,
            requestBody = redeemBody,
        ).parsedSafe<RedeemRes>()
    }

    /**
     * Fetches the HLS master playlist at [masterUrl], parses each
     * `#EXT-X-STREAM-INF` variant, and emits one [ExtractorLink] per
     * variant labeled with its resolution (e.g. "Idlix 1080p"). Returns
     * `false` if the playlist could not be fetched, was not a master
     * (no variants), or any other unrecoverable parsing error happened —
     * the caller should fall back to a single master link in that case.
     */
    private suspend fun emitVariants(
        masterUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val body = runCatching {
            app.get(masterUrl, headers = mapOf("Referer" to "$mainUrl/")).text
        }.getOrNull() ?: return false

        if (!body.contains("#EXT-X-STREAM-INF")) return false

        // Resolve each variant URI relative to the master. We don't use
        // java.net.URI#resolve() to avoid pulling stricter validation on
        // the upstream URL; manual prefixing is enough here because the
        // server always returns absolute or root-relative paths.
        val masterBase = masterUrl.substringBeforeLast('/') + "/"
        val schemeAndHost = run {
            val schemeIdx = masterUrl.indexOf("://")
            if (schemeIdx <= 0) return@run ""
            val pathIdx = masterUrl.indexOf('/', schemeIdx + 3)
            if (pathIdx < 0) masterUrl else masterUrl.substring(0, pathIdx)
        }

        val variants = parseHlsVariants(body)
        if (variants.isEmpty()) return false

        var emitted = 0
        for (v in variants) {
            val absolute = when {
                v.uri.startsWith("http://") || v.uri.startsWith("https://") -> v.uri
                v.uri.startsWith("//") -> "https:${v.uri}"
                v.uri.startsWith("/") -> schemeAndHost + v.uri
                else -> masterBase + v.uri
            }
            val label = if (v.height != null) "$name ${v.height}p" else name
            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = absolute,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = v.height ?: Qualities.Unknown.value
                }
            )
            emitted++
        }
        return emitted > 0
    }

    /** Internal: a single HLS variant entry. */
    private data class HlsVariant(val height: Int?, val bandwidth: Long?, val uri: String)

    /**
     * Minimal HLS master parser: walks `#EXT-X-STREAM-INF:` lines and
     * pairs each with the next non-comment line (the variant URI).
     * Extracts RESOLUTION (height) and BANDWIDTH when present. Skips
     * lines we don't understand instead of throwing.
     */
    private fun parseHlsVariants(body: String): List<HlsVariant> {
        val out = mutableListOf<HlsVariant>()
        val lines = body.lineSequence().map { it.trim() }.toList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val attrs = line.substringAfter(':', "")
                val height = Regex("RESOLUTION=\\d+x(\\d+)")
                    .find(attrs)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val bandwidth = Regex("BANDWIDTH=(\\d+)")
                    .find(attrs)?.groupValues?.getOrNull(1)?.toLongOrNull()
                // Find the next URI line (skip comments and blanks).
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    out += HlsVariant(height, bandwidth, lines[j])
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return out
    }

    /* ---------- mappers ---------- */

    private fun ApiItem.toSearchResponse(): SearchResponse? {
        val t = title ?: return null
        val s = slug ?: return null
        val poster = posterPath?.let { "$tmdbImg$it" }
        val y = (releaseDate ?: firstAirDate)?.substringBefore("-")?.toIntOrNull()
        val q = getQualityFromString(quality)
        return if (contentType == "movie") {
            newMovieSearchResponse(t, "$mainUrl/api/movies/$s", TvType.Movie) {
                this.posterUrl = poster
                this.year = y
                this.quality = q
            }
        } else {
            newTvSeriesSearchResponse(t, "$mainUrl/api/series/$s", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = y
                this.quality = q
            }
        }
    }

    private fun SearchApiResult.toSearchResponse(): SearchResponse {
        val poster = posterPath?.let { "$tmdbImg$it" }
        val y = (releaseDate ?: firstAirDate)?.substringBefore("-")?.toIntOrNull()
        val q = getQualityFromString(quality)
        return if (contentType == "movie") {
            newMovieSearchResponse(title, "$mainUrl/api/movies/$slug", TvType.Movie) {
                this.posterUrl = poster
                this.year = y
                this.quality = q
            }
        } else {
            newTvSeriesSearchResponse(title, "$mainUrl/api/series/$slug", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = y
                this.quality = q
            }
        }
    }
}
