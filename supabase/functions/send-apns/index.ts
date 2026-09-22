import { adminClient, providerSecretKey } from "../_shared/client.ts";
import { errorResponse, handleOptions, json } from "../_shared/http.ts";
import { notificationDecision, notificationBodyForPreferences } from "../_shared/notification-preferences.ts";
import { isInvalidDeviceToken } from "../_shared/push-response.ts";

Deno.serve(async (req) => {
  const options = handleOptions(req);
  if (options) return options;

  try {
    assertServiceRole(req);
    const supabase = adminClient();
    const { eventIDs } = await req.json();
    if (!Array.isArray(eventIDs) || eventIDs.length === 0) return json({ sent: 0 });

    const { data: events, error: eventError } = await supabase
      .from("new_mark_events")
      .select("*")
      .is("delivered_at", null)
      .in("id", eventIDs);
    if (eventError) throw eventError;

    let sent = 0;
    for (const event of events ?? []) {
      const [preferences, account] = await Promise.all([
        supabase.from("notification_preferences").select("*").eq("user_id", event.user_id).maybeSingle(),
        supabase.from("linked_accounts").select("notifications_enabled").eq("id", event.linked_account_id).eq("user_id", event.user_id).maybeSingle(),
      ]);
      if (preferences.error) throw preferences.error;
      if (account.error) throw account.error;
      const decision = notificationDecision(preferences.data, account.data?.notifications_enabled === true);
      if (decision === "quiet") continue;
      if (decision === "disabled") {
        const { error } = await supabase.from("new_mark_events").update({ delivered_at: new Date().toISOString() }).eq("id", event.id);
        if (error) throw error;
        continue;
      }
      const { data: devices, error: deviceError } = await supabase
        .from("device_push_tokens")
        .select("*")
        .eq("user_id", event.user_id)
        .in("platform", ["ios", "macos"])
        .is("invalidated_at", null);
      if (deviceError) throw deviceError;

      let retryNeeded = false;
      for (const device of devices ?? []) {
        const token = await readDeviceToken(supabase, device.token_secret_id);
        const response = await sendAPNS(token, device.environment, {
          aps: {
            alert: {
              title: event.notification_title || "New mark",
              body: notificationBodyForPreferences(event.notification_body || "", preferences.data),
            },
            sound: "default",
          },
          url: `gradey://marks?event=${event.id}`,
          eventID: event.id,
        });

        if (await isInvalidDeviceToken(device.platform, response)) {
          await supabase
            .from("device_push_tokens")
            .update({ invalidated_at: new Date().toISOString() })
            .eq("id", device.id);
        } else if (response.ok) {
          sent += 1;
        } else {
          retryNeeded = true;
        }
      }

      if (!retryNeeded) await supabase
        .from("new_mark_events")
        .update({ delivered_at: new Date().toISOString() })
        .eq("id", event.id);
    }

    return json({ sent });
  } catch (error) {
    return errorResponse(error, "Could not send APNs notifications");
  }
});

async function readDeviceToken(supabase: ReturnType<typeof adminClient>, secretID: string) {
  const { data, error } = await supabase.rpc("read_provider_secret", {
    p_secret_id: secretID,
    p_key: providerSecretKey(),
  });
  if (error) throw error;
  return data.token as string;
}

async function sendAPNS(token: string, environment: string, payload: unknown) {
  const topic = Deno.env.get("APNS_TOPIC");
  const jwt = await apnsJWT();
  const host = environment === "production" ? "https://api.push.apple.com" : "https://api.sandbox.push.apple.com";
  return await fetch(`${host}/3/device/${token}`, {
    method: "POST",
    headers: {
      authorization: `bearer ${jwt}`,
      "apns-topic": topic ?? "",
      "apns-push-type": "alert",
      "content-type": "application/json",
    },
    body: JSON.stringify(payload),
  });
}

async function apnsJWT() {
  const teamID = Deno.env.get("APNS_TEAM_ID");
  const keyID = Deno.env.get("APNS_KEY_ID");
  const privateKey = Deno.env.get("APNS_PRIVATE_KEY_P8");
  if (!teamID || !keyID || !privateKey) throw new Error("Missing APNs credentials");

  const header = base64URL(JSON.stringify({ alg: "ES256", kid: keyID }));
  const claims = base64URL(JSON.stringify({ iss: teamID, iat: Math.floor(Date.now() / 1000) }));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(privateKey),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    new TextEncoder().encode(`${header}.${claims}`),
  );
  return `${header}.${claims}.${base64URL(new Uint8Array(signature))}`;
}

function pemToArrayBuffer(pem: string) {
  const base64 = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  const binary = atob(base64);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0)).buffer;
}

function base64URL(value: string | Uint8Array) {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  const binary = String.fromCharCode(...bytes);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function assertServiceRole(req: Request) {
  const expected = `Bearer ${Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")}`;
  if (req.headers.get("Authorization") !== expected) {
    throw new Response("Service role required", { status: 401 });
  }
}
