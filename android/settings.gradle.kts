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
        // jros2-android is not on Maven Central; it is hosted on IHMC's own repository.
        maven { url = uri("https://robotlabfiles.ihmc.us/repository/") }
    }
}

rootProject.name = "siyi_mk15_teleop"
include(":app")
