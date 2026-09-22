package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.model.StravaCZMeal
import com.bukovinafilip.gradey.model.StravaCZMenu
import com.bukovinafilip.gradey.model.StravaCZMenuDay
import com.bukovinafilip.gradey.model.StravaCZStoredSession
import com.bukovinafilip.gradey.network.StravaCZApiException
import com.bukovinafilip.gradey.network.StravaCZClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

class AndroidStravaCZRepositoryTest {
    private val meal = StravaCZMeal(7, "Lunch", "Pasta")
    private val session = StravaCZStoredSession("secret", "1234", "student", serviceURL = "https://canteen.example")
    private val initialMenu = StravaCZMenu(listOf(StravaCZMenuDay("day", "Tuesday", "2026-09-22", listOf(meal))))

    @Test fun saveUpdatesBalanceThenRefetchesAuthoritativeMenu() = runBlocking<Unit> {
        val store = MemoryStore(session, initialMenu)
        val client = FakeClient(initialMenu)
        val repository = AndroidStravaCZRepository(store, client)
        val result = repository.setMeal(meal, true)
        assertThat(client.events).containsExactly("fetch", "change:true", "save", "fetch").inOrder()
        assertThat(result.second.days.single().meals.single().ordered).isTrue()
        assertThat(store.session?.balance).isEqualTo("60.0")
        assertThat(store.menu).isEqualTo(result.second)
    }

    @Test fun failedSaveRollsBackPendingChangesAndDoesNotPretendSuccess() = runBlocking<Unit> {
        val store = MemoryStore(session, initialMenu)
        val client = FakeClient(initialMenu).apply { saveFailure = StravaCZApiException("Order rejected") }
        val repository = AndroidStravaCZRepository(store, client)
        val failure = runCatching { repository.setMeal(meal, true) }.exceptionOrNull()
        assertThat(failure?.message).isEqualTo("Order rejected")
        assertThat(client.events).containsExactly("fetch", "change:true", "save", "rollback").inOrder()
        assertThat(store.menu?.days?.single()?.meals?.single()?.ordered).isFalse()
    }

    @Test fun doesNotRollbackCommittedOrderWhenRefreshFails() = runBlocking<Unit> {
        val store = MemoryStore(session, initialMenu)
        val client = FakeClient(initialMenu).apply { failRefreshAfterSave = true }
        val failure = runCatching { AndroidStravaCZRepository(store, client).setMeal(meal, true) }.exceptionOrNull()
        assertThat(failure?.message).contains("order may have been saved")
        assertThat(client.events).doesNotContain("rollback")
    }

    @Test fun expiredSessionClearsPrivateData() = runBlocking<Unit> {
        val store = MemoryStore(session, initialMenu)
        val client = FakeClient(initialMenu).apply { fetchFailure = StravaCZApiException("Expired", authenticationRequired = true) }
        runCatching { AndroidStravaCZRepository(store, client).loadMenu() }
        assertThat(store.session).isNull()
        assertThat(store.menu).isNull()
    }

    @Test fun rechecksOrderRestrictionsBeforeMakingChanges() = runBlocking<Unit> {
        val restrictedMenu = initialMenu.copy(days = initialMenu.days.map { it.copy(meals = listOf(meal.copy(canModify = false))) })
        val store = MemoryStore(session, initialMenu)
        val client = FakeClient(restrictedMenu)
        val failure = runCatching { AndroidStravaCZRepository(store, client).setMeal(meal, true) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(client.events).containsExactly("fetch")
    }

    @Test fun disconnectClearsDataWhenRemoteLogoutFails() = runBlocking<Unit> {
        val store = MemoryStore(session, initialMenu)
        val client = FakeClient(initialMenu).apply { logoutFailure = IOException("Offline") }
        AndroidStravaCZRepository(store, client).logout()
        assertThat(store.session).isNull()
        assertThat(store.menu).isNull()
    }

    private class MemoryStore(var session: StravaCZStoredSession?, var menu: StravaCZMenu?) : StravaCZLocalStore {
        override fun loadSession() = session
        override fun saveSession(session: StravaCZStoredSession?) { this.session = session }
        override fun loadMenu() = menu
        override fun saveMenu(menu: StravaCZMenu?) { this.menu = menu }
    }

    private class FakeClient(var menu: StravaCZMenu) : StravaCZClient {
        val events = mutableListOf<String>()
        var saveFailure: Exception? = null
        var fetchFailure: Exception? = null
        var logoutFailure: Exception? = null
        var failRefreshAfterSave = false
        private var stagedOrder = false
        private var saved = false
        override suspend fun login(canteenNumber: String, username: String, password: String) = error("Not used")
        override suspend fun fetchMenu(session: StravaCZStoredSession): StravaCZMenu {
            events.add("fetch")
            fetchFailure?.let { throw it }
            if (saved && failRefreshAfterSave) throw IOException("Offline")
            return menu
        }
        override suspend fun changeMeal(session: StravaCZStoredSession, mealID: Int, ordered: Boolean): String {
            events.add("change:$ordered")
            stagedOrder = ordered
            return "60.0"
        }
        override suspend fun saveOrders(session: StravaCZStoredSession): String {
            events.add("save")
            saveFailure?.let { throw it }
            saved = true
            menu = menu.copy(days = menu.days.map { it.copy(meals = it.meals.map { meal -> meal.copy(ordered = stagedOrder) }) })
            return "60.0"
        }
        override suspend fun cancelChanges(session: StravaCZStoredSession): String {
            events.add("rollback")
            stagedOrder = false
            return "100.0"
        }
        override suspend fun logout(session: StravaCZStoredSession) { logoutFailure?.let { throw it } }
    }
}
