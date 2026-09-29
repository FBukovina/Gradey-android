package com.bukovinafilip.gradey

import com.bukovinafilip.gradey.domain.WearSchoolProtocol
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.network.GradeyJson
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WearSchoolAccessManagerTest {
    private class Storage : WearSchoolAccessStorage {
        var value: WearSchoolSavedAccess? = null
        var failSave = false
        var failRevoke = false
        override fun load() = value
        override fun save(value: WearSchoolSavedAccess) { check(!failSave); this.value = value }
        override fun invalidate() { check(!failRevoke); value = null }
    }
    private class Environment : WearSchoolAccessEnvironment {
        var current: WearSchoolPhoneIdentity? = WearSchoolPhoneIdentity(WearSchoolScope("owner", "school"), SchoolProvider.BAKALARI,
            "https://school.example", "canonical-user", source = Any())
        var allowed = true
        var nodes = listOf(WatchSchoolNode("watch-a", "My Watch"))
        var beforeCredentials: (suspend () -> Unit)? = null
        var beforeNodes: (suspend () -> Unit)? = null
        var credentialReads = 0
        val sent = mutableListOf<Pair<String, ByteArray>>()
        val paths = mutableListOf<String>()
        override suspend fun identity() = current
        override fun stillAllowed(identity: WearSchoolPhoneIdentity) = allowed && current == identity
        override suspend fun credentials(identity: WearSchoolPhoneIdentity): WearSchoolCredentials {
            credentialReads++
            beforeCredentials?.invoke()
            return WearSchoolCredentials(identity.provider, identity.baseURL, "fixture-login", "fixture-password", identity.expectedUserID, identity.selectedStudentID)
        }
        override suspend fun nearbyNodes(): List<WatchSchoolNode> { beforeNodes?.invoke(); return nodes }
        override fun send(nodeID: String, bytes: ByteArray, path: String): suspend () -> Unit {
            sent += nodeID to bytes.copyOf()
            paths += path
            return {}
        }
    }
    private class Fixture {
        val storage = Storage()
        val environment = Environment()
        var time = 1_000_000L
        val manager = WearSchoolAccessManager(storage, environment) { time }
        val scope get() = requireNotNull(environment.current).scope
        suspend fun enable(node: String = "watch-a") = manager.enable(node, scope)
        suspend fun request() = WearSchoolProvisionRequest(requestID = UUID.randomUUID().toString(), scope = scope,
            grantID = requireNotNull(manager.snapshot()).grants.single().grantID, createdAtEpochMillis = time,
            publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded))
        suspend fun send(request: WearSchoolProvisionRequest, node: String = "watch-a") = manager.handleProvision(node, GradeyJson.encodeToString(WearSchoolProvisionRequest.serializer(), request).toByteArray())
    }

    @Test fun defaultOffAndOnlyAnExplicitNearbyEnableCreatesSevenDayGrant() = runBlocking {
        val f = Fixture()
        assertTrue(f.manager.load().available)
        assertTrue(requireNotNull(f.manager.snapshot()).grants.isEmpty())
        expectFailure { f.enable("remote-watch") }
        assertNull(f.storage.value)
        val first = f.enable().grants.single()
        assertEquals(f.time + WearSchoolProtocol.LEASE_MILLIS, first.expiresAtEpochMillis)
        assertEquals("My Watch", f.manager.load().nodeNames["watch-a"])
        val second = f.enable().grants.single()
        assertNotEquals(first.grantID, second.grantID)
        assertEquals(1, f.storage.value?.access?.grants?.size)
    }

    @Test fun grantsAreAccountScopedExpireAndCannotBeRestoredByPublicSnapshotReads() = runBlocking {
        val f = Fixture(); f.enable()
        val original = requireNotNull(f.environment.current)
        f.environment.current = original.copy(scope = original.scope.copy(ownerID = "other-owner"))
        assertTrue(requireNotNull(f.manager.snapshot()).grants.isEmpty())
        f.environment.current = original.copy(scope = original.scope.copy(schoolScope = "other-student"))
        assertTrue(f.manager.load().grants.isEmpty())
        f.environment.current = original
        f.time += WearSchoolProtocol.LEASE_MILLIS
        assertTrue(requireNotNull(f.manager.snapshot()).grants.isEmpty())
        f.environment.allowed = false
        assertNull(f.manager.snapshot())
    }

    @Test fun validRequestIsTargetedSealedAndBoundedByTheApprovedExpiry() = runBlocking {
        val f = Fixture(); f.enable()
        val request = f.request()
        assertTrue(f.send(request))
        assertEquals("watch-a", f.environment.sent.single().first)
        val bytes = f.environment.sent.single().second
        assertFalse(bytes.toString(Charsets.UTF_8).contains("fixture-password"))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("fixture-login"))
        val response = GradeyJson.decodeFromString(WearSchoolProvisionResponse.serializer(), bytes.toString(Charsets.UTF_8))
        val grant = requireNotNull(f.manager.snapshot()).grants.single()
        val opened = WearSchoolProtocol.open(response, request, keyPair.private, grant, f.time)
        assertEquals("fixture-password", opened.password)
        assertEquals("canonical-user", opened.expectedUserID)
        assertEquals(grant.expiresAtEpochMillis, response.expiresAtEpochMillis)
        assertFalse(f.send(request)) // Duplicate challenge must not provision a second time.
        assertEquals(1, f.environment.sent.size)
    }

    @Test fun malformedOversizedWrongNodeScopeGrantAndStaleRequestsNeverReadCredentials() = runBlocking {
        val f = Fixture(); f.enable()
        val request = f.request()
        assertFalse(f.manager.handleProvision("watch-a", ByteArray(WearSchoolProtocol.MAX_MESSAGE_BYTES + 1)))
        assertFalse(f.manager.handleProvision("watch-a", "{}".toByteArray()))
        assertFalse(f.send(request, "watch-b"))
        assertFalse(f.send(request.copy(scope = request.scope.copy(ownerID = "other"))))
        assertFalse(f.send(request.copy(grantID = UUID.randomUUID().toString())))
        assertFalse(f.send(request.copy(createdAtEpochMillis = f.time - WearSchoolProtocol.REQUEST_LIFETIME_MILLIS - 1)))
        assertFalse(f.send(request.copy(createdAtEpochMillis = f.time + 30_001)))
        assertFalse(f.send(request.copy(schemaVersion = 2)))
        assertEquals(0, f.environment.credentialReads)
        assertTrue(f.environment.sent.isEmpty())
    }

    @Test fun revokeDuringSuspendedCredentialReadPreventsSendEvenWhenSameAccountRemains() = runBlocking {
        val f = Fixture(); f.enable()
        val started = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        f.environment.beforeCredentials = { started.complete(Unit); proceed.await() }
        val request = f.request()
        val response = async { f.send(request) }
        withTimeout(5_000) { started.await() }
        f.manager.revokeAll()
        proceed.complete(Unit)
        assertFalse(withTimeout(5_000) { response.await() })
        assertTrue(f.environment.sent.isEmpty())
        assertTrue(requireNotNull(f.manager.snapshot()).grants.isEmpty())
    }

    @Test fun accountChangeOrLossOfNearbyStatusAfterCredentialReadPreventsSend() = runBlocking {
        listOf<(Environment) -> Unit>(
            { it.current = it.current!!.copy(scope = it.current!!.scope.copy(ownerID = "new-owner")) },
            { it.nodes = emptyList() },
            { it.allowed = false },
        ).forEach { mutation ->
            val f = Fixture(); f.enable()
            f.environment.beforeCredentials = { mutation(f.environment) }
            assertFalse(f.send(f.request()))
            assertTrue(f.environment.sent.isEmpty())
        }
    }

    @Test fun revokeBeforeSuspendedEnableCompletesPreventsGrantResurrection() = runBlocking {
        val f = Fixture()
        val started = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        f.environment.beforeNodes = { started.complete(Unit); proceed.await() }
        val result = async { runCatching { f.enable() }.isFailure }
        withTimeout(5_000) { started.await() }
        f.manager.revokeAll()
        proceed.complete(Unit)
        assertTrue(withTimeout(5_000) { result.await() })
        assertNull(f.storage.value)
    }

    @Test fun revokeWhileEnableWaitsForItsFirstDispatchCannotCreateANewGrant() = runBlocking {
        val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val paused = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val f = Fixture()
        val manager = WearSchoolAccessManager(f.storage, f.environment, ioDispatcher = paused) { f.time }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { manager.enable("watch-a", f.scope) }.isFailure
        }
        assertFalse(queue.isEmpty())
        manager.revokeAll()
        requireNotNull(queue.poll()).run()
        assertTrue(withTimeout(5_000) { result.await() })
        assertNull(f.storage.value)
    }

    @Test fun individualRevocationPreservesOtherWatchesAndFailedReplacementLeavesAllRevoked() = runBlocking {
        val f = Fixture()
        f.environment.nodes += WatchSchoolNode("watch-b", "Second Watch")
        f.enable(); f.enable("watch-b")
        val onlySecond = f.manager.revoke("watch-a", f.scope)
        assertEquals(listOf("watch-b"), onlySecond.grants.map { it.nodeID })
        assertEquals(mapOf("watch-b" to "Second Watch"), onlySecond.nodeNames)
        f.enable()
        f.storage.failSave = true
        expectFailure { f.manager.revoke("watch-a", f.scope) }
        assertNull(f.storage.value)
        assertTrue(requireNotNull(f.manager.snapshot()).grants.isEmpty())
        // A new process also reads no grant after the invalidation succeeded but replacement failed.
        val restarted = WearSchoolAccessManager(f.storage, f.environment) { f.time }
        assertTrue(requireNotNull(restarted.snapshot()).grants.isEmpty())
    }

    @Test fun storageFailureCannotPublishAnUnpersistedOptInOrResumePendingRequests() = runBlocking {
        val f = Fixture(); f.storage.failSave = true
        expectFailure { f.enable() }
        assertTrue(requireNotNull(f.manager.snapshot()).grants.isEmpty())
        f.storage.failSave = false; f.enable()
        val request = f.request()
        f.storage.failRevoke = true
        expectFailure { f.manager.revokeAll() }
        assertFalse(f.send(request))
    }

    @Test fun missingIdentityOnlyRepliesToAnExistingCorrelatedGrantWithAGenericRejection() = runBlocking {
        val f = Fixture(); f.enable()
        val request = f.request()
        f.environment.current = null
        assertFalse(f.send(request))
        assertEquals(listOf(WearSchoolProtocol.REJECTION_PATH), f.environment.paths)
        val response = GradeyJson.decodeFromString(WearSchoolProvisionRejection.serializer(), f.environment.sent.single().second.toString(Charsets.UTF_8))
        assertEquals(WearSchoolProvisionRejectionReason.SCHOOL_IDENTITY_UNAVAILABLE, response.reason)
        assertEquals(request.requestID, response.requestID)
        assertEquals(request.scope, response.scope)
        assertEquals(request.grantID, response.grantID)
        assertEquals(0, f.environment.credentialReads)
        assertFalse(f.send(request.copy(grantID = UUID.randomUUID().toString())))
        assertEquals(1, f.environment.sent.size)
    }

    @Test fun phoneAccessRefreshAndPhpSessionTokensAreNeverCopiedIntoProvisionedCredentials() {
        val session = StoredSession("PHONE-ACCESS", "PHONE-REFRESH", "Bearer", 99, "https://school.example",
            bakalari = BakalariCredentials("fixture-login", "fixture-password"))
        val identity = WearSchoolPhoneIdentity(WearSchoolScope("owner", session.cacheScope), SchoolProvider.BAKALARI,
            "https://school.example", "canonical-user", source = Any())
        val credentials = requireNotNull(wearSchoolCredentials(session, identity))
        val encoded = GradeyJson.encodeToString(WearSchoolCredentials.serializer(), credentials)
        assertTrue(encoded.contains("fixture-password"))
        assertFalse(encoded.contains("PHONE-ACCESS"))
        assertFalse(encoded.contains("PHONE-REFRESH"))
        val parent = EduPageSessionData("PHONE-PHPSESSION", "parent-login", "fixture-parent-password", "PHONE-GSEC", "parent-id",
            activeStudent = SchoolStudentProfile("student-id", "Fixture"))
        val eduSession = session.copy(provider = SchoolProvider.EDU_PAGE, baseURL = "https://school.edupage.org", eduPage = parent, bakalari = null)
        val eduIdentity = identity.copy(scope = identity.scope.copy(schoolScope = eduSession.cacheScope), provider = SchoolProvider.EDU_PAGE,
            baseURL = eduSession.baseURL, expectedUserID = "student-id", selectedStudentID = "student-id")
        val edu = requireNotNull(wearSchoolCredentials(eduSession, eduIdentity))
        val eduEncoded = GradeyJson.encodeToString(WearSchoolCredentials.serializer(), edu)
        assertEquals("student-id", edu.selectedStudentID)
        assertFalse(eduEncoded.contains("PHONE-PHPSESSION"))
        assertFalse(eduEncoded.contains("PHONE-GSEC"))
        assertNull(wearSchoolCredentials(session.copy(bakalari = null), identity))
        assertNull(wearSchoolCredentials(session, identity.copy(scope = identity.scope.copy(schoolScope = "other"))))
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalStateException) { failed = true }
        assertTrue("Expected the unsafe action to be rejected", failed)
    }
    companion object {
        private val keyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    }
}
