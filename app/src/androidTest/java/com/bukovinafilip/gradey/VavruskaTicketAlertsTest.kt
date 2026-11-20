package com.bukovinafilip.gradey

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bukovinafilip.gradey.domain.VavruskaTicketSale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class VavruskaTicketAlertsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences(VavruskaTicketAlerts.preferences, 0)
    private val hour = 60L * 60 * 1000
    private val now = System.currentTimeMillis()

    private fun sale(id: String, opensIn: Long) = VavruskaTicketSale(id, "Prodloužená lekce", eventStartEpochMillis = now + opensIn + 14 * 24 * hour, saleStartEpochMillis = now + opensIn)
    private fun scheduled(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(VavruskaTicketAlerts.workName).get(10, TimeUnit.SECONDS)
        .filter { it.state == WorkInfo.State.ENQUEUED }

    @Before fun allowNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        VavruskaTicketAlerts.invalidate(context)
    }

    @After fun cleanUp() = VavruskaTicketAlerts.invalidate(context)

    @Test fun oneAlertIsScheduledForEachSaleThatOpensWithinTwoMonths() {
        VavruskaTicketAlerts.reconcile(
            context,
            listOf(sale("soon", 2 * hour), sale("later", 30 * 24 * hour), sale("open", -hour), sale("any-second", 30_000), sale("next-season", 90 * 24 * hour)),
            now,
        )
        // A sale already open, one opening this very minute and one a season away get none.
        assertEquals(2, scheduled().size)
        val generation = preferences.getString("generation", null)
        assertTrue(generation != null)

        // Sale times that moved replace what was scheduled instead of adding to it.
        VavruskaTicketAlerts.reconcile(context, listOf(sale("soon", 3 * hour)), now)
        assertEquals(1, scheduled().size)
        assertNotEquals(generation, preferences.getString("generation", null))
    }

    @Test fun turningAlertsOffOrLosingEverySaleCancelsWhatWasScheduled() {
        VavruskaTicketAlerts.reconcile(context, listOf(sale("soon", 2 * hour)), now)
        assertEquals(1, scheduled().size)
        val generation = preferences.getString("generation", null)
        // The sale opened or was taken off the site: nothing is left to wait for.
        VavruskaTicketAlerts.reconcile(context, emptyList(), now)
        assertEquals(0, scheduled().size)
        // A worker that was already running holds the old token and may not post.
        assertNotEquals(generation, preferences.getString("generation", null))

        VavruskaTicketAlerts.reconcile(context, listOf(sale("soon", 2 * hour)), now)
        assertEquals(1, scheduled().size)
        VavruskaTicketAlerts.invalidate(context)
        assertEquals(0, scheduled().size)
    }
}
