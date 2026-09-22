export const eduPageParentSyncReason = "Background grade history and mark alerts are not available for EduPage parent accounts. Open the app to refresh the selected student’s grades.";

/** switchchild mutates the shared PHPSESSID. Never attribute that cookie's grades to an unverified child. */
export function isEduPageParentSession(secret: Record<string, unknown>): boolean {
  const session = secret.eduPage as Record<string, unknown> | undefined;
  if (!session || typeof session !== "object") return false;
  const userID = String(session.userID ?? "");
  return /rodic|parent/i.test(userID) || (Array.isArray(session.linkedStudents) && session.linkedStudents.length > 1);
}
