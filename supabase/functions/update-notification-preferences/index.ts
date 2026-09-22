import { errorResponse, handleOptions, json } from "../_shared/http.ts";
import { requireUser } from "../_shared/client.ts";
import { notificationPreferencesUpdate } from "../_shared/notification-preferences.ts";

Deno.serve(async (req) => {
  const options = handleOptions(req);
  if (options) return options;

  try {
    const { supabase, user } = await requireUser(req);
    const body = await req.json();
    const { data: existing, error: readError } = await supabase.from("notification_preferences").select("*").eq("user_id", user.id).maybeSingle();
    if (readError) throw readError;
    let preferences;
    try { preferences = notificationPreferencesUpdate(body, existing); }
    catch (error) { return json({ error: error instanceof Error ? error.message : "Invalid notification settings" }, 422); }
    const { error } = await supabase
      .from("notification_preferences")
      .upsert({
        user_id: user.id,
        ...preferences,
        updated_at: new Date().toISOString(),
      }, { onConflict: "user_id" });
    if (error) throw error;

    return json({});
  } catch (error) {
    return errorResponse(error, "Could not update notification preferences");
  }
});
