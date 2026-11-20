package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.FriendTimetableSource
import com.bukovinafilip.gradey.model.LoginResponse
import com.bukovinafilip.gradey.model.MarksResponse
import com.bukovinafilip.gradey.model.PublicTimetableClass
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.TimetableAtom
import com.bukovinafilip.gradey.model.TimetableDayDTO
import com.bukovinafilip.gradey.model.TimetableEntity
import com.bukovinafilip.gradey.model.TimetableGroup
import com.bukovinafilip.gradey.model.TimetableHour
import com.bukovinafilip.gradey.model.TimetableKind
import com.bukovinafilip.gradey.model.TimetableResponse
import com.bukovinafilip.gradey.model.UserResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDate

class FriendsTest {
    private val today = LocalDate.parse("2026-11-19")
    private var clock = 1_000_000L

    private class Rejected(override val failureReason: AccountFailureReason) : RuntimeException(failureReason.name), UserFacingFailure

    /** A Bakaláři that only ever serves timetables; anything else fails the test. */
    private inner class School(var password: String = "secret") : BakalariClient {
        val calls = mutableListOf<String>()
        var validToken = "token-1"
        var refreshWorks = true
        private var issued = 1

        private fun issue(): LoginResponse { issued++; validToken = "token-$issued"; return LoginResponse(validToken, "refresh-$issued", "Bearer", 3600) }
        override suspend fun login(baseURL: String, username: String, password: String): LoginResponse {
            calls += "login"
            if (password != this.password) throw Rejected(AccountFailureReason.SCHOOL_BAD_CREDENTIALS)
            return issue()
        }
        override suspend fun refreshToken(baseURL: String, refreshToken: String): LoginResponse {
            calls += "refresh"
            if (!refreshWorks) throw Rejected(AccountFailureReason.SCHOOL_SESSION_EXPIRED)
            return issue()
        }
        override suspend fun fetchTimetable(baseURL: String, accessToken: String, date: String): TimetableResponse {
            calls += "timetable:$date"
            if (accessToken != validToken) throw Rejected(AccountFailureReason.SCHOOL_SESSION_EXPIRED)
            return DemoData.timetableResponse(date)
        }
        override suspend fun fetchPermanentTimetable(baseURL: String, accessToken: String): TimetableResponse {
            calls += "permanent"
            return DemoData.permanentTimetableResponse()
        }
        override suspend fun fetchMarks(baseURL: String, accessToken: String): MarksResponse = error("A friend's marks are never loaded.")
        override suspend fun fetchAbsences(baseURL: String, accessToken: String): AbsenceResponse = error("A friend's absence is never loaded.")
        override suspend fun fetchUser(baseURL: String, accessToken: String): UserResponse = error("A friend's profile is never loaded.")
        override suspend fun predictSubject(baseURL: String, accessToken: String, subject: Subject, markText: String, weight: Int): Subject = error("unused")
    }

    private class PublicPages(var published: Boolean = true) : BakalariPublicTimetableClient {
        val terms = mutableListOf<Pair<PublicTimetableTerm, LocalDate>>()
        val response = TimetableResponse(
            hours = listOf(TimetableHour("1", "1", "8:00", "8:45")),
            days = listOf(TimetableDayDTO(dayOfWeek = 1, atoms = listOf(
                TimetableAtom(hourID = "1", subjectID = "m"), TimetableAtom(hourID = "1", subjectID = "aj", groupIDs = listOf("g-AJ1")),
                TimetableAtom(hourID = "1", subjectID = "nj", groupIDs = listOf("g-NJ")), TimetableAtom(hourID = "1", subjectID = "tv", groupIDs = listOf("g-unknown")),
            )), TimetableDayDTO(dayOfWeek = 3)),
            groups = listOf(TimetableGroup("g-NJ", "NJ", "NJ"), TimetableGroup("g-AJ1", "AJ1", "AJ1"), TimetableGroup("g-x", "  ", null), TimetableGroup("g-AJ1b", "AJ1", "AJ1")),
            subjects = listOf(TimetableEntity("m", "M", "Matematika"), TimetableEntity("aj", "AJ", "Angličtina"), TimetableEntity("nj", "NJ", "Němčina"), TimetableEntity("tv", "TV", "Tělocvik")),
        )
        override suspend fun fetchClasses(schoolURL: String): List<PublicTimetableClass> =
            if (published) listOf(PublicTimetableClass("ZV", "1.A")) else throw FriendTimetableException(FriendTimetableException.Reason.NOT_PUBLISHED)
        override suspend fun fetchTimetable(schoolURL: String, classID: String, term: PublicTimetableTerm, weekStart: LocalDate): TimetableResponse {
            terms += term to weekStart
            return if (term == PublicTimetableTerm.PERMANENT) response else FriendRepository.rebased(response, weekStart)
        }
    }

    private fun repository(school: School = School(), pages: PublicPages = PublicPages(), store: FriendStore = InMemoryFriendStore(), cache: FriendTimetableCache = InMemoryFriendTimetableCache()) =
        FriendRepository(store, school, pages, cache, today = { today }, now = { clock })

    @Test fun aFriendsLoginIsCheckedByLoadingThisWeekAndOnlyTimetablesAreEverRequested() = runBlocking {
        val school = School()
        val store = InMemoryFriendStore()
        val repository = repository(school, store = store)
        val friend = repository.connectBakalari("  Kuba ", "skola.example.cz", "Gymnázium", " kuba ", "secret")
        assertThat(friend.name).isEqualTo("Kuba")
        assertThat(friend.source).isEqualTo(FriendTimetableSource.Bakalari("https://skola.example.cz", "Gymnázium", "kuba"))
        assertThat(school.calls).containsExactly("login", "timetable:2026-11-16").inOrder()
        assertThat(store.loadSession(friend.id)!!.bakalari!!.password).isEqualTo("secret")
        // Connecting does not add the friend; the caller does once the flow is finished.
        assertThat(repository.loadFriends()).isEmpty()
        assertThat(repository.add(friend)).containsExactly(friend)

        val week = repository.loadWeek(friend, LocalDate.parse("2026-11-25"), TimetableKind.WEEKLY)
        assertThat(week.weekStart).isEqualTo("2026-11-23")
        assertThat(repository.cachedWeek(friend, LocalDate.parse("2026-11-27"), TimetableKind.WEEKLY)).isEqualTo(week)
        assertThat(repository.cachedWeek(friend, LocalDate.parse("2026-12-02"), TimetableKind.WEEKLY)).isNull()
        assertThat(repository.loadWeek(friend, today, TimetableKind.PERMANENT).kind).isEqualTo(TimetableKind.PERMANENT)
        assertThat(school.calls.none { it == "marks" || it == "absence" }).isTrue()

        // A wrong password fails while adding and leaves nothing behind.
        val failure = runCatching { repository.connectBakalari("Eva", "skola.example.cz", null, "eva", "wrong") }.exceptionOrNull()
        assertThat((failure as UserFacingFailure).failureReason).isEqualTo(AccountFailureReason.SCHOOL_BAD_CREDENTIALS)
        assertThat(runCatching { repository.connectBakalari("Eva", "skola.example.cz", null, " ", "x") }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun anExpiredOrRejectedTokenIsRenewedAndASavedLoginIsTheLastResort() = runBlocking {
        val school = School()
        val store = InMemoryFriendStore()
        val repository = repository(school, store = store)
        val friend = repository.connectBakalari("Kuba", "skola.example.cz", null, "kuba", "secret")
        school.calls.clear()

        // The school dropped the token early: one refresh, then the same request again.
        school.validToken = "revoked"
        repository.loadWeek(friend, today, TimetableKind.WEEKLY)
        assertThat(school.calls).containsExactly("timetable:2026-11-16", "refresh", "timetable:2026-11-16").inOrder()

        // Past its expiry the token is refreshed before the request; a dead refresh token falls back to the saved login.
        school.calls.clear()
        clock += 3_600_000
        school.refreshWorks = false
        repository.loadWeek(friend, today, TimetableKind.WEEKLY)
        assertThat(school.calls).containsExactly("refresh", "login", "timetable:2026-11-16").inOrder()
        assertThat(store.loadSession(friend.id)!!.accessToken).isEqualTo(school.validToken)

        // The friend changed their password: nothing more can be done from here.
        clock += 3_600_000
        school.password = "changed"
        val failure = runCatching { repository.loadWeek(friend, today, TimetableKind.WEEKLY) }.exceptionOrNull() as FriendTimetableException
        assertThat(failure.reason).isEqualTo(FriendTimetableException.Reason.SIGN_IN_AGAIN)
        // The week saved earlier is still there to show.
        assertThat(repository.cachedWeek(friend, today, TimetableKind.WEEKLY)).isNotNull()
    }

    @Test fun aPublicClassShowsThisAndNextWeekLiveAndThePermanentTimetableOnOtherDates() = runBlocking {
        val pages = PublicPages()
        val repository = repository(pages = pages)
        val (baseURL, classes) = repository.publicClasses("skola.example.cz")
        assertThat(baseURL).isEqualTo("https://skola.example.cz")
        // Groups come from the permanent timetable, each once, blank ones left out.
        assertThat(repository.publicGroups(baseURL, classes.single().id)).containsExactly("AJ1", "NJ").inOrder()
        pages.terms.clear()

        val friend = repository.makePublicFriend(" Ema ", baseURL, "Gymnázium", classes.single(), groups = listOf("AJ1"))
        assertThat(friend.name).isEqualTo("Ema")
        repository.loadWeek(friend, today, TimetableKind.WEEKLY)
        repository.loadWeek(friend, today.plusWeeks(1), TimetableKind.WEEKLY)
        val later = repository.loadWeek(friend, today.plusWeeks(3), TimetableKind.WEEKLY)
        repository.loadWeek(friend, today, TimetableKind.PERMANENT)
        assertThat(pages.terms.map { it.first }).containsExactly(
            PublicTimetableTerm.ACTUAL, PublicTimetableTerm.NEXT, PublicTimetableTerm.PERMANENT, PublicTimetableTerm.PERMANENT,
        ).inOrder()
        // The permanent timetable is put on the dates of the week that was asked for.
        assertThat(later.weekStart).isEqualTo("2026-12-07")
        assertThat(later.days.map { it.date }).containsExactly("2026-12-07", "2026-12-09").inOrder()
        // Whole-class lessons, the friend's own group, and a group the page does not name; never the other group.
        assertThat(later.days.first().lessons.map { it.subjectAbbrev }).containsExactly("M", "AJ", "TV")
        assertThat(repository.loadWeek(repository.makePublicFriend("All", baseURL, null, classes.single(), groups = null), today, TimetableKind.WEEKLY).days.first().lessons).hasSize(4)

        pages.published = false
        val failure = runCatching { repository.publicClasses("skola.example.cz") }.exceptionOrNull() as FriendTimetableException
        assertThat(failure.reason).isEqualTo(FriendTimetableException.Reason.NOT_PUBLISHED)
    }

    @Test fun friendsAndTheDanceCourseSurviveBeingSaved() {
        // The stores keep these as JSON; both kinds of friend and every field must come back as they went in.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val friends = listOf(
            com.bukovinafilip.gradey.model.Friend("f1", "Kuba", FriendTimetableSource.Bakalari("https://skola.example.cz", "Gymnázium", "kuba"), 1_790_000_000_000),
            com.bukovinafilip.gradey.model.Friend("f2", "Ema", FriendTimetableSource.PublicClass("https://skola.example.cz", null, "ZV", "1.A", listOf("AJ1")), 1_790_000_000_001),
            com.bukovinafilip.gradey.model.Friend("f3", "Tom", FriendTimetableSource.PublicClass("https://skola.example.cz", "ZŠ", "2B", "2.B", null), 1_790_000_000_002),
        )
        val serializer = kotlinx.serialization.builtins.ListSerializer(com.bukovinafilip.gradey.model.Friend.serializer())
        val saved = json.encodeToString(serializer, friends)
        assertThat(json.decodeFromString(serializer, saved)).isEqualTo(friends)
        // A friend's password is in their session, never in the list.
        assertThat(saved).doesNotContain("password")

        val snapshot = runBlocking {
            VavruskaRepository(DemoVavruskaClient(), InMemoryVavruskaCourseStore(VavruskaDemoData.course), InMemoryVavruskaCache()).refresh(force = true, includeTicketLinks = true).snapshot
        }
        assertThat(snapshot.events).isNotEmpty()
        assertThat(snapshot.noticeBoard!!.items.first().paragraphs).isNotEmpty()
        assertThat(json.decodeFromString(VavruskaSnapshot.serializer(), json.encodeToString(VavruskaSnapshot.serializer(), snapshot))).isEqualTo(snapshot)
        assertThat(json.decodeFromString(VavruskaStoredCourse.serializer(), json.encodeToString(VavruskaStoredCourse.serializer(), VavruskaDemoData.course))).isEqualTo(VavruskaDemoData.course)
    }

    @Test fun renamingAndRemovingFriendsKeepsTheListAndDeletesTheirDataFromTheDevice() = runBlocking {
        val store = InMemoryFriendStore()
        val cache = InMemoryFriendTimetableCache()
        val repository = repository(store = store, cache = cache)
        val kuba = repository.connectBakalari("Kuba", "skola.example.cz", null, "kuba", "secret")
        val ema = repository.makePublicFriend("Ema", "https://skola.example.cz", null, PublicTimetableClass("ZV", "1.A"), null)
        repository.add(kuba)
        repository.add(ema)
        repository.loadWeek(ema, today, TimetableKind.WEEKLY)

        assertThat(repository.rename(kuba.id, "  Jakub ").map { it.name }).containsExactly("Jakub", "Ema").inOrder()
        // A blank name changes nothing.
        assertThat(repository.rename(kuba.id, "   ").map { it.name }).containsExactly("Jakub", "Ema").inOrder()

        assertThat(repository.remove(kuba.id).map { it.id }).containsExactly(ema.id)
        assertThat(store.loadSession(kuba.id)).isNull()
        assertThat(repository.cachedWeek(kuba, today, TimetableKind.WEEKLY)).isNull()
        assertThat(repository.cachedWeek(ema, today, TimetableKind.WEEKLY)).isNotNull()

        // Signing out takes every friend's login and timetable off the device.
        repository.removeAll()
        assertThat(repository.loadFriends()).isEmpty()
        assertThat(repository.cachedWeek(ema, today, TimetableKind.WEEKLY)).isNull()
    }
}
