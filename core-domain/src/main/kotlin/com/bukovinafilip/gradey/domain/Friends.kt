package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.BakalariCredentials
import com.bukovinafilip.gradey.model.Friend
import com.bukovinafilip.gradey.model.FriendTimetableSource
import com.bukovinafilip.gradey.model.LoginResponse
import com.bukovinafilip.gradey.model.PublicTimetableClass
import com.bukovinafilip.gradey.model.StoredSession
import com.bukovinafilip.gradey.model.TimetableKind
import com.bukovinafilip.gradey.model.TimetableResponse
import com.bukovinafilip.gradey.model.TimetableWeek
import kotlinx.coroutines.CancellationException
import java.text.Collator
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/** The public timetable only offers this week, next week and the permanent one. */
enum class PublicTimetableTerm(val path: String) { ACTUAL("Actual"), NEXT("Next"), PERMANENT("Permanent") }

/** Reads a school's public timetable pages, without signing in. Schools can turn the pages off. */
interface BakalariPublicTimetableClient {
    suspend fun fetchClasses(schoolURL: String): List<PublicTimetableClass>
    suspend fun fetchTimetable(schoolURL: String, classID: String, term: PublicTimetableTerm, weekStart: LocalDate): TimetableResponse
}

/** Why a friend's timetable could not be added or loaded; the screens put it into words. */
class FriendTimetableException(val reason: Reason, val status: Int? = null) : Exception(reason.name) {
    enum class Reason {
        /** Bakaláři no longer accepts the saved login. */
        SIGN_IN_AGAIN,
        /** The school does not publish its timetable. */
        NOT_PUBLISHED,
        /** The public page is not the one Gradey knows how to read. */
        UNEXPECTED_PAGE,
        /** The school's server answered with [status]. */
        HTTP_STATUS,
    }
}

/** The friend list and each friend's Bakaláři session (iOS `FriendStoring`). Sessions hold a password, so storage is encrypted. */
interface FriendStore {
    suspend fun loadFriends(): List<Friend>
    suspend fun saveFriends(friends: List<Friend>)
    suspend fun loadSession(friendID: String): StoredSession?
    suspend fun saveSession(session: StoredSession, friendID: String)
    suspend fun removeData(friendID: String)
}

/** The last copy of each week of a friend's timetable, for showing it at once and offline. */
interface FriendTimetableCache {
    suspend fun load(friendID: String, key: String): TimetableResponse?
    suspend fun save(friendID: String, key: String, response: TimetableResponse)
    suspend fun clear(friendID: String)
}

class InMemoryFriendStore(friends: List<Friend> = emptyList()) : FriendStore {
    private var friends = friends
    private val sessions = HashMap<String, StoredSession>()
    override suspend fun loadFriends(): List<Friend> = friends
    override suspend fun saveFriends(friends: List<Friend>) { this.friends = friends }
    override suspend fun loadSession(friendID: String): StoredSession? = sessions[friendID]
    override suspend fun saveSession(session: StoredSession, friendID: String) { sessions[friendID] = session }
    override suspend fun removeData(friendID: String) { sessions.remove(friendID) }
}

class InMemoryFriendTimetableCache : FriendTimetableCache {
    private val weeks = HashMap<String, TimetableResponse>()
    override suspend fun load(friendID: String, key: String): TimetableResponse? = weeks["$friendID:$key"]
    override suspend fun save(friendID: String, key: String, response: TimetableResponse) { weeks["$friendID:$key"] = response }
    override suspend fun clear(friendID: String) { weeks.keys.removeAll { it.startsWith("$friendID:") } }
}

/**
 * Adds friends and loads their timetables from whichever source they came from, as the same
 * [TimetableWeek] your own timetable uses (iOS `FriendRepository`). Only timetables are ever
 * requested with a friend's login: never marks or absence.
 */
class FriendRepository(
    private val store: FriendStore,
    private val client: BakalariClient,
    private val publicClient: BakalariPublicTimetableClient,
    private val cache: FriendTimetableCache = InMemoryFriendTimetableCache(),
    private val today: () -> LocalDate = { LocalDate.now(Homework.SchoolZone) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun loadFriends(): List<Friend> = store.loadFriends()

    suspend fun add(friend: Friend): List<Friend> = (store.loadFriends() + friend).also { store.saveFriends(it) }

    suspend fun rename(friendID: String, name: String): List<Friend> {
        val trimmed = name.trim()
        val friends = store.loadFriends()
        if (trimmed.isEmpty()) return friends
        return friends.map { if (it.id == friendID) it.copy(name = trimmed) else it }.also { store.saveFriends(it) }
    }

    /** Takes the friend off the list and deletes their login and saved timetable from the device. */
    suspend fun remove(friendID: String): List<Friend> {
        val friends = store.loadFriends().filter { it.id != friendID }
        store.saveFriends(friends)
        removeData(friendID)
        return friends
    }

    /** Signing out takes friends' logins off the device too. */
    suspend fun removeAll() {
        store.loadFriends().forEach { removeData(it.id) }
        store.saveFriends(emptyList())
    }

    private suspend fun removeData(friendID: String) {
        store.removeData(friendID)
        cache.clear(friendID)
    }

    // Adding

    /** Signs the friend in and loads this week once, so a wrong password or school fails here rather than on the timetable. */
    suspend fun connectBakalari(name: String, schoolURL: String, schoolName: String?, username: String, password: String): Friend {
        val user = username.trim()
        require(user.isNotEmpty() && password.isNotEmpty()) { "Username and password are required." }
        val baseURL = SchoolURLNormalizer.normalizedBaseURL(schoolURL)
        val response = client.login(baseURL, user, password)
        val friend = Friend(UUID.randomUUID().toString(), name.trim(), FriendTimetableSource.Bakalari(baseURL, schoolName, user), now())
        store.saveSession(session(response, baseURL, BakalariCredentials(user, password)), friend.id)
        try {
            loadWeek(friend, today(), TimetableKind.WEEKLY)
        } catch (error: Exception) {
            removeData(friend.id)
            throw error
        }
        return friend
    }

    /** The school's address as it was understood, and the classes it publishes. */
    suspend fun publicClasses(schoolURL: String): Pair<String, List<PublicTimetableClass>> {
        val baseURL = SchoolURLNormalizer.normalizedBaseURL(schoolURL)
        return baseURL to publicClient.fetchClasses(baseURL)
    }

    /** The class's groups (language and PE splits), read from its permanent timetable. */
    suspend fun publicGroups(schoolURL: String, classID: String): List<String> {
        val response = publicClient.fetchTimetable(schoolURL, classID, PublicTimetableTerm.PERMANENT, TimetableDates.monday(today()))
        val collator = Collator.getInstance(Locale.forLanguageTag("cs"))
        return response.groups.mapNotNull { it.abbrev?.trim()?.takeIf(String::isNotEmpty) }.distinct().sortedWith(collator)
    }

    fun makePublicFriend(name: String, schoolURL: String, schoolName: String?, schoolClass: PublicTimetableClass, groups: List<String>?): Friend =
        Friend(UUID.randomUUID().toString(), name.trim(), FriendTimetableSource.PublicClass(schoolURL, schoolName, schoolClass.id, schoolClass.name, groups), now())

    // Timetable

    suspend fun cachedWeek(friend: Friend, weekContaining: LocalDate, kind: TimetableKind): TimetableWeek? {
        val monday = TimetableDates.monday(weekContaining)
        return cache.load(friend.id, cacheKey(monday, kind))?.let { week(it, friend, monday, kind) }
    }

    suspend fun loadWeek(friend: Friend, weekContaining: LocalDate, kind: TimetableKind): TimetableWeek {
        val monday = TimetableDates.monday(weekContaining)
        val response = when (val source = friend.source) {
            is FriendTimetableSource.Bakalari -> withSession(friend) { session ->
                if (kind == TimetableKind.PERMANENT) client.fetchPermanentTimetable(session.baseURL, session.accessToken)
                else client.fetchTimetable(session.baseURL, session.accessToken, monday.toString())
            }
            is FriendTimetableSource.PublicClass -> {
                // The public page has this week, next week and the permanent timetable; any other week
                // shows the permanent one on its dates.
                val thisWeek = TimetableDates.monday(today())
                val term = when {
                    kind == TimetableKind.PERMANENT -> PublicTimetableTerm.PERMANENT
                    monday == thisWeek -> PublicTimetableTerm.ACTUAL
                    monday == thisWeek.plusWeeks(1) -> PublicTimetableTerm.NEXT
                    else -> PublicTimetableTerm.PERMANENT
                }
                val fetched = publicClient.fetchTimetable(source.schoolURL, source.classID, term, monday)
                if (kind == TimetableKind.WEEKLY && term == PublicTimetableTerm.PERMANENT) rebased(fetched, monday) else fetched
            }
        }
        cache.save(friend.id, cacheKey(monday, kind), response)
        return week(response, friend, monday, kind)
    }

    private fun cacheKey(monday: LocalDate, kind: TimetableKind) = if (kind == TimetableKind.PERMANENT) "permanent" else monday.toString()

    private fun week(response: TimetableResponse, friend: Friend, monday: LocalDate, kind: TimetableKind): TimetableWeek {
        val groups = (friend.source as? FriendTimetableSource.PublicClass)?.groups
        val shown = if (groups == null) response else filtered(response, groups.toSet())
        return TimetableMapper.makeWeek(shown, monday.toString(), today().toString(), kind)
    }

    /** Runs a request with the friend's token, refreshing it (or signing in again with the stored login) when Bakaláři rejects it. */
    private suspend fun <T> withSession(friend: Friend, operation: suspend (StoredSession) -> T): T {
        var session = store.loadSession(friend.id) ?: throw FriendTimetableException(FriendTimetableException.Reason.SIGN_IN_AGAIN)
        if (session.isExpired(now())) session = refreshed(session, friend.id)
        return try {
            operation(session)
        } catch (error: Exception) {
            if (error is CancellationException || !error.isRejected(AccountFailureReason.SCHOOL_SESSION_EXPIRED)) throw error
            operation(refreshed(session, friend.id))
        }
    }

    private suspend fun refreshed(session: StoredSession, friendID: String): StoredSession {
        val response = try {
            client.refreshToken(session.baseURL, session.refreshToken)
        } catch (error: Exception) {
            if (error is CancellationException || !error.isRejectedLogin()) throw error
            val credentials = session.bakalari ?: throw FriendTimetableException(FriendTimetableException.Reason.SIGN_IN_AGAIN)
            try {
                client.login(session.baseURL, credentials.username, credentials.password)
            } catch (again: Exception) {
                if (again is CancellationException || !again.isRejectedLogin()) throw again
                throw FriendTimetableException(FriendTimetableException.Reason.SIGN_IN_AGAIN)
            }
        }
        return session(response, session.baseURL, session.bakalari).also { store.saveSession(it, friendID) }
    }

    private fun Exception.isRejected(reason: AccountFailureReason) = (this as? UserFacingFailure)?.failureReason == reason
    private fun Exception.isRejectedLogin() = isRejected(AccountFailureReason.SCHOOL_BAD_CREDENTIALS) || isRejected(AccountFailureReason.SCHOOL_SESSION_EXPIRED)

    private fun session(response: LoginResponse, baseURL: String, credentials: BakalariCredentials?) = StoredSession(
        accessToken = response.accessToken, refreshToken = response.refreshToken, tokenType = response.tokenType,
        expiresAtEpochMillis = now() + response.expiresIn * 1000, baseURL = baseURL, bakalari = credentials,
    )

    companion object {
        /** A class timetable shows every group; keep whole-class lessons and the friend's own groups. */
        fun filtered(response: TimetableResponse, keepingGroups: Set<String>): TimetableResponse {
            val names = response.groups.associate { it.id to it.abbrev }
            return response.copy(days = response.days.map { day ->
                day.copy(atoms = day.atoms.filter { atom ->
                    // A group the page does not name is kept: better one lesson too many than a hole in the day.
                    atom.groupIDs.isEmpty() || atom.groupIDs.any { id -> names[id]?.let(keepingGroups::contains) ?: true }
                })
            })
        }

        /** The same lessons on the dates of the week that starts on [monday], for a timetable without dates. */
        fun rebased(response: TimetableResponse, monday: LocalDate): TimetableResponse =
            response.copy(days = response.days.map { it.copy(date = monday.plusDays((it.dayOfWeek - 1).coerceAtLeast(0).toLong()).toString()) })
    }
}
