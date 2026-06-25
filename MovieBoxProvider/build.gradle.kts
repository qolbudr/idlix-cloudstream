version = 3

cloudstream {
    description = "MovieBox provider"
    authors = listOf("MovieBox")

    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
    )

    language = "en"

    iconUrl = "https://moviebox-api-theta.vercel.app/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
