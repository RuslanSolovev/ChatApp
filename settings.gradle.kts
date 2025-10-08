pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "1.9.23"
        id("com.android.application") version "8.6.1"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/yandex-cloud/maven") }
        maven { url = uri("https://jitpack.io") } // Добавленный репозиторий
    }
}

rootProject.name = "Filosoff"
include(":app")