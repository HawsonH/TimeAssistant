// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

val checkModuleBoundaries by tasks.registering {
    group = "verification"
    description = "Reject implementation dependencies between independently replaceable modules."
    doLast {
        val allowed = mapOf(
            ":core:domain" to emptySet(),
            ":core:serialization" to emptySet(),
            ":core:design" to emptySet(),
            ":core:navigation" to emptySet(),
            ":core:network" to setOf(":core:domain"),
            ":data:local" to setOf(":core:domain", ":core:network"),
            ":engine:ai" to setOf(":core:domain", ":core:serialization", ":core:network"),
            ":integration:ai-compatible" to setOf(":core:domain", ":core:network"),
            ":integration:quota-sub2api" to setOf(":core:domain", ":core:network"),
            ":integration:oauth-openai" to setOf(":core:domain", ":core:network"),
            ":feature:settings" to setOf(":core:domain", ":core:design", ":core:navigation"),
            ":feature:chat" to setOf(":core:domain", ":core:design"),
            ":integration:calendar" to emptySet(),
            ":feature:onboarding" to setOf(":core:domain", ":core:design", ":core:navigation")
        )
        subprojects.filter { it.path != ":app" }.forEach { module ->
            val dependencies = module.configurations.flatMap { it.dependencies }
                .filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
                .map { it.path }.filter { it != module.path }.toSet()
            check(dependencies.all { it in allowed.getValue(module.path) }) {
                "${module.path} has forbidden dependencies: $dependencies"
            }
        }
        val domainSources = fileTree("core/domain/src/main/java") { include("**/*.java") }
        domainSources.forEach {
            check(!Regex("""import\s+(android\.|androidx\.|org\.json\.)""").containsMatchIn(it.readText())) {
                "Pure domain must not import Android or JSON APIs: $it"
            }
        }
    }
}

subprojects {
    if (providers.gradleProperty("lowMemoryTests").orNull == "true") {
        tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
            minHeapSize = "32m"
            maxHeapSize = "192m"
            maxParallelForks = 1
        }
    }
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("checkModuleBoundaries"))
    }
}
