# Personal planner and calendar lifecycle

Planner entries are personal, encrypted local data. Changing school accounts, clearing school caches, or signing out does not delete them. Android stores the planner and calendar ownership mappings separately in authenticated, atomic files under `noBackupFilesDir`; both remain independent of the school-session store.

## Explicit calendar export

A planner entry has no calendar target by default. The editor's **Keep in my calendar** switch requests `READ_CALENDAR` and `WRITE_CALENDAR` only after the user selects it. The user then chooses a writable calendar. Selecting a calendar changes only the draft; saving the planner entry commits the opt-in before any Calendar Provider mutation.

Gradey mirrors edits, completion, and deletion to its own exported event. It does not import calendar changes. The separate **Calendar copy** actions use an explicit calendar insertion intent or export an `.ics` file; those copies are not tracked or updated.

`PlannerCalendarMirror` compares saved entries with durable ownership mappings. Before an insertion, it persists a random ownership token and an attempted-write marker. A failed identifier acknowledgment is recoverable by locating the exact ownership token in the selected calendar. Calendar events must retain Gradey's package, custom URI, and description marker to be recognized as owned. Updates and removals include ownership conditions in the provider mutation, so recycled event identifiers do not grant ownership.

Deleting an entry retains a minimal mapping as a cleanup tombstone until its exported event is removed. A denied permission leaves attempted work pending. Disabling or deleting an export that was never attempted clears the mapping without requesting access. Changing the target calendar first cleans up the old owned event. A missing event is already removed; a missing enabled event is recreated when the entry is next edited.

Automatic activation retries must use the shared mirror and check `hasAccess`; they never request permission. The planner provides an explicit retry action when calendar work remains pending. Failed local writes never trigger calendar writes, and unreadable mapping state blocks synchronization rather than discarding ownership data.

## Dates and account boundaries

A due date overrides linked lesson timing. Date-only entries export as all-day events ending on the next calendar day. Android Calendar Provider receives UTC-midnight all-day boundaries; `.ics` uses civil dates. Timed events retain the item's saved time zone.

Permanent-timetable lessons deliberately carry no calendar date and cannot automatically become dated planner events. The user must choose a day or due date. Next-lesson resolution checks provider subject IDs, account scope, groups, cancellation state, and consecutive cached/online weeks.

## Validation

`PlannerCalendarMirrorTest` uses an injected fake adapter and store. It covers explicit opt-in, permission denial/revocation, updates/completion/deletion, failed ownership persistence, interrupted event-ID persistence, calendar changes, missing/recycled events, invalid dates, and unchanged entries. Calendar API mutations against a real account have not been performed by the automated verification. Native permission dialogs, calendar-account behavior, provider synchronization, and vendor-specific stripping of ownership metadata remain real-device release QA.
