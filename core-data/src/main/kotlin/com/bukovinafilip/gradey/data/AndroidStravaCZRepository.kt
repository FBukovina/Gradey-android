package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.StravaCZRepository
import com.bukovinafilip.gradey.model.StravaCZMeal
import com.bukovinafilip.gradey.model.StravaCZMenu
import com.bukovinafilip.gradey.model.StravaCZStoredSession
import com.bukovinafilip.gradey.network.StravaCZApiException
import com.bukovinafilip.gradey.network.StravaCZClient
import com.bukovinafilip.gradey.network.StravaCZNetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

interface StravaCZLocalStore {
    fun loadSession(): StravaCZStoredSession?
    fun saveSession(session: StravaCZStoredSession?)
    fun loadMenu(): StravaCZMenu?
    fun saveMenu(menu: StravaCZMenu?)
}

class AndroidStravaCZRepository(
    private val store: StravaCZLocalStore,
    private val client: StravaCZClient = StravaCZNetworkClient(),
) : StravaCZRepository {
    constructor(secureStore: SecureJsonStore, client: StravaCZClient = StravaCZNetworkClient()) : this(
        object : StravaCZLocalStore {
            override fun loadSession() = secureStore.load("strava.session", StravaCZStoredSession.serializer())
            override fun saveSession(session: StravaCZStoredSession?) = secureStore.save("strava.session", session, StravaCZStoredSession.serializer())
            override fun loadMenu() = secureStore.load("strava.menu", StravaCZMenu.serializer())
            override fun saveMenu(menu: StravaCZMenu?) = secureStore.save("strava.menu", menu, StravaCZMenu.serializer())
        }, client,
    )

    private val mutex = Mutex()

    override suspend fun bootstrapSession(): StravaCZStoredSession? = mutex.withLock {
        store.loadSession()?.takeIf { it.serviceURL.isNotBlank() } ?: run {
            clearLocalData()
            null
        }
    }

    override suspend fun loadCachedMenu(): StravaCZMenu? = mutex.withLock {
        if (store.loadSession() != null) store.loadMenu() else null
    }

    override suspend fun login(canteenNumber: String, username: String, password: String): StravaCZStoredSession = mutex.withLock {
        require(canteenNumber.isNotBlank() && username.isNotBlank() && password.isNotEmpty()) {
            "Enter your canteen number, username, and password."
        }
        client.login(canteenNumber.trim(), username.trim(), password).also {
            store.saveMenu(null)
            store.saveSession(it)
        }
    }

    override suspend fun loadMenu(forceRefresh: Boolean): Pair<StravaCZStoredSession, StravaCZMenu> = mutex.withLock {
        // Always contact the provider. Cached data is exposed separately so the UI can label it honestly.
        fetchMenu()
    }

    override suspend fun setMeal(meal: StravaCZMeal, ordered: Boolean): Pair<StravaCZStoredSession, StravaCZMenu> = mutex.withLock {
        // Refresh restrictions before submitting a potentially stale cached meal.
        val (originalSession, freshMenu) = fetchMenu()
        val currentMeal = freshMenu.days.flatMap { it.meals }.firstOrNull { it.id == meal.id }
            ?: throw IllegalStateException("This meal is no longer on the menu. Refresh to choose another meal.")
        check(currentMeal.canModify) { "The canteen is no longer accepting changes for this meal." }
        if (currentMeal.ordered == ordered) return@withLock originalSession to freshMenu
        var session = originalSession
        var saveStarted = false
        var saved = false
        try {
            client.changeMeal(session, meal.id, ordered)?.let { session = updateBalance(session, it) }
            saveStarted = true
            client.saveOrders(session)?.let { session = updateBalance(session, it) }
            saved = true
            fetchMenu()
        } catch (error: Exception) {
            // A staged change must never leak into a later order. Do not undo a confirmed save.
            if (!saved) withContext(NonCancellable) {
                withTimeoutOrNull(15_000) {
                    runCatching { client.cancelChanges(session)?.let { updateBalance(session, it) } }
                }
            }
            clearIfExpired(error)
            if (error is CancellationException) throw error
            if (saved || (saveStarted && error !is StravaCZApiException)) {
                throw IOException("Your order may have been saved, but its latest status could not be confirmed. Refresh the menu before making another change.", error)
            }
            throw error
        }
    }

    override suspend fun logout() = mutex.withLock {
        val session = store.loadSession()
        clearLocalData()
        if (session != null) {
            try {
                client.logout(session)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Disconnect always removes local secrets, including when the provider is offline.
            }
        }
    }

    private suspend fun fetchMenu(): Pair<StravaCZStoredSession, StravaCZMenu> {
        val session = store.loadSession() ?: throw IllegalStateException("Connect your Strava.cz account to load meals.")
        return try {
            val menu = client.fetchMenu(session)
            store.saveMenu(menu)
            session to menu
        } catch (error: Exception) {
            clearIfExpired(error)
            throw error
        }
    }

    private fun updateBalance(session: StravaCZStoredSession, balance: String): StravaCZStoredSession =
        session.copy(balance = balance, savedAtEpochMillis = System.currentTimeMillis()).also(store::saveSession)

    private fun clearIfExpired(error: Exception) {
        if (error is StravaCZApiException && error.authenticationRequired) clearLocalData()
    }

    private fun clearLocalData() {
        store.saveSession(null)
        store.saveMenu(null)
    }
}
