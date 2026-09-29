package com.bukovinafilip.gradey

import androidx.test.platform.app.InstrumentationRegistry
import com.bukovinafilip.gradey.push.PushDeliveryHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NotificationPolicyRaceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun settingsChangeWhileReadingRejectsOldDeliveryWithoutConsumingItsReceipt() = runBlocking {
        val epoch = AccountNotifications.captureMarksPolicyEpoch()
        val reading = CompletableDeferred<Unit>()
        val finishReading = CompletableDeferred<Unit>()
        val dispatch = UUID.randomUUID().toString()
        var notifications = 0
        val pending = async(Dispatchers.Default) {
            reading.complete(Unit)
            finishReading.await()
            PushDeliveryHistory.deliverOnce(context, "race-test-owner", dispatch) {
                AccountNotifications.deliverMarksIfCurrent(epoch) { notifications++; true }
            }
        }
        reading.await()
        AccountNotifications.clearChannel(context, MARKS_CHANNEL)
        finishReading.complete(Unit)
        assertFalse(pending.await())
        assertEquals(0, notifications)
        val current = AccountNotifications.captureMarksPolicyEpoch()
        assertTrue(PushDeliveryHistory.deliverOnce(context, "race-test-owner", dispatch) {
            AccountNotifications.deliverMarksIfCurrent(current) { notifications++; true }
        })
        assertEquals(1, notifications)
    }

    @Test fun cleanupCannotFinishBetweenFinalEpochCheckAndDelivery() {
        val epoch = AccountNotifications.captureMarksPolicyEpoch()
        val delivering = CountDownLatch(1)
        val finishDelivery = CountDownLatch(1)
        val clearing = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        val delivered = AtomicBoolean(false)
        val worker = Thread {
            delivered.set(AccountNotifications.deliverMarksIfCurrent(epoch) {
                delivering.countDown()
                check(finishDelivery.await(5, TimeUnit.SECONDS))
                true
            })
        }
        val cleanup = Thread {
            clearing.countDown()
            AccountNotifications.clearChannel(context, MARKS_CHANNEL)
            cleared.countDown()
        }
        try {
            worker.start()
            assertTrue(delivering.await(5, TimeUnit.SECONDS))
            cleanup.start()
            assertTrue(clearing.await(5, TimeUnit.SECONDS))
            assertFalse(cleared.await(100, TimeUnit.MILLISECONDS))
        } finally {
            finishDelivery.countDown()
            worker.join(5_000)
            cleanup.join(5_000)
        }
        assertTrue(delivered.get())
        assertTrue(cleared.await(5, TimeUnit.SECONDS))
        assertFalse(AccountNotifications.deliverMarksIfCurrent(epoch) { error("Old delivery survived cleanup") })
    }

    @Test fun disabledReminderReconcileInvalidatesGenerationWithoutChangingMarksPolicy() {
        val preferences = context.getSharedPreferences(PlannerReminderScheduler.workName, 0)
        val previousGeneration = UUID.randomUUID().toString()
        preferences.edit().putString("generation", previousGeneration).commit()
        val marksEpoch = AccountNotifications.captureMarksPolicyEpoch()
        PlannerReminderScheduler.reconcile(context, GradeyState(), enabled = false)
        assertNotEquals(previousGeneration, preferences.getString("generation", null))
        assertTrue(AccountNotifications.deliverMarksIfCurrent(marksEpoch) { true })
    }
}
