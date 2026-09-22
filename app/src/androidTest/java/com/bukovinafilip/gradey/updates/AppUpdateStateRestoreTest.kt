package com.bukovinafilip.gradey.updates

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import com.bukovinafilip.gradey.BuildConfig
import com.bukovinafilip.gradey.network.GradeyJson
import com.bukovinafilip.gradey.network.UpdateRelease
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh managers restore state; download requests are captured without workers or network calls. */
@RunWith(AndroidJUnit4::class)
class AppUpdateStateRestoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("gradey-app-updates", Context.MODE_PRIVATE)
    private var savedPreferences: Map<String, *> = emptyMap<String, Any>()
    private val token = "a1234567-1234-1234-1234-123456789abc"
    private val release get() = UpdateRelease(
        versionCode = BuildConfig.VERSION_CODE + 10,
        versionName = "future-test",
        packageName = context.packageName,
        minSdk = 26,
        apkUrl = "https://github.com/FBukovina/Gradey-android/releases/download/test/gradey.apk",
        sha256 = "a".repeat(64),
        sizeBytes = 1024,
    )

    @Before fun isolateSavedState() {
        savedPreferences = preferences.all.toMap()
        assumeTrue(BuildConfig.DIRECT_UPDATES_ENABLED)
        assumeTrue(AppUpdateManager(context).state.value.phase != UpdatePhase.STORE_MANAGED)
        preferences.edit().clear().putBoolean("automaticChecks", false).commit()
    }

    @After fun restoreSavedState() {
        val editor = preferences.edit().clear()
        savedPreferences.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.commit()
    }

    @Test fun interruptedDownloadRestoresAsQueuedAndIgnoresStaleGenerations() = runBlocking {
        saveRelease()
        preferences.edit().putString("phase", "DOWNLOADING").putString("downloadToken", token)
            .putBoolean("downloadManual", true).commit()
        val manager = AppUpdateManager(context)
        assertEquals(UpdatePhase.QUEUED, manager.state.value.phase)
        assertTrue(manager.state.value.manualDownload)
        assertEquals(release, manager.state.value.release)
        assertNull(manager.beginDownload("b1234567-1234-1234-1234-123456789abc"))
        assertEquals(UpdatePhase.QUEUED, manager.state.value.phase)
        assertEquals(release, manager.beginDownload(token))
        assertEquals(UpdatePhase.DOWNLOADING, manager.state.value.phase)
        manager.downloadStopped(token, "A verified test failure", retry = false)
        assertFalse(preferences.contains("downloadToken"))
        val restored = AppUpdateManager(context).state.value
        assertEquals(UpdatePhase.ERROR, restored.phase)
        assertEquals("A verified test failure", restored.message)
        // A late worker callback from the old generation must not overwrite a terminal failure.
        manager.downloadStopped(token, "Late old worker", retry = true)
        assertEquals(UpdatePhase.ERROR, manager.state.value.phase)
        assertEquals("A verified test failure", manager.state.value.message)
    }

    @Test fun invalidGenerationCannotRestoreAQueuedDownload() {
        saveRelease()
        preferences.edit().putString("phase", "QUEUED").putString("downloadToken", "../../untrusted").commit()
        assertEquals(UpdatePhase.AVAILABLE, AppUpdateManager(context).state.value.phase)
    }

    @SuppressLint("RestrictedApi") // Inspect the captured WorkRequest without scheduling real network work.
    @Test fun downloadNowReplacesWifiQueueOnceAndRestoresManualChoice() = runBlocking {
        saveRelease()
        preferences.edit().putString("phase", "QUEUED").putString("downloadToken", token)
            .putBoolean("downloadManual", false).putBoolean("wifiOnly", true).commit()
        val requests = mutableListOf<OneTimeWorkRequest>()
        val manager = AppUpdateManager(context) { requests += it }
        assertFalse(manager.state.value.manualDownload)

        manager.downloadNow()
        assertEquals(1, requests.size)
        assertEquals(NetworkType.CONNECTED, requests.single().workSpec.constraints.requiredNetworkType)
        val manualToken = preferences.getString("downloadToken", null)
        assertNotEquals(token, manualToken)
        assertEquals(manualToken, requests.single().workSpec.input.getString("token"))
        assertTrue(manager.state.value.manualDownload)
        assertTrue(AppUpdateManager(context).state.value.manualDownload)
        // Repeated taps keep the same queued manual request, rather than restarting its work.
        manager.downloadNow()
        assertEquals(1, requests.size)
        assertEquals(manualToken, preferences.getString("downloadToken", null))
        // An old worker finishing after replacement cannot change the new generation.
        manager.downloadStopped(token, "Old Wi-Fi request stopped", retry = true)
        assertNotEquals("Old Wi-Fi request stopped", manager.state.value.message)
        assertTrue(manager.state.value.manualDownload)
    }

    @SuppressLint("RestrictedApi") // Inspect the captured WorkRequest without scheduling real network work.
    @Test fun enablingChecksResumesAnAvailableAutomaticDownload() = runBlocking {
        saveRelease()
        preferences.edit().putString("phase", "AVAILABLE").putInt("canceledVersion", release.versionCode)
            .putBoolean("automaticChecks", false).putBoolean("automaticDownloads", true)
            .putBoolean("wifiOnly", true).commit()
        val requests = mutableListOf<OneTimeWorkRequest>()
        val manager = AppUpdateManager(context) { requests += it }
        try {
            manager.setAutomaticChecks(true)
            assertEquals(1, requests.size)
            assertEquals(NetworkType.UNMETERED, requests.single().workSpec.constraints.requiredNetworkType)
            assertFalse(preferences.contains("canceledVersion"))
            assertEquals(UpdatePhase.QUEUED, manager.state.value.phase)
            assertFalse(manager.state.value.manualDownload)
        } finally {
            // Cancel the delayed periodic check; captured APK requests are never submitted.
            manager.setAutomaticChecks(false)
        }
    }

    @Test fun checkFailureWithoutAReleaseRetainsItsMessageAfterRestart() {
        preferences.edit().putString("phase", "ERROR").putString("message", "Check your connection and try again.").commit()
        val state = AppUpdateManager(context).state.value
        assertEquals(UpdatePhase.ERROR, state.phase)
        assertEquals("Check your connection and try again.", state.message)
        assertNull(state.release)
    }

    @Test fun recentFailedAttemptThrottlesAutomaticChecksAcrossProcessRestarts() = runBlocking {
        preferences.edit().putBoolean("automaticChecks", true).putString("phase", "ERROR")
            .putString("message", "Previous offline attempt")
            .putLong("lastAttemptAt", System.currentTimeMillis()).commit()
        val manager = AppUpdateManager(context)
        manager.checkForUpdates(automatic = true)
        assertEquals(UpdatePhase.ERROR, manager.state.value.phase)
        assertEquals("Previous offline attempt", manager.state.value.message)
        assertEquals(0L, manager.state.value.lastCheckedAt)
    }

    @Test fun recentlySuccessfulCheckRetainsTheTwelveHourIntervalAfterRestart() = runBlocking {
        val successful = System.currentTimeMillis() - 60 * 60 * 1000
        preferences.edit().putBoolean("automaticChecks", true).putLong("lastCheckedAt", successful).commit()
        val manager = AppUpdateManager(context)
        manager.checkForUpdates(automatic = true)
        assertEquals(successful, manager.state.value.lastCheckedAt)
        assertEquals(UpdatePhase.IDLE, manager.state.value.phase)
        assertNull(manager.state.value.message)
    }

    private fun saveRelease() {
        val json = GradeyJson.encodeToJsonElement(UpdateRelease.serializer(), release) as JsonObject
        preferences.edit().putString("release", JsonObject(json + ("target" to JsonPrimitive("phone"))).toString()).commit()
    }
}
