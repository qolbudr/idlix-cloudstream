package com.moviebox

/* ---------- /search ---------- */

data class MovieBoxSearchResponse(
    val status: String? = null,
    val total_results: Int? = null,
    val results: List<MovieBoxSearchResult>? = null,
)

data class MovieBoxSearchResult(
    val subject_id: String? = null,
    val title: String? = null,
    val type: String? = null, // "movie" | "series"
    val poster: String? = null,
    val year: String? = null,
    val rating: Double? = null,
    val seasons: Int? = null,
    val languages: List<String>? = null,
    val country: String? = null,
    val description: String? = null,
    val has_resource: Boolean? = null,
)

/* ---------- /get_download_links ---------- */

data class MovieBoxDownloadResponse(
    val status: String? = null,
    val subject_id: String? = null,
    val total_links: Int? = null,
    val seasons_found: List<Int>? = null,
    val episodes_per_season: Map<String, Int>? = null,
    val resolutions_found: List<Int>? = null,
    val download_links: List<MovieBoxDownloadLink>? = null,
)

data class MovieBoxDownloadLink(
    val url: String? = null,
    val resolution: Int? = null,
    val size: String? = null, // "356567015" in bytes as string
    val season: Int? = null,
    val episode: Int? = null,
    val resource_id: String? = null,
    val codec: String? = null,
    val duration: Int? = null,
    val subtitles_available: Boolean? = null,
    val has_arabic_subtitle: Boolean? = null,
    val arabic_subtitle_url: String? = null,
    val all_subtitles: List<MovieBoxSubtitle>? = null,
    val total_languages: Int? = null,
)

/* ---------- /get_subtitles ---------- */

data class MovieBoxSubtitlesResponse(
    val status: String? = null,
    val subject_id: String? = null,
    val resource_id: String? = null,
    val has_arabic: Boolean? = null,
    val arabic_subtitle: MovieBoxSubtitle? = null,
    val all_subtitles: List<MovieBoxSubtitle>? = null,
)

/* ---------- shared subtitle ---------- */

data class MovieBoxSubtitle(
    val language_code: String? = null,
    val language_name: String? = null,
    val url: String? = null,
    val size: Int? = null,
    val delay: Int? = null,
)

/* ---------- payload encoded in LoadResponse.dataUrl ---------- */

data class MovieBoxLoadData(
    val subjectId: String,
    val type: String, // "movie" | "series"
    val season: Int = 0,
    val episode: Int = 0,
)
