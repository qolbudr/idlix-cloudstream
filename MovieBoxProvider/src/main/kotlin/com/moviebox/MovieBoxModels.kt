package com.moviebox

import com.fasterxml.jackson.annotation.JsonProperty

/* ---------- /search ---------- */

data class SearchResponseBody(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("total_results") val totalResults: Int? = null,
    @JsonProperty("results") val results: List<SearchResult>? = null,
)

data class SearchResult(
    @JsonProperty("subject_id") val subjectId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("type") val type: String? = null, // "movie" or "series"
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("rating") val rating: Double? = null,
    @JsonProperty("seasons") val seasons: Int? = null,
    @JsonProperty("duration_seconds") val durationSeconds: Long? = null,
    @JsonProperty("languages") val languages: List<String>? = null,
    @JsonProperty("country") val country: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("genre") val genre: List<String>? = null,
    @JsonProperty("has_resource") val hasResource: Boolean? = null,
)

/* ---------- /get_download_links ---------- */

data class DownloadLinksResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("subject_id") val subjectId: String? = null,
    @JsonProperty("total_links") val totalLinks: Int? = null,
    @JsonProperty("seasons_found") val seasonsFound: List<Int>? = null,
    @JsonProperty("episodes_per_season") val episodesPerSeason: Map<String, Int>? = null,
    @JsonProperty("resolutions_found") val resolutionsFound: List<Int>? = null,
    @JsonProperty("download_links") val downloadLinks: List<DownloadLink>? = null,
)

data class DownloadLink(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("resolution") val resolution: Int? = null,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("resource_id") val resourceId: String? = null,
    @JsonProperty("codec") val codec: String? = null,
    @JsonProperty("duration") val duration: Long? = null,
    @JsonProperty("source_url") val sourceUrl: String? = null,
    @JsonProperty("subtitles_available") val subtitlesAvailable: Boolean? = null,
    @JsonProperty("has_arabic_subtitle") val hasArabicSubtitle: Boolean? = null,
    @JsonProperty("arabic_subtitle_url") val arabicSubtitleUrl: String? = null,
    @JsonProperty("all_subtitles") val allSubtitles: List<SubtitleInfo>? = null,
    @JsonProperty("total_languages") val totalLanguages: Int? = null,
)

data class SubtitleInfo(
    @JsonProperty("language_code") val languageCode: String? = null,
    @JsonProperty("language_name") val languageName: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("size") val size: Long? = null,
    @JsonProperty("delay") val delay: Int? = null,
)

/* ---------- /get_subtitles (fallback) ---------- */

data class SubtitlesResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("subject_id") val subjectId: String? = null,
    @JsonProperty("resource_id") val resourceId: String? = null,
    @JsonProperty("has_arabic") val hasArabic: Boolean? = null,
    @JsonProperty("arabic_subtitle") val arabicSubtitle: Any? = null,
    @JsonProperty("all_subtitles") val allSubtitles: List<SubtitleInfo>? = null,
    @JsonProperty("total_languages") val totalLanguages: Int? = null,
)

/* ---------- payload encoded in LoadResponse.data ---------- */

data class LoadData(
    @JsonProperty("subject_id") val subjectId: String,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
)
