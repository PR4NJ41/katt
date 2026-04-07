dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

version = 1

cloudstream {
    description = "KatmovieHD provider"
    authors = listOf("PR4NJ41")

    /**
    * Status int as one of the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta-only
    **/
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = true
    language = "hi"
    iconUrl = "https://katmoviehd.page/images/katmoviehd-icon.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
