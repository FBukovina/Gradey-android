import { strict as assert } from "node:assert";
import { test } from "node:test";
import { notificationDecision, notificationPreferencesUpdate, notificationBodyForPreferences } from "./notification-preferences.ts";

const preferences = { new_marks_enabled: true, quiet_hours_enabled: true, quiet_hours_start_minute: 1320, quiet_hours_end_minute: 360 };

test("disabled account or global mark alerts always suppress delivery", () => {
  assert.equal(notificationDecision(preferences, false), "disabled");
  assert.equal(notificationDecision({ new_marks_enabled: false }, true), "disabled");
});
test("overnight quiet hours include start and exclude end in Prague summer time", () => {
  assert.equal(notificationDecision(preferences, true, new Date("2026-07-01T20:00:00Z")), "quiet");
  assert.equal(notificationDecision(preferences, true, new Date("2026-07-02T03:59:00Z")), "quiet");
  assert.equal(notificationDecision(preferences, true, new Date("2026-07-02T04:00:00Z")), "send");
});
test("Prague winter offset is applied", () => {
  assert.equal(notificationDecision(preferences, true, new Date("2026-01-01T20:59:00Z")), "send");
  assert.equal(notificationDecision(preferences, true, new Date("2026-01-01T21:00:00Z")), "quiet");
});
test("same-day and equal start/end ranges have defined boundaries", () => {
  const sameDay = { ...preferences, quiet_hours_start_minute: 600, quiet_hours_end_minute: 660 };
  assert.equal(notificationDecision(sameDay, true, new Date("2026-07-01T08:30:00Z")), "quiet");
  assert.equal(notificationDecision(sameDay, true, new Date("2026-07-01T09:00:00Z")), "send");
  assert.equal(notificationDecision({ ...sameDay, quiet_hours_end_minute: 600 }, true, new Date("2026-07-01T08:30:00Z")), "send");
});
test("unset preferences send normally and malformed times use safe defaults", () => {
  assert.equal(notificationDecision(null, true), "send");
  assert.equal(notificationDecision({ ...preferences, quiet_hours_start_minute: -1 }, true, new Date("2026-07-01T20:00:00Z")), "quiet");
});

test("editing Android mark alerts preserves an existing Apple privacy choice", () => {
  const update = notificationPreferencesUpdate({ newMarksEnabled: false }, { lock_screen_detail: "private_summary", quiet_hours_start_minute: 1260 });
  assert.equal(update.lock_screen_detail, "private_summary");
  assert.equal(update.quiet_hours_start_minute, 1260);
  assert.equal(update.new_marks_enabled, false);
});
test("invalid quiet hours and string booleans are rejected", () => {
  assert.throws(() => notificationPreferencesUpdate({ quietHoursStartMinute: 1440 }, null));
  assert.throws(() => notificationPreferencesUpdate({ newMarksEnabled: "false" }, null));
});
test("private summaries do not reveal a grade or subject in the push payload", () => {
  assert.equal(notificationBodyForPreferences("5 from Mathematics", { lock_screen_detail: "private_summary" }), "Open Gradey to see your updated grades.");
  assert.equal(notificationBodyForPreferences("1 from English", { lock_screen_detail: "mark_and_subject" }), "1 from English");
});
