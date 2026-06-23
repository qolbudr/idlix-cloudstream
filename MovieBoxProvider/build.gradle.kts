// Use an integer for version numbers
version = 3

cloudstream {
    description = "Moviebox plugins for cloudstream"
    authors = listOf("qolbudr (original)")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama",
    )

    language = "id"

    iconUrl = "https://themoviebox.xyz/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
