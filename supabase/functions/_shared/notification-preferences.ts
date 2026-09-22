/** The current schema has no per-user timezone; match the school poller's Prague timezone. */
export function notificationDecision(
  preferences: Record<string, unknown> | null,
  accountEnabled: boolean,
  now: Date = new Date(),
): "send" | "disabled" | "quiet" {
  if (!accountEnabled || preferences?.new_marks_enabled === false) return "disabled";
  if (preferences?.quiet_hours_enabled !== true) return "send";
  const start = minuteValue(preferences.quiet_hours_start_minute, 1320);
  const end = minuteValue(preferences.quiet_hours_end_minute, 360);
  if (start === end) return "send";
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Europe/Prague", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  }).formatToParts(now);
  const minute = Number(parts.find((part) => part.type === "hour")?.value) * 60 +
    Number(parts.find((part) => part.type === "minute")?.value);
  const quiet = start < end ? minute >= start && minute < end : minute >= start || minute < end;
  return quiet ? "quiet" : "send";
}

function minuteValue(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0 && value < 1440 ? value : fallback;
}

/** A client editing one setting must not reset another platform's privacy settings. */
export function notificationPreferencesUpdate(body: Record<string, unknown>, existing: Record<string, unknown> | null) {
  const value = (camel: string, snake: string, fallback: unknown) => body[camel] ?? body[snake] ?? existing?.[snake] ?? fallback;
  const newMarks = value("newMarksEnabled", "new_marks_enabled", true);
  const quiet = value("quietHoursEnabled", "quiet_hours_enabled", false);
  const start = value("quietHoursStartMinute", "quiet_hours_start_minute", 1320);
  const end = value("quietHoursEndMinute", "quiet_hours_end_minute", 360);
  const detail = value("lockScreenDetail", "lock_screen_detail", "mark_and_subject");
  if (typeof newMarks !== "boolean" || typeof quiet !== "boolean") throw new Error("Notification choices must be booleans");
  if (![start, end].every((minute) => typeof minute === "number" && Number.isInteger(minute) && minute >= 0 && minute < 1440)) {
    throw new Error("Quiet hours must contain valid minutes of the day");
  }
  if (typeof detail !== "string" || !["private_summary", "mark_and_subject", "full_details"].includes(detail)) {
    throw new Error("Unknown notification privacy setting");
  }
  return {
    new_marks_enabled: newMarks,
    quiet_hours_enabled: quiet,
    quiet_hours_start_minute: start,
    quiet_hours_end_minute: end,
    lock_screen_detail: detail,
  };
}

export function notificationBodyForPreferences(body: string, preferences: Record<string, unknown> | null): string {
  return preferences?.lock_screen_detail === "private_summary" ? "Open Gradey to see your updated grades." : body;
}
