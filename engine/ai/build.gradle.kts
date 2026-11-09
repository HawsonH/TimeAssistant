plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.hawson.timeassistant.aiclient"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core:domain"))
    api(project(":core:network"))
    implementation(project(":core:serialization"))
    testImplementation(libs.junit)
}
