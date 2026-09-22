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
    if (!Array.isArray(eventIDs) || eventIDs.length === 0) return json({ sent: 0, byPlatform: {} });

    const { data: events, error: eventError } = await supabase
      .from("new_mark_events")
      .select("*")
      .is("delivered_at", null)
      .in("id", eventIDs);
    if (eventError) throw eventError;

    let sent = 0;
    const byPlatform: Record<string, number> = {};
    for (const event of events ?? []) {
      const [preferences, account] = await Promise.all([
        supabase.from("notification_preferences").select("*").eq("user_id", event.user_id).maybeSingle(),
        supabase.from("linked_accounts").select("notifications_enabled").eq("id", event.linked_account_id).eq("user_id", event.user_id).maybeSingle(),
      ]);
      if (preferences.error) throw preferences.error;
      if (account.error) throw account.error;
      const decision = notificationDecision(preferences.data, account.data?.notifications_enabled === true);
      if (decision === "quiet") continue; // Leave pending for a later poll after quiet hours.
      if (decision === "disabled") {
        const { error } = await supabase.from("new_mark_events").update({ delivered_at: new Date().toISOString() }).eq("id", event.id);
        if (error) throw error;
        continue;
      }
      const { data: devices, error: deviceError } = await supabase
        .from("device_push_tokens")
        .select("*")
        .eq("user_id", event.user_id)
        .is("invalidated_at", null);
      if (deviceError) throw deviceError;

      let retryNeeded = false;
      for (const device of devices ?? []) {
        const token = await readDeviceToken(supabase, device.token_secret_id);
        const push = notificationPayload(event, preferences.data);
        const response = device.platform === "android"
          ? await sendFCM(token, push)
          : await sendAPNS(token, device.environment, apnsPayload(event, push));

        if (await isInvalidDeviceToken(device.platform, response)) {
          await supabase
            .from("device_push_tokens")
            .update({ invalidated_at: new Date().toISOString() })
            .eq("id", device.id);
        } else if (response.ok) {
          sent += 1;
          byPlatform[device.platform] = (byPlatform[device.platform] ?? 0) + 1;
        } else {
          retryNeeded = true;
        }
      }

      if (!retryNeeded) await supabase
        .from("new_mark_events")
        .update({ delivered_at: new Date().toISOString() })
        .eq("id", event.id);
    }

    return json({ sent, byPlatform });
  } catch (error) {
    return errorResponse(error, "Could not send push notifications");
  }
});

function notificationPayload(event: Record<string, any>, preferences: Record<string, unknown> | null) {
  return {
    title: event.notification_title || "New mark",
    body: notificationBodyForPreferences(event.notification_body || "", preferences),
    url: `gradey://marks?event=${event.id}`,
    eventID: String(event.id),
    userID: String(event.user_id),
  };
}

function apnsPayload(event: Record<string, any>, push: ReturnType<typeof notificationPayload>) {
  return {
    aps: {
      alert: {
        title: push.title,
        body: push.body,
      },
      sound: "default",
    },
    url: push.url,
    eventID: event.id,
  };
}

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

async function sendFCM(token: string, push: ReturnType<typeof notificationPayload>) {
  const projectID = Deno.env.get("FCM_PROJECT_ID");
  if (!projectID) throw new Error("Missing FCM_PROJECT_ID");

  const accessToken = await fcmAccessToken();
  return await fetch(`https://fcm.googleapis.com/v1/projects/${projectID}/messages:send`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      message: {
        token,
        data: {
          title: push.title,
          body: push.body,
          url: push.url,
          eventID: push.eventID,
          userID: push.userID,
        },
        android: {
          priority: "HIGH",
        },
      },
    }),
  });
}

async function fcmAccessToken() {
  const clientEmail = Deno.env.get("FCM_CLIENT_EMAIL");
  const privateKey = Deno.env.get("FCM_PRIVATE_KEY");
  if (!clientEmail || !privateKey) throw new Error("Missing FCM service account credentials");

  const now = Math.floor(Date.now() / 1000);
  const assertion = await serviceAccountJWT({
    iss: clientEmail,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }, privateKey);

  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) throw new Error(`fcm_oauth_status_${response.status}`);
  const payload = await response.json();
  return payload.access_token as string;
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

async function serviceAccountJWT(claims: Record<string, unknown>, privateKey: string) {
  const header = base64URL(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const payload = base64URL(JSON.stringify(claims));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(privateKey),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(`${header}.${payload}`),
  );
  return `${header}.${payload}.${base64URL(new Uint8Array(signature))}`;
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
