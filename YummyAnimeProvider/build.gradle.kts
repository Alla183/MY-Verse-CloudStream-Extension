version = 26

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "uk"

    authors = listOf("Alla183")
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("Anime")
    iconUrl = "https://site.yummyani.me/favicon.ico"
}

dependencies {
    // FIXME remove this when crossplatform is fully supported
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
