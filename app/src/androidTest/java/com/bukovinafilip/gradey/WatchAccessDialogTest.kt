package com.bukovinafilip.gradey

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.bukovinafilip.gradey.model.WearSchoolNodeGrant
import com.bukovinafilip.gradey.model.WearSchoolScope
import com.bukovinafilip.gradey.ui.GradeyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Presentation uses a fake metadata API only; no Wear service or school credentials are accessed. */
class WatchAccessDialogTest {
    @get:Rule val compose = createComposeRule()
    private val school = WearSchoolScope("private-owner", "private-school-scope")
    private val watch = WatchSchoolNode("private-node-id", "School Watch")

    private class FakeAccess(var value: WatchSchoolAccessState) : WatchSchoolAccessApi {
        var loads = 0
        var enables = mutableListOf<Pair<String, WearSchoolScope>>()
        var revokes = mutableListOf<Pair<String, WearSchoolScope>>()
        var revokedAll = 0
        var failEnable = false
        override suspend fun load(): WatchSchoolAccessState { loads++; return value }
        override suspend fun enable(nodeID: String, expectedScope: WearSchoolScope): WatchSchoolAccessState {
            enables += nodeID to expectedScope
            if (failEnable || value.scope != expectedScope) error("private-password private-owner private-school-scope")
            val grant = WearSchoolNodeGrant(nodeID, "private-grant-id", System.currentTimeMillis() + 7 * 86_400_000L)
            value = value.copy(grants = value.grants.filterNot { it.nodeID == nodeID } + grant)
            return value
        }
        override suspend fun revoke(nodeID: String, expectedScope: WearSchoolScope): WatchSchoolAccessState {
            revokes += nodeID to expectedScope
            value = value.copy(grants = value.grants.filterNot { it.nodeID == nodeID }, nodeNames = value.nodeNames - nodeID)
            return value
        }
        override fun revokeAll() { revokedAll++ }
    }

    @Test fun accessStaysOffUntilExplicitReviewConfirmationAtLargeFont() {
        val api = FakeAccess(WatchSchoolAccessState(school, listOf(watch), emptyList(), true))
        var changes = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                GradeyTheme { WatchAccessDialog(onDismiss = {}, isDemo = false, onAccessChanged = { changes++ }, manager = api) }
            }
        }
        compose.waitUntil(5_000) { api.loads == 1 }
        compose.onNodeWithText("Access is off").assertExists()
        compose.onNodeWithTag("watchAccessDevices").performScrollToNode(hasText("Review access"))
        compose.onNodeWithText("Review access").performClick()
        compose.onNodeWithText("An encrypted copy of your current school username and password", substring = true).assertExists()
        compose.onNodeWithText("Not now").performClick()
        compose.runOnIdle { assertTrue(api.enables.isEmpty()); assertEquals(0, changes) }
        compose.onNodeWithText("Review access").performClick()
        compose.onNodeWithText("Allow for 7 days").performClick()
        compose.waitUntil(5_000) { api.enables.size == 1 }
        compose.runOnIdle {
            assertEquals(watch.id to school, api.enables.single())
            assertEquals(1, changes)
            assertEquals(0, api.revokedAll)
        }
        assertNoInternalValues()
    }

    @Test fun disconnectedWatchCanBeRevokedWithClearDeferredRemovalNotice() {
        val grant = WearSchoolNodeGrant(watch.id, "private-grant-id", System.currentTimeMillis() + 86_400_000L)
        val api = FakeAccess(WatchSchoolAccessState(school, emptyList(), listOf(grant), true, mapOf(watch.id to watch.displayName)))
        var changes = 0
        compose.setContent { GradeyTheme { WatchAccessDialog(onDismiss = {}, isDemo = false, onAccessChanged = { changes++ }, manager = api) } }
        compose.onNodeWithText(watch.displayName).assertExists()
        compose.onNodeWithText("Not nearby").assertExists()
        compose.onNodeWithText("Review renewal").assertDoesNotExist()
        compose.onNodeWithTag("watchAccessDevices").performScrollToNode(hasText("Turn off access"))
        compose.onNodeWithText("Turn off access").performClick()
        compose.onNodeWithText("Access was turned off on this phone.", substring = true).assertExists()
        compose.onNodeWithText("Until then, it can work until the original expiry.", substring = true).assertExists()
        compose.runOnIdle {
            assertEquals(watch.id to school, api.revokes.single())
            assertTrue(api.value.grants.isEmpty())
            assertEquals(1, changes)
        }
        assertNoInternalValues()
    }

    @Test fun changedSchoolAfterReviewCannotSilentlyGrantTheNewSchoolOrExposeFailureDetails() {
        val api = FakeAccess(WatchSchoolAccessState(school, listOf(watch), emptyList(), true))
        var changes = 0
        compose.setContent { GradeyTheme { WatchAccessDialog(onDismiss = {}, isDemo = false, onAccessChanged = { changes++ }, manager = api) } }
        compose.onNodeWithTag("watchAccessDevices").performScrollToNode(hasText("Review access"))
        compose.onNodeWithText("Review access").performClick()
        compose.runOnIdle { api.value = api.value.copy(scope = WearSchoolScope("other-owner", "other-school")) }
        compose.onNodeWithText("Allow for 7 days").performClick()
        compose.onNodeWithText("Watch access could not be updated.", substring = true).assertExists()
        compose.runOnIdle {
            assertEquals(watch.id to school, api.enables.single())
            assertTrue(api.value.grants.isEmpty())
            // Refresh metadata even if a mutation succeeded but its following read failed.
            assertEquals(1, changes)
        }
        assertNoInternalValues()
    }

    @Test fun demoExplainsRestrictionWithoutDiscoveringOrEnablingAnyWatch() {
        val api = FakeAccess(WatchSchoolAccessState(school, listOf(watch), emptyList(), true))
        var closed = false
        compose.setContent { GradeyTheme { WatchAccessDialog(onDismiss = { closed = true }, isDemo = true, onAccessChanged = {}, manager = api) } }
        compose.onNodeWithText("Independent watch access is unavailable in demo mode.", substring = true).assertExists()
        compose.onNodeWithText("Review access").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertTrue(closed); assertEquals(0, api.loads); assertTrue(api.enables.isEmpty()) }
    }

    private fun assertNoInternalValues() {
        listOf("private-owner", "private-school-scope", "private-node-id", "private-grant-id", "private-password", "other-owner", "other-school")
            .forEach { compose.onAllNodesWithText(it, substring = true, useUnmergedTree = true).assertCountEquals(0) }
    }
}
