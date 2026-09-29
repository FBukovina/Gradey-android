package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID

class WearSchoolProtocolTest {
    private val now = 1_800_000_000_000L
    private val scope = WearSchoolScope("owner", "school-scope")
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val request = WearSchoolProvisionRequest(requestID = UUID.randomUUID().toString(), scope = scope, grantID = UUID.randomUUID().toString(),
        createdAtEpochMillis = now, publicKey = Base64.getEncoder().encodeToString(key.public.encoded))
    private val credentials = WearSchoolCredentials(SchoolProvider.BAKALARI, "https://school.example", "fixture-student", "fixture-password", "student-1")
    private val grant = WearSchoolNodeGrant("watch", request.grantID, now + WearSchoolProtocol.LEASE_MILLIS)

    @Test fun envelopeAuthenticatesScopeAndKeepsCredentialsOutOfTransportMetadata() {
        val response = WearSchoolProtocol.seal(request, credentials, grant.expiresAtEpochMillis, now)
        assertThat(response.toString()).doesNotContain("fixture-password")
        assertThat(response.toString()).doesNotContain("fixture-student")
        val decoded = WearSchoolProtocol.open(response, request, key.private, grant, now + 1000)
        assertThat(decoded.password).isEqualTo("fixture-password")
        assertThat(decoded.expectedUserID).isEqualTo("student-1")
        assertThat(decoded.toString()).doesNotContain("fixture-password")
    }
    @Test fun wrongOwnerWrongGrantAndTamperedCiphertextAreRejected() {
        val response = WearSchoolProtocol.seal(request, credentials, grant.expiresAtEpochMillis, now)
        assertThrows(Exception::class.java) { WearSchoolProtocol.open(response.copy(scope = scope.copy(ownerID = "other")), request, key.private, grant, now) }
        assertThrows(Exception::class.java) { WearSchoolProtocol.open(response, request, key.private, grant.copy(grantID = UUID.randomUUID().toString()), now) }
        val bytes = Base64.getDecoder().decode(response.ciphertext).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { WearSchoolProtocol.open(response.copy(ciphertext = Base64.getEncoder().encodeToString(bytes)), request, key.private, grant, now) }
        assertThrows(Exception::class.java) { WearSchoolProtocol.open(response.copy(expiresAtEpochMillis = response.expiresAtEpochMillis - 1), request, key.private, grant, now) }
    }
    @Test fun expiredRequestsAndLeasesCannotRenewThemselves() {
        assertThat(WearSchoolProtocol.valid(request, now + 120_001)).isFalse()
        assertThrows(Exception::class.java) { WearSchoolProtocol.seal(request, credentials, now + WearSchoolProtocol.LEASE_MILLIS + 1, now) }
        assertThat(WearSchoolProtocol.grant(WearSchoolAccess(scope, listOf(grant)), "watch", grant.expiresAtEpochMillis)).isNull()
        assertThat(WearSchoolProtocol.grant(WearSchoolAccess(scope, emptyList()), "watch", now)).isNull()
        assertThat(WearSchoolProtocol.grant(WearSchoolAccess(scope, listOf(grant, grant)), "watch", now)).isNull()
    }
    @Test fun insecureHostsAndMissingCanonicalIdentityAreNotProvisionable() {
        assertThat(WearSchoolProtocol.valid(WearSchoolCredentials(SchoolProvider.BAKALARI, "http://school.example", "name", "password", "id"))).isFalse()
        assertThat(WearSchoolProtocol.valid(WearSchoolCredentials(SchoolProvider.EDU_PAGE, "https://not-edupage.example", "name", "password", "id"))).isFalse()
        assertThat(WearSchoolProtocol.valid(WearSchoolCredentials(SchoolProvider.BAKALARI, "https://school.example", "name", "password", ""))).isFalse()
        assertThat(WearSchoolProtocol.valid(request.copy(scope = scope.copy(ownerID = "owner\u0000other")), now)).isFalse()
    }
}
