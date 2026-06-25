package com.moviebox

/* ---------- /search ---------- */

data class SearchResponseBody(
    val status: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("total_results")
    val totalResults: Int? = null,
    val results: List<SearchResult>? = null,
)

data class SearchResult(
    @com.fasterxml.jackson.annotation.JsonProperty("subject_id")
    val subjectId: String? = null,
    val title: String? = null,
    val type: String? = null, // "movie" or "series"
    val poster: String? = null,
    val year: String? = null,
    val rating: Double? = null,
    val seasons: Int? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("duration_seconds")
    val durationSeconds: Long? = null,
    val languages: List<String>? = null,
    val country: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("has_resource")
    val hasResource: Boolean? = null,
)

/* ---------- /get_download_links ---------- */

data class DownloadLinksResponse(
    val status: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("subject_id")
    val subjectId: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("total_links")
    val totalLinks: Int? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("seasons_found")
    val seasonsFound: List<Int>? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("episodes_per_season")
    val episodesPerSeason: Map<String, Int>? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("resolutions_found")
    val resolutionsFound: List<Int>? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("download_links")
    val downloadLinks: List<DownloadLink>? = null,
)

data class DownloadLink(
    val url: String? = null,
    val resolution: Int? = null,
    val size: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("resource_id")
    val resourceId: String? = null,
    val codec: String? = null,
    val duration: Long? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("source_url")
    val sourceUrl: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("subtitles_available")
    val subtitlesAvailable: Boolean? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("has_arabic_subtitle")
    val hasArabicSubtitle: Boolean? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("arabic_subtitle_url")
    val arabicSubtitleUrl: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("all_subtitles")
    val allSubtitles: List<SubtitleInfo>? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("total_languages")
    val totalLanguages: Int? = null,
)

data class SubtitleInfo(
    @com.fasterxml.jackson.annotation.JsonProperty("language_code")
    val languageCode: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("language_name")
    val languageName: String? = null,
    val url: String? = null,
    val size: Long? = null,
    val delay: Int? = null,
)

/* ---------- /get_subtitles (fallback) ---------- */

data class SubtitlesResponse(
    val status: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("subject_id")
    val subjectId: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("resource_id")
    val resourceId: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("has_arabic")
    val hasArabic: Boolean? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("arabic_subtitle")
    val arabicSubtitle: Any? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("all_subtitles")
    val allSubtitles: List<SubtitleInfo>? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("total_languages")
    val totalLanguages: Int? = null,
)

/* ---------- payload encoded in LoadResponse.data ---------- */

data class LoadData(
    @com.fasterxml.jackson.annotation.JsonProperty("subject_id")
    val subjectId: String,
    val season: Int? = null,
    val episode: Int? = null,
)
