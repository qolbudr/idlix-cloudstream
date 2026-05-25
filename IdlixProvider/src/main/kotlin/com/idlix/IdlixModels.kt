/*
 * Ported from https://github.com/qolbudr/idlix-addons-stremio
 * Original Stremio addon by qolbudr — released under MIT.
 *
 * Reuses the same backend (z2.idlixku.com) and play-info → claim → redeem
 * flow described in src/wrapper/idlix.ts and src/wrapper/proxyClient.ts.
 * Cloudstream runs natively on the device, so we don't need the
 * proxy-cloudflare-mocha hop — we just chain cookies between play-info and
 * claim through NiceHttp.
 */
package com.idlix

/* ---------- /api/movies, /api/series, /api/browse, /api/homepage ---------- */

data class ApiResponse(
    val data: List<ApiItem> = emptyList(),
    val pagination: Pagination? = null,
)

data class ApiItem(
    val id: String? = null,
    val title: String? = null,
    val slug: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val voteAverage: String? = null,
    val quality: String? = null,
    val country: String? = null,
    val runtime: Int? = null,
    val numberOfSeasons: Int? = null,
    val numberOfEpisodes: Int? = null,
    val contentType: String? = null,
    val originalLanguage: String? = null,
)

data class Pagination(
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val totalPages: Int? = null,
)

/* ---------- /api/movies/{slug}, /api/series/{slug} ---------- */

data class DetailResponse(
    val id: String? = null,
    val title: String? = null,
    val slug: String? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val logoPath: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val runtime: Int? = null,
    val voteAverage: Any? = null,
    val originalLanguage: String? = null,
    val country: String? = null,
    val status: String? = null,
    val trailerUrl: String? = null,
    val quality: String? = null,
    val director: String? = null,
    val genres: List<IdlixGenre>? = null,
    val cast: List<IdlixCast>? = null,
    val seasons: List<IdlixSeason>? = null, // TV only
    val firstSeason: IdlixSeason? = null,
    val numberOfSeasons: Int? = null,
)

data class IdlixGenre(
    val id: String? = null,
    val name: String? = null,
    val slug: String? = null,
)

data class IdlixCast(
    val id: String? = null,
    val name: String? = null,
    val character: String? = null,
    val profilePath: String? = null,
)

data class IdlixSeason(
    val id: String? = null,
    val seasonNumber: Int? = null,
    val name: String? = null,
    val posterPath: String? = null,
    val episodes: List<IdlixEpisode>? = null,
)

data class IdlixEpisode(
    val id: String? = null,
    val episodeNumber: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val stillPath: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
    val voteAverage: Any? = null,
)

data class SeasonWrapper(
    val season: IdlixSeason? = null,
)

/* ---------- /api/search ---------- */

data class SearchApiResponse(
    val results: List<SearchApiResult> = emptyList(),
    val total: Long? = null,
)

data class SearchApiResult(
    val id: String,
    val contentType: String,
    val title: String,
    val originalTitle: String? = null,
    val overview: String? = null,
    val originalLanguage: String? = null,
    val voteAverage: Double? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val slug: String,
    val firstAirDate: String? = null,
    val numberOfSeasons: Long? = null,
    val releaseDate: String? = null,
    val quality: String? = null,
)

/* ---------- /api/watch/play-info → /api/watch/session/claim → redeemUrl ---------- */

data class PlayInfoRes(
    val gateToken: String,
    val serverNow: Long = 0L,
    val unlockAt: Long = 0L,
)

data class ClaimRes(
    val kind: String? = null,
    val claim: String,
    val redeemUrl: String? = null,
    val videoId: String? = null,
    val title: String? = null,
    val durationSec: Long? = null,
    val viewerTier: String? = null,
    val maxHeight: Long? = null,
)

data class RedeemRes(
    val code: String? = null,
    val url: String? = null,
    val expiresAt: Long? = null,
    val subtitles: List<RedeemSubtitle> = emptyList(),
    val videoId: String? = null,
)

data class RedeemSubtitle(
    val lang: String? = null,
    val label: String? = null,
    val path: String,
)

/* ---------- payload encoded in LoadResponse.dataUrl ---------- */

data class LoadData(
    val id: String,
    /** "movie" → /api/watch/play-info/movie/{id}, "episode" → /api/watch/play-info/episode/{id} */
    val type: String,
)
