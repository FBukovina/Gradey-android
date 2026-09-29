package com.bukovinafilip.gradey

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.bukovinafilip.gradey.push.PushDeliveryHistory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PushDeliveryHistoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun clearHistory() = runBlocking { PushDeliveryHistory.clear(context); Unit }

    @Test fun concurrentRetriesDeliverOnlyOnceAndRemainScopedToTheOwner() = runBlocking {
        var calls = 0
        val results = coroutineScope {
            (1..8).map { async { PushDeliveryHistory.deliverOnce(context, "owner-a", "dispatch-1") { calls++; true } } }.awaitAll()
        }
        assertEquals(1, results.count { it })
        assertEquals(1, calls)
        assertFalse(PushDeliveryHistory.deliverOnce(context, "owner-a", "dispatch-1") { calls++; true })
        assertTrue(PushDeliveryHistory.deliverOnce(context, "owner-b", "dispatch-1") { calls++; true })
        assertEquals(2, calls)
        assertNotEquals(PushDeliveryHistory.notificationID("owner-a", "dispatch-1"), PushDeliveryHistory.notificationID("owner-b", "dispatch-1"))
    }

    @Test fun rejectedDeliveryDoesNotConsumeTheDispatchAndCleanupRemovesItsReceipt() = runBlocking {
        assertFalse(PushDeliveryHistory.deliverOnce(context, "owner", "dispatch") { false })
        assertTrue(PushDeliveryHistory.deliverOnce(context, "owner", "dispatch") { true })
        assertEquals(1, context.getSharedPreferences("gradey-push-receipts", Context.MODE_PRIVATE).all.size)
        PushDeliveryHistory.clear(context)
        assertTrue(context.getSharedPreferences("gradey-push-receipts", Context.MODE_PRIVATE).all.isEmpty())
    }
}
