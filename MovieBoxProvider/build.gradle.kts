version = 2

cloudstream {
    description = "MovieBox - movies and series from moviebox-fastapi"
    authors = listOf("ahmedio3 (original API)", "ported to Cloudstream")

    status = 1

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
