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
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
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
     * z2.idlixku.com is fronted by Cloudflare. On real devices we let
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
     * Browser-shaped headers for HLS playback.
     *
     * Why so many headers: in the browser the same playlist plays
     * smoothly, but ExoPlayer rebuffers ("dikit-load dikit-load").
     * Two CDN behaviours conspire here:
     *
     *  1. UA-based throttling — Android UAs get ~1 MB/s, browser-style
     *     requests (Chrome UA + Sec-Fetch-* + Connection: keep-alive)
     *     get ~2 MB/s. Verified with curl side-by-side.
     *
     *  2. Per-segment host fan-out — majorplay's variant playlists
     *     spread 358 segments across ~15 unique CDN hosts
     *     (g6.wiseacademia.asia, g6.akademivo.website, …). Each new
     *     host costs a TCP+TLS handshake; ExoPlayer's default Cronet
     *     transport doesn't share that pool with our pre-fetch path.
     *
     * Fix:
     *  - Stamp the same browser fingerprint we already use for the
     *    master playlist on every segment.
     *  - Override [getVideoInterceptor] so ExoPlayer routes segment
     *    requests through Cloudstream's shared OkHttp client, reusing
     *    the connections we just opened for the master. (See
     *    `CS3IPlayer#createVideoSource` — any non-null interceptor
     *    selects `OkHttpDataSource` over Cronet.)
     */
    private val playbackUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** RESOLUTION=1280x720 → captures the height (720). */
    private val STREAM_INF_RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)

    /** NAME="720p" — used as a fallback when RESOLUTION is missing (majorplay's case). */
    private val STREAM_INF_NAME = Regex("""NAME="([^"]+)"""", RegexOption.IGNORE_CASE)

    /** BANDWIDTH=3500000 — informational, useful for the lowest-bandwidth pick. */
    private val STREAM_INF_BANDWIDTH = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)

    /** "720p" / "1080P" inside a NAME attribute. */
    private val HEIGHT_FROM_NAME = Regex("""\d{3,4}""")

    private fun playbackHeaders(origin: String) = mapOf(
        "User-Agent" to playbackUserAgent,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9,id;q=0.8",
        "Origin" to origin,
        "Referer" to "$origin/",
        // Browser fingerprint — doubles segment throughput on
        // majorplay's CDN (verified ~1 MB/s vs ~2 MB/s).
        "Connection" to "keep-alive",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
    )

    /**
     * Force ExoPlayer onto Cloudstream's shared OkHttp client.
     *
     * In `CS3IPlayer#createVideoSource`, when this returns null the
     * player builds either a `CronetDataSource` or a
     * `DefaultHttpDataSource`. Both keep their own connection pools,
     * so the TCP+TLS sessions we just opened to the master playlist
     * (and to each of majorplay's ~15 segment hosts) cannot be reused.
     * Result: every host transition on the variant playlist costs a
     * fresh handshake (~300 ms) and the player rebuffers.
     *
     * Returning any non-null interceptor selects
     * `OkHttpDataSource.Factory(app.baseClient.newBuilder()...)` —
     * the same client every `app.get()` uses — so connection reuse
     * across segments and across hosts becomes effectively free.
     *
     * The interceptor itself only stamps headers defensively, in case
     * ExoPlayer's `setDefaultRequestProperties` is dropped on a
     * cross-host redirect inside the playlist.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        // Only swap transports for our own playback links — leave
        // unrelated extractors (e.g. external subtitles fetched by
        // other code paths) on whatever default they expect.
        if (extractorLink.source != name) return null

        val origin = runCatching { originOf(extractorLink.url) }.getOrNull() ?: return null
        val baseHeaders = playbackHeaders(origin)

        return Interceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
            // Don't fight the player on Range — only fill in headers
            // that aren't already present so seeking/byte-range fetches
            // keep working.
            baseHeaders.forEach { (key, value) ->
                if (original.header(key) == null) builder.header(key, value)
            }
            chain.proceed(builder.build())
        }
    }

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

        emitPlaybackLinks(streamUrl, callback)

        redeem.subtitles.forEach { sub ->
            val label = sub.label ?: sub.lang ?: "Subtitle"
            subtitleCallback(newSubtitleFile(label, sub.path))
        }

        return true
    }

    /**
     * Resolve the redeem-returned master playlist into one
     * [ExtractorLink] per variant (e.g. 720p, 1080p) instead of handing
     * the master to the player.
     *
     * Why not let ExoPlayer's ABR handle the master directly?
     * majorplay's variant playlists fan every 5-second segment out
     * across dozens of unique CDN hostnames (g6.wiseacademia.asia,
     * g6.akademivo.website, g6.aspireheightsacademy.digital, …). With
     * no socket reuse between segments, per-segment fetch time gets
     * noisy enough that ABR misreads it as bandwidth drops and starts
     * oscillating between variants. Each switch flushes the buffer —
     * exactly the "sometimes buffering" pattern users hit on long
     * series episodes.
     *
     * Pinning to a single variant up front removes the oscillation:
     * the player loads one media playlist, Cloudstream's quality
     * profile picks the best, and the user can fall back to a lower
     * variant if their connection is weak.
     *
     * If the master ever fails to parse (e.g. majorplay returns a
     * media-only playlist), we fall back to handing the original URL
     * through unchanged.
     */
    private suspend fun emitPlaybackLinks(
        streamUrl: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val origin = originOf(streamUrl)
        val headers = playbackHeaders(origin)

        val variants = runCatching { parseHlsVariants(streamUrl, headers) }
            .getOrDefault(emptyList())

        if (variants.isEmpty()) {
            // Defensive fallback — keep the user able to play even if
            // the master shape changes upstream.
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = "$origin/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return
        }

        variants.forEach { v ->
            callback(
                newExtractorLink(
                    source = name,
                    name = if (v.height > 0) "$name ${v.height}p" else name,
                    url = v.url,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = "$origin/"
                    this.quality = qualityForHeight(v.height)
                    // Forwarded by ExoPlayer on every segment + media
                    // playlist request — keeps majorplay's CDN from
                    // throttling because we look like a real browser.
                    this.headers = headers
                }
            )
        }
    }

    private data class HlsVariant(val url: String, val height: Int, val bandwidth: Int)

    /**
     * Minimal #EXT-X-STREAM-INF parser. We only need enough to map a
     * variant URI to a height and bandwidth — the full HLS grammar lives
     * in the player.
     */
    private suspend fun parseHlsVariants(
        masterUrl: String,
        headers: Map<String, String>,
    ): List<HlsVariant> {
        val text = app.get(masterUrl, headers = headers, timeout = 30L).text
        if (!text.contains("#EXT-X-STREAM-INF")) return emptyList()

        val lines = text.lines()
        val out = mutableListOf<HlsVariant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // The URI is the next non-blank, non-comment line.
                var j = i + 1
                while (j < lines.size) {
                    val candidate = lines[j].trim()
                    if (candidate.isNotEmpty() && !candidate.startsWith("#")) break
                    j++
                }
                if (j >= lines.size) break

                val uri = lines[j].trim()
                val resolution = STREAM_INF_RESOLUTION.find(line)?.groupValues?.get(1)?.toIntOrNull()
                val nameAttr = STREAM_INF_NAME.find(line)?.groupValues?.get(1)
                val bandwidth = STREAM_INF_BANDWIDTH.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val height = resolution
                    ?: nameAttr?.let { HEIGHT_FROM_NAME.find(it)?.value?.toIntOrNull() }
                    ?: 0

                out += HlsVariant(
                    url = resolveAgainst(masterUrl, uri),
                    height = height,
                    bandwidth = bandwidth,
                )
                i = j + 1
            } else {
                i++
            }
        }
        return out
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

    /**
     * Resolves [ref] against [baseUrl] using HLS-flavored relative URI
     * rules. Handles absolute URLs, protocol-relative URLs, absolute
     * paths, and the common case of a sibling file
     * ("data-287173.json" relative to ".../config-545186.json?t=…").
     *
     * majorplay quirk: the master playlist (config-*.json?t=…&pm=browser)
     * lists its variants as bare paths without any query string, but
     * the CDN rejects every variant/segment fetch that doesn't carry
     * the same `?t=…` token (HTTP 401). Browsers happen to inherit it
     * because they resolve relative URIs against the *full* master URL,
     * query and all. So when [ref] doesn't carry its own query, we
     * graft the master's query+fragment onto the resolved URL.
     */
    private fun resolveAgainst(baseUrl: String, ref: String): String {
        // Pull the master's query/fragment up front — every branch below
        // needs it for the no-own-query case.
        val baseQueryAndFragment = run {
            val q = baseUrl.indexOf('?')
            val f = baseUrl.indexOf('#')
            val cut = when {
                q >= 0 && f >= 0 -> minOf(q, f)
                q >= 0 -> q
                f >= 0 -> f
                else -> -1
            }
            if (cut < 0) "" else baseUrl.substring(cut)
        }

        fun withInheritedQuery(resolved: String): String =
            if (resolved.contains('?') || resolved.contains('#') || baseQueryAndFragment.isEmpty()) {
                resolved
            } else {
                resolved + baseQueryAndFragment
            }

        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        if (ref.startsWith("//")) {
            val scheme = baseUrl.substringBefore("://", "https")
            return withInheritedQuery("$scheme:$ref")
        }
        if (ref.startsWith("/")) {
            return withInheritedQuery(originOf(baseUrl) + ref)
        }
        // Strip query + fragment from base before taking the parent dir,
        // then re-attach the master's query if the variant didn't supply
        // one of its own.
        val cleanBase = baseUrl.substringBefore('?').substringBefore('#')
        val parent = cleanBase.substringBeforeLast('/', cleanBase)
        return withInheritedQuery("$parent/$ref")
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

    /** Returns scheme+host of [url], e.g. "https://e2e.majorplay.net". */
    private fun originOf(url: String): String {
        val schemeIdx = url.indexOf("://")
        if (schemeIdx <= 0) return mainUrl
        val pathIdx = url.indexOf('/', schemeIdx + 3)
        return if (pathIdx < 0) url else url.substring(0, pathIdx)
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
