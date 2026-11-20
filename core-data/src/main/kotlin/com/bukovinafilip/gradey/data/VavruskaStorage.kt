package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.VavruskaCache
import com.bukovinafilip.gradey.domain.VavruskaCourseStore
import com.bukovinafilip.gradey.domain.VavruskaSnapshot
import com.bukovinafilip.gradey.domain.VavruskaStoredCourse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The connected dance course in encrypted storage (iOS `VavruskaCourseStore`, which uses the
 * Keychain): its lesson plan link works without a login, so it is a secret.
 */
class AndroidVavruskaCourseStore(private val secureStore: JsonStore) : VavruskaCourseStore {
    override suspend fun load(): VavruskaStoredCourse? = withContext(Dispatchers.IO) { secureStore.load(Key, VavruskaStoredCourse.serializer()) }
    override suspend fun save(course: VavruskaStoredCourse) = withContext(Dispatchers.IO) { secureStore.save(Key, course, VavruskaStoredCourse.serializer()) }
    override suspend fun clear() = withContext(Dispatchers.IO) { secureStore.clear(Key) }

    private companion object {
        const val Key = "vavruska.course"
    }
}

/** The last lesson plan, notice board and ticket sales, for showing them at once and offline. */
class JsonVavruskaCache(private val store: JsonStore) : VavruskaCache {
    override suspend fun load(): VavruskaSnapshot? = withContext(Dispatchers.IO) { store.load(Key, VavruskaSnapshot.serializer()) }
    override suspend fun save(snapshot: VavruskaSnapshot) = withContext(Dispatchers.IO) { store.save(Key, snapshot, VavruskaSnapshot.serializer()) }
    override suspend fun clear() = withContext(Dispatchers.IO) { store.clear(Key) }

    private companion object {
        const val Key = "vavruska.cache"
    }
}
