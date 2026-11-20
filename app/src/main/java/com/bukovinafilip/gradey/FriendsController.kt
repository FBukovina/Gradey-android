package com.bukovinafilip.gradey

import android.content.Context
import com.bukovinafilip.gradey.domain.FriendRepository
import com.bukovinafilip.gradey.domain.FriendTimetableException
import com.bukovinafilip.gradey.domain.TimetableDates
import com.bukovinafilip.gradey.model.Friend
import com.bukovinafilip.gradey.model.TimetableKind
import com.bukovinafilip.gradey.model.TimetableWeek
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * @param selectedID the friend whose timetable is on screen; null for the user's own.
 * @param week the selected friend's week for [viewedWeek] and [kind], once it is saved or loaded.
 */
internal data class FriendsState(
    val friends: List<Friend> = emptyList(),
    val selectedID: String? = null,
    val week: TimetableWeek? = null,
    val viewedWeek: String = TimetableDates.monday().toString(),
    val kind: TimetableKind = TimetableKind.WEEKLY,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val selected: Friend? get() = friends.firstOrNull { it.id == selectedID }
}

/**
 * One app-wide list of friends and the timetable of the one being looked at (iOS `FriendsViewModel`
 * plus the friend's `TimetableViewModel`). Lives in the view model, so it survives rotation.
 */
internal class FriendsController(private val context: Context, private val scope: CoroutineScope, private val repository: () -> FriendRepository) {
    private val mutableState = MutableStateFlow(FriendsState())
    val state: StateFlow<FriendsState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    /** Reads the saved list; called at launch and whenever the account, and with it the repository, changes. */
    fun reload() {
        loadJob?.cancel()
        mutableState.value = FriendsState()
        scope.launch {
            val friends = withContext(Dispatchers.IO) { runCatching { repository().loadFriends() }.getOrDefault(emptyList()) }
            mutableState.update { it.copy(friends = friends) }
        }
    }

    /** Shows [friendID]'s timetable for this week, or the user's own for null. */
    fun select(friendID: String?) {
        val friend = state.value.friends.firstOrNull { it.id == friendID }
        mutableState.update { FriendsState(friends = it.friends, selectedID = friend?.id) }
        if (friend != null) load() else loadJob?.cancel()
    }

    fun changeWeek(date: String) {
        mutableState.update { it.copy(viewedWeek = date) }
        load()
    }

    fun changeKind(kind: TimetableKind) {
        mutableState.update { it.copy(kind = kind) }
        load()
    }

    fun refresh() = load()

    /** The saved copy first, so the week is there at once and offline; then the school's. */
    private fun load() {
        val snapshot = state.value
        val friend = snapshot.selected ?: return
        val date = runCatching { LocalDate.parse(snapshot.viewedWeek.take(10)) }.getOrDefault(TimetableDates.monday())
        val kind = snapshot.kind
        loadJob?.cancel()
        mutableState.update { it.copy(week = null, loading = true, error = null) }
        loadJob = scope.launch {
            fun stillShown(state: FriendsState) = state.selectedID == friend.id && state.viewedWeek == snapshot.viewedWeek && state.kind == kind
            val repository = repository()
            try {
                val cached = withContext(Dispatchers.IO) { repository.cachedWeek(friend, date, kind) }
                if (cached != null) mutableState.update { if (stillShown(it)) it.copy(week = cached) else it }
                val week = withContext(Dispatchers.IO) { repository.loadWeek(friend, date, kind) }
                mutableState.update { if (stillShown(it)) it.copy(week = week, loading = false) else it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { if (stillShown(it)) it.copy(loading = false, error = message(error)) else it }
            }
        }
    }

    /** Adds a friend the Add friend flow has finished, and shows their timetable when asked to. */
    fun add(friend: Friend, show: Boolean) {
        scope.launch {
            val friends = withContext(Dispatchers.IO) { repository().add(friend) }
            mutableState.update { it.copy(friends = friends) }
            if (show) select(friend.id)
        }
    }

    fun rename(friendID: String, name: String) {
        scope.launch {
            val friends = withContext(Dispatchers.IO) { repository().rename(friendID, name) }
            mutableState.update { it.copy(friends = friends) }
        }
    }

    /** A removed friend's login and timetable are deleted from the device; their timetable gives way to the user's own. */
    fun remove(friendID: String) {
        if (state.value.selectedID == friendID) select(null)
        scope.launch {
            val friends = withContext(Dispatchers.IO) { repository().remove(friendID) }
            mutableState.update { it.copy(friends = friends) }
        }
    }

    /** Signing out takes friends' logins off the device too. */
    suspend fun removeAll() {
        loadJob?.cancel()
        withContext(Dispatchers.IO) { runCatching { repository().removeAll() } }
        mutableState.value = FriendsState()
    }

    fun message(error: Exception): String = friendErrorMessage(context, error)
}

/** A friend's timetable failure in the app's words. */
internal fun friendErrorMessage(context: Context, error: Exception): String = when ((error as? FriendTimetableException)?.reason) {
    FriendTimetableException.Reason.SIGN_IN_AGAIN -> context.getString(R.string.app_friends_error_sign_in_again)
    FriendTimetableException.Reason.NOT_PUBLISHED -> context.getString(R.string.app_friends_error_not_published)
    FriendTimetableException.Reason.UNEXPECTED_PAGE -> context.getString(R.string.app_friends_error_unexpected_page)
    FriendTimetableException.Reason.HTTP_STATUS -> context.getString(R.string.app_friends_error_status, error.status ?: 0)
    null -> safeError(error, context)
}
