version = 13

cloudstream {
    authors     = listOf("CloudStreamTR", "Emre-Kahveci")
    language    = "tr"
    description = "CloudStreamHub Aggregator - TMDB Keşif & Tüm Türkçe Sağlayıcıları Birleştiren Süper Eklenti"
    status      = 1
    tvTypes     = listOf("Movie", "TvSeries", "Anime", "Cartoon", "Documentary")
    iconUrl     = "https://raw.githubusercontent.com/Emre-Kahveci/CloudStreamHub/main/assets/icon.png"
}

dependencies {
    implementation(project(":FilmMakinesi"))
    implementation(project(":HDFilmCehennemi"))
    implementation(project(":SinemaCX"))
    implementation(project(":SezonlukDizi"))
    implementation(project(":KultFilmler"))
    implementation(project(":Dizilla"))
    implementation(project(":DiziYou"))
    implementation(project(":HDFilmDelisi"))
    implementation(project(":JetFilmIzle"))
    implementation(project(":DiziKorea"))
    implementation(project(":YesilCamTv"))
}
