pluginManagement {
    repositories {
        google()
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

rootProject.name = "cascade-mesh"
include(":app")
include(":domain", ":pipeline", ":crypto", ":transport", ":simulator")
// Using published Arti AAR; local module not included
