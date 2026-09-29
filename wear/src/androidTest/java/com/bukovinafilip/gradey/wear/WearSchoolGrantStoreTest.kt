package com.bukovinafilip.gradey.wear

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bukovinafilip.gradey.domain.WearSchoolProtocol
import com.bukovinafilip.gradey.model.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Android Keystore, synthetic credentials only. Never contacts a provider or paired phone. */
@RunWith(AndroidJUnit4::class)
class WearSchoolGrantStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = WearSchoolScope("synthetic-owner", "synthetic-school")
    private val node = "synthetic-watch"
    private val phone = "synthetic-phone"
    private val credentials = WearSchoolCredentials(SchoolProvider.BAKALARI, "https://school.example", "synthetic-user", "synthetic-secret", "student-1")
    private lateinit var access: WearSchoolAccess

    @Before fun prepare() {
        clear()
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assumeTrue("Requires the dedicated test watch to have a screen lock and be unlocked", keyguard.isDeviceSecure && !keyguard.isDeviceLocked)
        access = WearSchoolAccess(scope, listOf(WearSchoolNodeGrant(node, UUID.randomUUID().toString(), System.currentTimeMillis() + WearSchoolProtocol.LEASE_MILLIS - 60_000)))
        publish(access)
    }
    @After fun cleanup() = clear()
    private fun clear() {
        WearSchoolGrantStore.clear(context)
        context.getSharedPreferences("gradey-watch", 0).edit().clear().commit()
    }
    private fun publish(access: WearSchoolAccess?) {
        val payload = GradeyWearSyncPayload(generatedAtEpochMillis = System.currentTimeMillis(), isSignedIn = true, schoolAccess = access)
        context.getSharedPreferences("gradey-watch", 0).edit()
            .putString("snapshot", Json.encodeToString(GradeyWearSyncPayload.serializer(), payload)).putString("source-node", phone).commit()
        WearSchoolGrantStore.reconcile(context, payload, phone)
    }
    private fun response(request: WearSchoolProvisionRequest) = WearSchoolProtocol.seal(request, credentials, access.grants.single().expiresAtEpochMillis, System.currentTimeMillis())

    @Test fun androidKeystoreRoundTripStoresOnlyCiphertextAndRejectsReplay() {
        val request = WearSchoolGrantStore.createRequest(context, node, phone)
        val sealed = response(request)
        assertTrue(WearSchoolGrantStore.accept(context, phone, sealed))
        val stored = WearSchoolGrantStore.load(context)!!
        assertEquals(credentials.password, stored.credentials.password)
        assertNull(stored.session)
        assertFalse(WearSchoolGrantStore.accept(context, phone, sealed))
        val privateValues = context.getSharedPreferences("gradey-watch-school-private", 0).all.values.joinToString()
        assertFalse(privateValues.contains(credentials.password))
        assertFalse(privateValues.contains(credentials.username))
        assertFalse(context.getSharedPreferences("gradey-watch", 0).all.values.joinToString().contains(credentials.password))
    }

    @Test fun wrongPhoneCannotConsumePendingChallenge() {
        val request = WearSchoolGrantStore.createRequest(context, node, phone)
        val sealed = response(request)
        assertFalse(WearSchoolGrantStore.accept(context, "another-phone", sealed))
        assertTrue(WearSchoolGrantStore.accept(context, phone, sealed))
    }

    @Test fun revokedGrantRejectsLateResponseAndErasesStoredKeys() {
        val request = WearSchoolGrantStore.createRequest(context, node, phone)
        val sealed = response(request)
        publish(null)
        assertFalse(WearSchoolGrantStore.accept(context, phone, sealed))
        assertNull(WearSchoolGrantStore.load(context))
        assertTrue(context.getSharedPreferences("gradey-watch-school-private", 0).all.isEmpty())
    }

    @Test fun removedConnectionCannotBeResurrectedByInFlightProviderRefresh() {
        val request = WearSchoolGrantStore.createRequest(context, node, phone)
        assertTrue(WearSchoolGrantStore.accept(context, phone, response(request)))
        val stored = WearSchoolGrantStore.load(context)!!
        WearSchoolGrantStore.clear(context)
        val session = StoredSession("synthetic-watch-access", "synthetic-watch-refresh", "Bearer", System.currentTimeMillis() + 60_000, credentials.baseURL)
        assertTrue(runCatching { WearSchoolGrantStore.updateSession(context, stored, session) }.isFailure)
        assertNull(WearSchoolGrantStore.load(context))
    }

    @Test fun changedOwnerErasesCredentialAndSessionMaterial() {
        val request = WearSchoolGrantStore.createRequest(context, node, phone)
        assertTrue(WearSchoolGrantStore.accept(context, phone, response(request)))
        publish(access.copy(scope = scope.copy(ownerID = "different-owner")))
        assertNull(WearSchoolGrantStore.load(context))
        assertTrue(context.getSharedPreferences("gradey-watch-school-private", 0).all.isEmpty())
    }

    @Test fun reconnectUnderSamePhoneGrantRejectsOldProviderResult() {
        val first = WearSchoolGrantStore.createRequest(context, node, phone)
        assertTrue(WearSchoolGrantStore.accept(context, phone, response(first)))
        val previous = WearSchoolGrantStore.load(context)!!
        WearSchoolGrantStore.clear(context)
        val next = WearSchoolGrantStore.createRequest(context, node, phone)
        assertTrue(WearSchoolGrantStore.accept(context, phone, response(next)))
        val current = WearSchoolGrantStore.load(context)!!
        assertEquals(previous.grantID, current.grantID)
        assertNotEquals(previous.connectionID, current.connectionID)
        val late = StoredSession("late-access", "late-refresh", "Bearer", System.currentTimeMillis() + 60_000, credentials.baseURL)
        assertTrue(runCatching { WearSchoolGrantStore.updateSession(context, previous, late) }.isFailure)
        assertFalse(WearSchoolGrantStore.isCurrent(context, previous))
        assertNull(WearSchoolGrantStore.load(context)!!.session)
    }
}
