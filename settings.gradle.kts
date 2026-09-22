pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android\\..*")
                includeGroupByRegex("com\\.google\\.firebase.*")
                includeGroupByRegex("com\\.google\\.gms.*")
                includeGroupByRegex("com\\.google\\.testing.*")
            }
        }
        mavenCentral {
            content {
                excludeGroupByRegex("com\\.android.*")
                excludeGroupByRegex("androidx.*")
                excludeGroupByRegex("com\\.google\\.android\\..*")
                excludeGroupByRegex("com\\.google\\.firebase.*")
                excludeGroupByRegex("com\\.google\\.gms.*")
                excludeGroupByRegex("com\\.google\\.testing.*")
            }
        }
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android\\..*")
                includeGroupByRegex("com\\.google\\.firebase.*")
                includeGroupByRegex("com\\.google\\.gms.*")
                includeGroupByRegex("com\\.google\\.testing.*")
            }
        }
        mavenCentral {
            content {
                excludeGroupByRegex("com\\.android.*")
                excludeGroupByRegex("androidx.*")
                excludeGroupByRegex("com\\.google\\.android\\..*")
                excludeGroupByRegex("com\\.google\\.firebase.*")
                excludeGroupByRegex("com\\.google\\.gms.*")
                excludeGroupByRegex("com\\.google\\.testing.*")
            }
        }
    }
}

rootProject.name = "GradeyAndroid"

include(
    ":app",
    ":core-model",
    ":core-domain",
    ":core-network",
    ":core-data",
    ":core-ui",
    ":feature-auth",
    ":feature-login",
    ":feature-today",
    ":feature-subjects",
    ":feature-absence",
    ":feature-timetable",
    ":feature-stravacz",
    ":feature-account",
    ":feature-ai",
    ":glance-widgets",
    ":wear",
)
