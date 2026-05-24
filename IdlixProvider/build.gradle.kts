// Use an integer for version numbers
version = 4

cloudstream {
    description = "Idlix - port of qolbudr/idlix-addons-stremio"
    authors = listOf("qolbudr (original)", "ported to Cloudstream")

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

    iconUrl = "https://z1.idlixku.com/favicon.png"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
