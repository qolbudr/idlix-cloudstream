package com.moviebox

/* ---------- /search ---------- */

data class SearchResponseBody(
    val status: String? = null,
    val total_results: Int? = null,
    val results: List<SearchResult>? = null,
)

data class SearchResult(
    val subject_id: String? = null,
    val title: String? = null,
    val type: String? = null, // "movie" or "series"
    val poster: String? = null,
    val year: String? = null,
    val rating: Double? = null,
    val seasons: Int? = null,
    val duration_seconds: Long? = null,
    val languages: List<String>? = null,
    val country: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val has_resource: Boolean? = null,
)

/* ---------- /get_download_links ---------- */

data class DownloadLinksResponse(
    val status: String? = null,
    val subject_id: String? = null,
    val total_links: Int? = null,
    val seasons_found: List<Int>? = null,
    val episodes_per_season: Map<String, Int>? = null,
    val resolutions_found: List<Int>? = null,
    val download_links: List<DownloadLink>? = null,
)

data class DownloadLink(
    val url: String? = null,
    val resolution: Int? = null,
    val size: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val resource_id: String? = null,
    val codec: String? = null,
    val duration: Long? = null,
    val source_url: String? = null,
    val subtitles_available: Boolean? = null,
    val has_arabic_subtitle: Boolean? = null,
    val arabic_subtitle_url: String? = null,
    val all_subtitles: List<SubtitleInfo>? = null,
    val total_languages: Int? = null,
)

data class SubtitleInfo(
    val language_code: String? = null,
    val language_name: String? = null,
    val url: String? = null,
    val size: Long? = null,
    val delay: Int? = null,
)

/* ---------- /get_subtitles (fallback) ---------- */

data class SubtitlesResponse(
    val status: String? = null,
    val subject_id: String? = null,
    val resource_id: String? = null,
    val has_arabic: Boolean? = null,
    val arabic_subtitle: Any? = null,
    val all_subtitles: List<SubtitleInfo>? = null,
    val total_languages: Int? = null,
)

/* ---------- payload encoded in LoadResponse.data ---------- */

data class LoadData(
    val subject_id: String,
    val season: Int? = null,
    val episode: Int? = null,
)
