pluginManagement {
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "AWA"
include(":app")

includeBuild("E:/Project Files/Others/Android Webcam Project/RootEncoder/RootEncoder") {
    dependencySubstitution {
        substitute(module("com.github.pedroSG94.RootEncoder:library"))
            .using(project(":library"))
        substitute(module("com.github.pedroSG94.RootEncoder:encoder"))
            .using(project(":encoder"))
        substitute(module("com.github.pedroSG94.RootEncoder:rtsp"))
            .using(project(":rtsp"))
    }
}
includeBuild("E:/Project Files/Others/Android Webcam Project/RootEncoder/RTSP-Server") {
    dependencySubstitution {
        substitute(module("com.github.pedroSG94:RTSP-Server"))
            .using(project(":rtspserver"))
    }
}