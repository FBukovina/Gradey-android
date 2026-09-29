package com.bukovinafilip.gradey

import org.junit.Assert.*
import org.junit.Test

class DeepLinkRouterTest {
    @Test fun plannerNotificationKeepsItsExactItemID() {
        assertEquals(AppDestination(AppTab.PERSONAL_PLANNER, "saved-item"), gradeyDestination("gradey://planner/item/saved-item"))
        assertEquals(AppDestination(AppTab.PERSONAL_PLANNER), gradeyDestination("gradey://planner"))
    }
    @Test fun onlyKnownAppLinksCanChangeTheScreen() {
        assertNull(gradeyDestination("https://planner/item/saved-item"))
        assertNull(gradeyDestination("gradey://unrecognized"))
        assertNull(gradeyDestination("%invalid"))
        assertEquals(AppDestination(AppTab.TIMETABLE), gradeyDestination("gradely://timetable"))
        assertEquals(AppDestination(AppTab.SUBJECTS), gradeyDestination("gradey://marks?event=123"))
    }

    @Test fun plannerCreationOpensABoundedUnsavedDraft() {
        val result = requireNotNull(gradeyDestination("gradey://planner/new?title=Math+test&type=test&due=2026-10-02"))
        assertEquals(AppTab.PERSONAL_PLANNER, result.tab)
        assertNull(result.plannerItemID)
        assertEquals(PlannerDraftInput("Math test", com.bukovinafilip.gradey.model.PlannerItemType.TEST, "2026-10-02"), result.plannerDraft)
        val invalid = requireNotNull(gradeyDestination("gradey://planner/new?type=bogus&due=2026-02-30&title=" + "a".repeat(400))).plannerDraft!!
        assertEquals(300, invalid.title.length)
        assertNull(invalid.dueDate)
        assertEquals(com.bukovinafilip.gradey.model.PlannerItemType.HOMEWORK, invalid.type)
        assertNull(gradeyDestination("gradey://planner/item/new")?.plannerDraft)
    }
}
