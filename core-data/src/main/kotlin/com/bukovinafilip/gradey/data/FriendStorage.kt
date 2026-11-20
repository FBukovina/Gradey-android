package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.FriendStore
import com.bukovinafilip.gradey.domain.FriendTimetableCache
import com.bukovinafilip.gradey.model.Friend
import com.bukovinafilip.gradey.model.StoredSession
import com.bukovinafilip.gradey.model.TimetableResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/**
 * The friend list and each friend's Bakaláři session in encrypted storage (iOS `FriendStore`, which
 * uses the Keychain). The app is excluded from backups and device transfers, so a friend's login
 * never leaves this device.
 */
class AndroidFriendStore(private val secureStore: JsonStore) : FriendStore {
    override suspend fun loadFriends(): List<Friend> = io { runCatching { secureStore.load(ListKey, ListSerializer(Friend.serializer())) }.getOrNull().orEmpty() }
    override suspend fun saveFriends(friends: List<Friend>) = io { secureStore.save(ListKey, friends, ListSerializer(Friend.serializer())) }
    override suspend fun loadSession(friendID: String): StoredSession? = io { runCatching { secureStore.load(sessionKey(friendID), StoredSession.serializer()) }.getOrNull() }
    override suspend fun saveSession(session: StoredSession, friendID: String) = io { secureStore.save(sessionKey(friendID), session, StoredSession.serializer()) }
    override suspend fun removeData(friendID: String) = io { secureStore.clear(sessionKey(friendID)) }

    private fun sessionKey(friendID: String) = "gradey.friend.$friendID.session"

    private companion object {
        /** The key iOS uses for the same list. */
        const val ListKey = "gradey.friends.v1"
    }
}

/** The last copy of each week of a friend's timetable, kept apart from the user's own school cache. */
class JsonFriendTimetableCache(private val store: JsonStore) : FriendTimetableCache {
    override suspend fun load(friendID: String, key: String): TimetableResponse? = io { runCatching { store.load(key(friendID, key), TimetableResponse.serializer()) }.getOrNull() }
    override suspend fun save(friendID: String, key: String, response: TimetableResponse) = io { store.save(key(friendID, key), response, TimetableResponse.serializer()) }
    override suspend fun clear(friendID: String) = io { store.clearPrefix("friend.$friendID.") }

    private fun key(friendID: String, key: String) = "friend.$friendID.$key"
}

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
