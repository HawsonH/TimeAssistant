plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.hawson.timeassistant.openaioauth"
    compileSdk = 36
    defaultConfig { minSdk = 24; manifestPlaceholders["appAuthRedirectScheme"] = "timeassistant-unused" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation("net.openid:appauth:0.11.1")
    implementation("com.nimbusds:nimbus-jose-jwt:10.6")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
