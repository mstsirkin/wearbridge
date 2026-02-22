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

rootProject.name = "WearBridge"
include(":wearbridge-phone")
project(":wearbridge-phone").projectDir = file("app")

include(":wearbridge-watch")
project(":wearbridge-watch").projectDir = file("watch")
