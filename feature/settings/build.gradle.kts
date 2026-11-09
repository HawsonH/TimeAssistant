plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.hawson.timeassistant.settingsfeature"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    buildFeatures { viewBinding = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:design"))
    implementation(project(":core:navigation"))
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
}
