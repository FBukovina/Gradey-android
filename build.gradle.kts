plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.google.services) apply false
}

val localBuildRoot = File(System.getProperty("user.home"), "Library/Caches/GradeyAndroid-build")

allprojects {
    val projectBuildPath = if (path == ":") "root" else path.removePrefix(":").replace(':', '/')
    layout.buildDirectory.set(localBuildRoot.resolve(projectBuildPath))
}

// Every Compose module shares one stability configuration, and can write compiler reports with
// -Pgradey.composeReports=true (build/compose-reports) to audit skippability.
val composeStability = layout.projectDirectory.file("compose-stability.conf")
val composeReports = providers.gradleProperty("gradey.composeReports").orElse("false").map(String::toBoolean)
subprojects {
    plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
            stabilityConfigurationFiles.add(composeStability)
            if (composeReports.get()) {
                reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
                metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
            }
        }
    }
}

tasks.register<Delete>("clean") {
    delete(localBuildRoot)
}
