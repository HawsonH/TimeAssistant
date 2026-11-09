plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
dependencies {
    api(project(":core:domain"))
    testImplementation(libs.junit)
}
