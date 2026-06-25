package com.moviebox

/* ---------- API envelope ---------- */

data class MovieBoxEnvelope<T>(val status: String, val data: T)

/* ---------- Homepage (operatingList) ---------- */

data class MovieBoxHomepageData(
    val operatingList: List<MovieBoxSection> = emptyList(),
)

data class MovieBoxSection(
    val type: String? = null,
    val title: String? = null,
    val subjects: List<MovieBoxSubjectItem> = emptyList(),
)

/* ---------- Trending (subjectList) ---------- */

data class MovieBoxTrendingData(
    val subjectList: List<MovieBoxSubjectItem> = emptyList(),
)

/* ---------- Subject item (shared by homepage, trending, search) ---------- */

data class MovieBoxSubjectItem(
    val subjectId: String? = null,
    val subjectType: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val releaseDate: String? = null,
    val duration: Long? = null,
    val genre: String? = null,
    val cover: MovieBoxCover? = null,
    val countryName: String? = null,
    val imdbRatingValue: String? = null,
    val hasResource: Boolean? = null,
    val trailer: Any? = null,
    val detailPath: String? = null,
    val imdbRatingCount: Long? = null,
    val stills: Any? = null,
)

data class MovieBoxCover(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/* ---------- Info (subject detail + stars + resource) ---------- */

data class MovieBoxInfoData(
    val subject: MovieBoxSubjectDetail? = null,
    val stars: List<MovieBoxStar> = emptyList(),
    val resource: MovieBoxResource? = null,
)

data class MovieBoxSubjectDetail(
    val subjectId: String? = null,
    val subjectType: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val releaseDate: String? = null,
    val duration: Long? = null,
    val genre: String? = null,
    val cover: MovieBoxCover? = null,
    val countryName: String? = null,
    val imdbRatingValue: String? = null,
    val hasResource: Boolean? = null,
    val trailer: Any? = null,
    val detailPath: String? = null,
    val imdbRatingCount: Long? = null,
    val stills: Any? = null,
)

data class MovieBoxResource(
    val seasons: List<MovieBoxSeason>? = null,
    val source: String? = null,
    val uploadBy: String? = null,
)

data class MovieBoxSeason(
    val se: Int? = null,
    val maxEp: Int? = null,
)

data class MovieBoxStar(
    val staffId: String? = null,
    val staffType: Int? = null,
    val name: String? = null,
    val character: String? = null,
    val avatarUrl: String? = null,
)

/* ---------- Sources (downloads + captions) ---------- */

data class MovieBoxSourcesData(
    val downloads: List<MovieBoxDownload> = emptyList(),
    val captions: List<MovieBoxCaption> = emptyList(),
)

data class MovieBoxDownload(
    val id: String? = null,
    val url: String? = null,
    val resolution: Int? = null,
    val size: String? = null,
)

data class MovieBoxCaption(
    val id: String? = null,
    val lan: String? = null,
    val lanName: String? = null,
    val url: String? = null,
    val size: String? = null,
    val delay: Int? = null,
)

/* ---------- Search ---------- */

data class MovieBoxSearchData(
    val items: List<MovieBoxSearchItem> = emptyList(),
    val pager: MovieBoxPager? = null,
)

data class MovieBoxSearchItem(
    val subjectId: String? = null,
    val subjectType: Int? = null,
    val title: String? = null,
    val releaseDate: String? = null,
    val cover: MovieBoxCover? = null,
    val duration: Long? = null,
    val genre: String? = null,
    val countryName: String? = null,
    val imdbRatingValue: String? = null,
    val hasResource: Boolean? = null,
    val detailPath: String? = null,
)

data class MovieBoxPager(
    val page: Int? = null,
    val perPage: Int? = null,
    val totalCount: Int? = null,
    val hasMore: Boolean? = null,
)

/* ---------- Payload passed from load() to loadLinks() ---------- */

data class MovieBoxLoadData(
    val subjectId: String,
    val season: Int? = null,
    val episode: Int? = null,
)
