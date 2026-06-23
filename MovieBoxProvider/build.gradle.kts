version = 2

cloudstream {
    description = "MovieBox - search & stream movies/series from MovieBox API"
    authors = listOf("MovieBox")

    status = 1 // 0=Down, 1=Ok, 2=Slow, 3=Beta-only

    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )

    language = "en"

    iconUrl = "https://moviebox-fastapi.vercel.app/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
