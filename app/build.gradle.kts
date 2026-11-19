plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
val directUpdatesEnabled = providers.gradleProperty("gradey.directUpdatesEnabled").orElse("true").get().toBoolean()
val intercomRegion = providers.gradleProperty("gradey.intercomRegion").orElse("us").get().ifBlank { "us" }.lowercase()
require(intercomRegion in setOf("us", "eu", "aus")) { "gradey.intercomRegion must be us, eu or aus." }

android {
    namespace = "com.bukovinafilip.gradey"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bukovinafilip.gradey"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("gradey.versionCode").orElse("8").get().toInt()
        versionName = providers.gradleProperty("gradey.versionName").orElse("0.5").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["intercomRegionResource"] = "@integer/intercom_server_region_$intercomRegion"

        buildConfigField("String", "SUPABASE_URL", "\"${providers.gradleProperty("gradey.supabaseUrl").orElse("").get()}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${providers.gradleProperty("gradey.supabaseAnonKey").orElse("").get()}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${providers.gradleProperty("gradey.googleWebClientId").orElse("").get()}\"")
        buildConfigField("String", "APPLE_OAUTH_CLIENT_ID", "\"${providers.gradleProperty("gradey.appleOAuthClientId").orElse("").get()}\"")
        buildConfigField("String", "REVENUECAT_ANDROID_KEY", "\"${providers.gradleProperty("gradey.revenueCatAndroidKey").orElse("").get()}\"")
        buildConfigField("String", "INTERCOM_ANDROID_API_KEY", "\"${providers.gradleProperty("gradey.intercomAndroidApiKey").orElse("").get()}\"")
        buildConfigField("String", "INTERCOM_APP_ID", "\"${providers.gradleProperty("gradey.intercomAppId").orElse("").get()}\"")
        buildConfigField("boolean", "DIRECT_UPDATES_ENABLED", directUpdatesEnabled.toString())
    }

    // Direct installs update in place only when every release is signed with the key used since 0.2.1.
    // The default is that key (the local Android debug keystore); override it with gradey.signing.* properties.
    signingConfigs {
        create("directUpdate") {
            storeFile = file(providers.gradleProperty("gradey.signing.storeFile").orElse("${System.getProperty("user.home")}/.android/debug.keystore").get())
            storePassword = providers.gradleProperty("gradey.signing.storePassword").orElse("android").get()
            keyAlias = providers.gradleProperty("gradey.signing.keyAlias").orElse("androiddebugkey").get()
            keyPassword = providers.gradleProperty("gradey.signing.keyPassword").orElse("android").get()
        }
    }

    buildTypes {
        release {
            // Users get an optimized, non-debuggable build: R8, resource shrinking and the baseline profile.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("directUpdate")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // This permission must not be present in Google Play builds.
    if (directUpdatesEnabled) {
        sourceSets.getByName("debug").manifest.srcFile("src/directUpdates/AndroidManifest.xml")
        sourceSets.getByName("release").manifest.srcFile("src/directUpdates/AndroidManifest.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-domain"))
    implementation(project(":core-network"))
    implementation(project(":core-data"))
    implementation(project(":core-ui"))
    implementation(project(":feature-auth"))
    implementation(project(":feature-login"))
    implementation(project(":feature-today"))
    implementation(project(":feature-subjects"))
    implementation(project(":feature-absence"))
    implementation(project(":feature-timetable"))
    implementation(project(":feature-stravacz"))
    implementation(project(":feature-account"))
    implementation(project(":feature-ai"))
    implementation(project(":feature-recap"))
    implementation(project(":feature-planner"))
    implementation(project(":feature-support"))
    implementation(project(":glance-widgets"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.firebase.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.firebase.messaging.ktx)
    implementation(libs.google.identity.googleid)
    implementation(libs.google.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    // Installs the baseline profile on devices where the store has not delivered one, such as direct APK installs.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.purchases)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

// A demo preview must be an explicit release choice, never an accidental store build.
val allowDemoRelease = providers.gradleProperty("gradey.allowDemoRelease").orElse("false").map(String::toBoolean)
// A direct GitHub preview ships with Gradey ID accounts only, like 0.4; AI, Apple, purchases and push stay unconfigured.
val directPreview = providers.gradleProperty("gradey.directPreview").orElse("false").map(String::toBoolean)
val accountReleaseProperties = listOf("gradey.supabaseUrl", "gradey.supabaseAnonKey", "gradey.googleWebClientId")
val missingAccountProperties = accountReleaseProperties.filter { providers.gradleProperty(it).orNull.isNullOrBlank() }
val missingReleaseProperties = (accountReleaseProperties + listOf("gradey.appleOAuthClientId", "gradey.revenueCatAndroidKey"))
    .filter { providers.gradleProperty(it).orNull.isNullOrBlank() }
val verifyReleaseConfiguration by tasks.registering {
    val intentionalDemo = allowDemoRelease.get()
    val preview = directPreview.get()
    val missingProperties = missingReleaseProperties.toList()
    val missingAccounts = missingAccountProperties.toList()
    val firebaseConfigured = layout.projectDirectory.file("google-services.json").asFile.isFile
    inputs.property("firebaseConfigured", firebaseConfigured)
    doLast {
        if (intentionalDemo) return@doLast
        if (preview) {
            check(missingAccounts.isEmpty()) {
                "A direct preview needs Gradey ID accounts: ${missingAccounts.joinToString()} missing. For a demo-only build, set gradey.allowDemoRelease=true."
            }
            return@doLast
        }
        check(missingProperties.isEmpty()) {
            "Live release configuration missing: ${missingProperties.joinToString()}. Set gradey.directPreview=true for an accounts-only GitHub preview or gradey.allowDemoRelease=true for a demo."
        }
        check(firebaseConfigured) {
            "Live releases require app/google-services.json from the deployed Gradey AI Firebase project. AI and Android push cannot work without it."
        }
    }
}
tasks.matching { it.name in listOf("packageRelease", "bundleRelease", "assembleRelease") }.configureEach { dependsOn(verifyReleaseConfiguration) }
