plugins {
    alias(libs.plugins.android.application)
}
val withOpenAiOAuth = providers.gradleProperty("withOpenAiOAuth").orElse("true").get().toBoolean()
val deviceQa = providers.gradleProperty("deviceQa").orElse("false").get().toBoolean()
val interactionQa = providers.gradleProperty("interactionQa").orElse("false").get().toBoolean()

android {
    namespace = "com.hawson.timeassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hawson.timeassistant"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["appAuthRedirectScheme"] = "timeassistant-unused"

        testInstrumentationRunner = "com.hawson.timeassistant.QaTestRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = if (interactionQa) ".interaction.qa" else if (deviceQa) ".device.qa" else ".qa"
            versionNameSuffix = "-qa"
            manifestPlaceholders["qaAppLabel"] = if (interactionQa) "时屿 交互验收" else if (deviceQa) "时屿 设备验收" else "@string/app_name"
        }
        create("oauthCallback") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa.callback"
            versionNameSuffix = "-qa-callback"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
    testBuildType = providers.gradleProperty("qaTestBuildType").orElse("debug").get()
    sourceSets.getByName("oauthCallback").java.srcDir("src/debug/java")
    sourceSets.getByName("oauthCallback").manifest.srcFile("src/debug/AndroidManifest.xml")
    sourceSets.getByName("main").java.srcDir(
        if (withOpenAiOAuth) "src/oauthEnabled/java" else "src/oauthDisabled/java")
}

dependencies {
    implementation(project(":core:network"))
    implementation(project(":data:local"))
    implementation(project(":integration:ai-compatible"))
    implementation(project(":integration:quota-sub2api"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:chat"))
    if (withOpenAiOAuth) implementation(project(":integration:oauth-openai"))
    implementation(project(":core:domain"))
    implementation(project(":core:serialization"))
    implementation(project(":core:design"))
    implementation(project(":core:navigation"))
    implementation(project(":engine:ai"))
    implementation(project(":integration:calendar"))
    implementation(project(":feature:onboarding"))

    implementation(libs.appcompat)
    implementation(libs.browser)
    implementation(libs.material)
    implementation(libs.room.runtime)
    implementation(libs.work.runtime)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.viewmodel)
    annotationProcessor(libs.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
