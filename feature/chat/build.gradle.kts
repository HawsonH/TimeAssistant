plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.hawson.timeassistant.chatfeature"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:design"))
    implementation(libs.appcompat)
    implementation(libs.material)
}
