package com.bukovinafilip.gradey.data

import android.content.Context
import com.bukovinafilip.gradey.domain.PlannerCalendarStateStore
import com.bukovinafilip.gradey.model.PlannerCalendarState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AndroidPlannerCalendarStateStore(private val storage: PlannerStorage) : PlannerCalendarStateStore {
    constructor(context: Context) : this(EncryptedPlannerStorage(context.applicationContext, "planner-calendar.enc", "gradey-planner-calendar-v1"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override suspend fun read(): PlannerCalendarState = withContext(Dispatchers.IO) {
        val text = storage.read() ?: return@withContext PlannerCalendarState()
        json.decodeFromString<PlannerCalendarState>(text).also { state ->
            check(state.version == 1 && state.mappings.map { it.itemID }.distinct().size == state.mappings.size)
            check(state.mappings.all { it.ownershipToken.isNotBlank() && it.itemID.isNotBlank() && it.calendarID > 0 })
        }
    }
    override suspend fun write(state: PlannerCalendarState) = withContext(Dispatchers.IO) { storage.write(json.encodeToString(state)) }
}
