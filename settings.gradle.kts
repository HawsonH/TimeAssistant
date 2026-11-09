pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.application" || requested.id.id == "com.android.library") {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TimeAssistant"
include(":app")
include(":core:domain", ":core:serialization", ":core:design", ":core:navigation")
include(":engine:ai", ":integration:calendar", ":feature:onboarding")
include(":core:network", ":data:local", ":integration:ai-compatible")
include(":integration:quota-sub2api")
include(":feature:settings", ":feature:chat")
if (providers.gradleProperty("withOpenAiOAuth").orElse("true").get().toBoolean()) {
    include(":integration:oauth-openai")
}
 
