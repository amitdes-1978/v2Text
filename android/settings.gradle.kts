pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central first (avoids Central's 429 rate limits), Central as fallback
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central first (avoids Central's 429 rate limits), Central as fallback
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "v2Text"
include(":app")
