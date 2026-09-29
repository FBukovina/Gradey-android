import { strict as assert } from "node:assert";
import {
  classifyFCMResponse,
  FCMClient,
  fcmData,
  readFCMConfiguration,
} from "../functions/_shared/fcm-delivery.ts";
import { sendDevicePush } from "../functions/_shared/push-routing.ts";
import {
  defaultNotificationPreferences,
  deliveryBatches,
  notificationSuppressionReason,
  quietHoursEnd,
  quietSummaryDispatchIdentity,
} from "../functions/_shared/notification-delivery.ts";

const event = {
  id: "event-1",
  user_id: "user-1",
  linked_account_id: "account-1",
  subject_abbrev: "MAT",
  subject_name: "Mathematics private name",
  mark_text: "1",
};
const asResponse = (
  value: unknown,
  status = 200,
  headers: Record<string, string> = {},
) => new Response(JSON.stringify(value), { status, headers });
const jsonPart = (part: string) =>
  JSON.parse(atob(part.replace(/-/g, "+").replace(/_/g, "/")));

Deno.test("FCM is opt-in and validates credential shape without exposing it", () => {
  assert.equal(readFCMConfiguration(() => undefined), null);
  assert.throws(
    () =>
      readFCMConfiguration((key) =>
        key === "FCM_ENABLED" ? "true" : "private-secret"
      ),
    /fcm_configuration_invalid/,
  );
  const vars: Record<string, string> = {
    FCM_ENABLED: "true",
    FCM_PROJECT_ID: "fixture-project",
    FCM_CLIENT_EMAIL: "test@fixture-project.iam.gserviceaccount.com",
    FCM_PRIVATE_KEY:
      "-----BEGIN PRIVATE KEY-----\\nZmFrZQ==\\n-----END PRIVATE KEY-----\\n",
  };
  assert.equal(
    readFCMConfiguration((key) => vars[key])?.privateKey.includes("\\n"),
    false,
  );
});

Deno.test("private payload omits mark and subject, summaries never impersonate one school", () => {
  const privateData = fcmData(
    "user-1",
    [event],
    "private_summary",
    "dispatch-1",
  );
  assert.equal(privateData.userID, "user-1");
  assert.equal(privateData.accountID, "account-1");
  assert.deepEqual(JSON.parse(privateData.accountIDs), ["account-1"]);
  assert.equal(privateData.dispatchID, "dispatch-1");
  assert.equal(privateData.markText, undefined);
  assert.equal(privateData.subjectName, undefined);
  assert.equal(privateData.body.includes("Mathematics"), false);
  const summary = fcmData(
    "user-1",
    [event, { ...event, id: "event-2", linked_account_id: "account-2" }],
    "private_summary",
    "summary-dispatch",
  );
  assert.equal(summary.accountID, undefined);
  assert.deepEqual(JSON.parse(summary.accountIDs), ["account-1", "account-2"]);
  assert.equal(summary.summaryCount, "2");
  assert.equal(summary.eventID, undefined);
  assert.equal(summary.subjectName, undefined);
  assert.equal(
    fcmData("user-1", [event], "unknown" as never, "dispatch").lockScreenDetail,
    "private_summary",
  );
  assert.throws(
    () => fcmData("other-user", [event], "full_details", "dispatch"),
    /fcm_invalid_dispatch/,
  );
});

Deno.test("digest account scope is complete, unique, stable and bounded", () => {
  const data = fcmData(
    "user-1",
    [
      { ...event, linked_account_id: "school-b" },
      { ...event, id: "event-2", linked_account_id: "school-a" },
      { ...event, id: "event-3", linked_account_id: "school-b" },
    ],
    "private_summary",
    "d",
  );
  assert.deepEqual(JSON.parse(data.accountIDs), ["school-a", "school-b"]);
  assert.equal(data.accountID, undefined);
  const events = Array.from(
    { length: 32 },
    (_, i) => ({ ...event, id: `e-${i}`, linked_account_id: `school-${i}` }),
  );
  assert.equal(
    JSON.parse(fcmData("user-1", events, "private_summary", "d").accountIDs)
      .length,
    32,
  );
  assert.throws(
    () =>
      fcmData(
        "user-1",
        [...events, { ...event, linked_account_id: "school-33" }],
        "private_summary",
        "d",
      ),
    /fcm_invalid_account_scope/,
  );
  for (const id of ["", " school", "school/name", "a".repeat(129)]) {
    assert.throws(
      () =>
        fcmData(
          "user-1",
          [{ ...event, linked_account_id: id }],
          "private_summary",
          "d",
        ),
      /fcm_invalid_account_scope/,
    );
  }
  assert.throws(
    () =>
      fcmData(
        "user-1",
        Array.from(
          { length: 17 },
          (_, i) => ({
            ...event,
            linked_account_id: "a".repeat(125) + String(i).padStart(3, "0"),
          }),
        ),
        "private_summary",
        "d",
      ),
    /fcm_invalid_account_scope/,
  );
});

Deno.test("structured fields reveal only the selected detail and remain bounded", () => {
  assert.equal(
    fcmData("user-1", [event], "mark_and_subject", "d").subjectName,
    "MAT",
  );
  assert.equal(
    fcmData("user-1", [event], "full_details", "d").subjectName,
    event.subject_name,
  );
  const huge = {
    ...event,
    mark_text: "🍎".repeat(2000),
    subject_name: "🍎".repeat(2000),
  };
  assert.ok(
    new TextEncoder().encode(
      JSON.stringify(fcmData("user-1", [huge], "full_details", "d")),
    ).length < 3500,
  );
});

Deno.test("maximum digest scope survives escaped and Unicode display copy within the FCM byte budget", () => {
  const owner = "1".repeat(36);
  const dispatch = "2".repeat(256);
  const accountIDs = Array.from(
    { length: 32 },
    (_, index) =>
      "a".repeat(index === 0 ? 57 : 58) + String(index).padStart(3, "0"),
  );
  assert.equal(JSON.stringify(accountIDs).length, 2048);
  for (
    const subject of ["\u0000".repeat(71), "語".repeat(71), "🧠".repeat(60)]
  ) {
    const events = accountIDs.map((accountID, index) => ({
      ...event,
      id: "3".repeat(36),
      user_id: owner,
      linked_account_id: accountID,
      subject_name: subject + index,
    }));
    const data = fcmData(owner, events, "full_details", dispatch);
    assert.ok(
      new TextEncoder().encode(JSON.stringify(data)).byteLength <= 3500,
    );
    assert.deepEqual(JSON.parse(data.accountIDs), [...accountIDs].sort());
    assert.equal(data.accountID, undefined);
    assert.equal(data.userID, owner);
    assert.equal(data.dispatchID, dispatch);
    assert.equal(data.summaryID, events[0].id);
    assert.equal(data.url, `gradey://marks?summary=${events[0].id}`);
    assert.equal(data.count, "32");
    assert.equal(data.lockScreenDetail, "full_details");
    assert.ok(data.body.length > 0 && data.body.isWellFormed());
    if (subject.startsWith("\u0000")) assert.ok(data.body.endsWith("…"));
    const privateData = fcmData(owner, events, "private_summary", dispatch);
    assert.equal(privateData.body, "Open Gradey to view the details.");
    assert.equal(privateData.subjectName, undefined);
    assert.equal(privateData.markText, undefined);
    assert.deepEqual(
      JSON.parse(privateData.accountIDs),
      [...accountIDs].sort(),
    );
  }
});

Deno.test("Android copy never cuts valid emoji into an isolated surrogate", () => {
  const data = fcmData(
    "user-1",
    [{
      ...event,
      mark_text: "🧠".repeat(40),
      subject_name: "🍎".repeat(100),
    }],
    "full_details",
    "dispatch",
  );
  assert.ok(data.body.isWellFormed());
  assert.ok(data.markText.isWellFormed());
  assert.ok(data.subjectName.isWellFormed());
  assert.equal(data.body.includes("\uFFFD"), false);
  assert.equal(data.body, `${"🧠".repeat(15)}… · ${"🍎".repeat(47)}…`);
});

Deno.test("display trimming never hides an oversized required identity envelope", () => {
  const owner = "owner".repeat(1000);
  assert.throws(
    () =>
      fcmData(
        owner,
        [{ ...event, user_id: owner }],
        "full_details",
        "dispatch",
      ),
    /fcm_payload_too_large/,
  );
});

Deno.test("only explicit typed UNREGISTERED invalidates, unrelated token-looking text does not", async () => {
  for (const status of [400, 401, 403, 404, 429, 500, 503]) {
    const result = await classifyFCMResponse(
      asResponse({
        error: { status: "UNREGISTERED", message: "private-token" },
      }, status),
    );
    assert.equal(result.invalidateToken, false);
    assert.equal(result.reason.includes("private-token"), false);
    assert.equal(result.retry, [401, 403, 429, 500, 503].includes(status));
  }
  const details = [{
    "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
    errorCode: "UNREGISTERED",
  }];
  assert.equal(
    (await classifyFCMResponse(asResponse({ error: { details } }, 404)))
      .invalidateToken,
    true,
  );
  assert.equal(
    (await classifyFCMResponse(asResponse({ error: { details } }, 400)))
      .invalidateToken,
    false,
  );
  assert.equal(
    (await classifyFCMResponse(
      asResponse({ error: { details: [{ errorCode: "UNREGISTERED" }] } }, 404),
    )).invalidateToken,
    false,
  );
});

Deno.test("FCM honors Retry-After seconds and date, no provider message leaks", async () => {
  const now = Date.parse("2026-09-29T10:00:00Z");
  assert.equal(
    (await classifyFCMResponse(
      asResponse({}, 429, { "retry-after": "600" }),
      now,
    )).retryAfterMilliseconds,
    600_000,
  );
  assert.equal(
    (await classifyFCMResponse(
      asResponse({}, 503, { "retry-after": "Tue, 29 Sep 2026 10:20:00 GMT" }),
      now,
    )).retryAfterMilliseconds,
    1_200_000,
  );
  assert.equal(
    (await classifyFCMResponse(new Response("secret", { status: 503 }), now))
      .reason,
    "fcm_status_503",
  );
});

Deno.test("real ephemeral RSA fixture signs limited OAuth JWT and fetch stays injected; access token cached", async () => {
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const pkcs8 = new Uint8Array(
    await crypto.subtle.exportKey("pkcs8", pair.privateKey),
  );
  const pem = `-----BEGIN PRIVATE KEY-----\n${
    btoa(String.fromCharCode(...pkcs8))
  }\n-----END PRIVATE KEY-----`;
  let oauthCalls = 0;
  let pushCalls = 0;
  let now = Date.parse("2026-09-29T10:00:00Z");
  const fakeFetch: typeof fetch = async (url, options) => {
    assert.equal(options?.redirect, "error");
    assert.ok(options?.signal);
    if (url === "https://oauth2.googleapis.com/token") {
      oauthCalls++;
      const body = new URLSearchParams(options?.body as URLSearchParams);
      assert.equal(
        body.get("grant_type"),
        "urn:ietf:params:oauth:grant-type:jwt-bearer",
      );
      const parts = body.get("assertion")!.split(".");
      assert.deepEqual(jsonPart(parts[0]), { alg: "RS256", typ: "JWT" });
      const claims = jsonPart(parts[1]);
      assert.equal(claims.iss, "test@fixture-project.iam.gserviceaccount.com");
      assert.equal(
        claims.scope,
        "https://www.googleapis.com/auth/firebase.messaging",
      );
      assert.equal(claims.aud, "https://oauth2.googleapis.com/token");
      assert.equal(claims.iat, now / 1000);
      assert.equal(claims.exp - claims.iat, 3600);
      const signature = Uint8Array.from(
        atob(parts[2].replace(/-/g, "+").replace(/_/g, "/")),
        (value) => value.charCodeAt(0),
      );
      assert.equal(
        await crypto.subtle.verify(
          "RSASSA-PKCS1-v1_5",
          pair.publicKey,
          signature,
          new TextEncoder().encode(parts.slice(0, 2).join(".")),
        ),
        true,
      );
      return asResponse({
        access_token: "fixture-access",
        token_type: "Bearer",
        expires_in: 3600,
      });
    }
    assert.equal(
      url,
      "https://fcm.googleapis.com/v1/projects/fixture-project/messages:send",
    );
    assert.equal(
      new Headers(options?.headers).get("authorization"),
      "Bearer fixture-access",
    );
    const body = JSON.parse(options?.body as string);
    assert.equal(body.message.token, "fixture-token");
    assert.equal(body.message.notification, undefined);
    assert.equal(body.message.android.notification, undefined);
    assert.equal(body.message.android.collapse_key, undefined);
    assert.equal(body.message.android.priority, "HIGH");
    assert.ok(
      Object.values(body.message.data).every((value) =>
        typeof value === "string"
      ),
    );
    pushCalls++;
    return asResponse({ name: "projects/fixture-project/messages/fixture" });
  };
  const client = new FCMClient({
    projectID: "fixture-project",
    clientEmail: "test@fixture-project.iam.gserviceaccount.com",
    privateKey: pem,
  }, { fetch: fakeFetch, subtle: crypto.subtle, now: () => now });
  const data = fcmData("user-1", [event], "private_summary", "dispatch-1");
  assert.ok(
    (await Promise.all([
      client.send("fixture-token", data),
      client.send("fixture-token", data),
    ])).every((result) => result.accepted),
  );
  assert.equal(oauthCalls, 1);
  assert.equal(pushCalls, 2);
  now += 3550_000;
  assert.equal((await client.send("fixture-token", data)).accepted, true);
  assert.equal(oauthCalls, 2);
});

Deno.test("signing and transport exceptions never expose credentials", async () => {
  const client = new FCMClient({
    projectID: "fixture-project",
    clientEmail: "test@fixture-project.iam.gserviceaccount.com",
    privateKey: "secret-malformed-pem",
  }, {
    fetch: () => {
      throw new Error("private-token-and-key");
    },
    subtle: crypto.subtle,
    now: Date.now,
  });
  const result = await client.send("fixture-token", {});
  assert.equal(result.reason, "fcm_signing_failed");
  assert.equal(result.invalidateToken, false);
  assert.equal(result.retry, true);
});

Deno.test("mixed platform routing preserves Apple payload and stable Android summary identity", async () => {
  const sent: { platform: string; id: string; payload: unknown }[] = [];
  const transports = {
    apns: async (
      _token: string,
      _environment: string,
      payload: unknown,
      id: string,
    ) => {
      sent.push({ platform: "apple", id, payload });
      return new Response(null, { status: 200 });
    },
    fcm: async (_token: string, data: Record<string, string>) => {
      sent.push({ platform: "android", id: data.dispatchID, payload: data });
      return {
        accepted: true,
        invalidateToken: false,
        retry: false,
        reason: "accepted",
      };
    },
  };
  for (const platform of ["ios", "macos", "android"]) {
    await sendDevicePush(
      "user-1",
      { id: "device-1", platform, environment: "production" },
      "fixture-token",
      [event],
      "private_summary",
      "row-id",
      transports,
    );
  }
  assert.deepEqual(sent.map((item) => item.platform), [
    "apple",
    "apple",
    "android",
  ]);
  assert.equal(sent[0].id, "row-id");
  assert.deepEqual((sent[0].payload as { aps: unknown }).aps, {
    alert: { title: "New mark", body: "Open Gradey to view it." },
    sound: "default",
  });
  const quietKey = "2026-09-29T06:00:00Z";
  const events = [{ ...event, quiet_delivery_key: quietKey }, {
    ...event,
    id: "event-2",
    quiet_delivery_key: quietKey,
  }];
  await sendDevicePush(
    "user-1",
    { id: "device-1", platform: "android", environment: "production" },
    "fixture-token",
    events,
    "private_summary",
    "ignored-row-id",
    transports,
  );
  await sendDevicePush(
    "user-1",
    { id: "device-1", platform: "android", environment: "production" },
    "fixture-token",
    events,
    "private_summary",
    "another-row-id",
    transports,
  );
  assert.equal(sent[3].id, sent[4].id);
  assert.equal(
    sent[3].id,
    (await quietSummaryDispatchIdentity(quietKey, "device-1")).apnsID,
  );
  assert.notEqual(
    sent[3].id,
    (await quietSummaryDispatchIdentity(quietKey, "device-2")).apnsID,
  );
  assert.equal(
    (await sendDevicePush(
      "user-1",
      { id: "device-1", platform: "unknown", environment: "production" },
      "fixture-token",
      [event],
      "private_summary",
      "id",
      transports,
    )).reason,
    "unsupported_platform",
  );
  assert.equal(sent.length, 5);
});

Deno.test("v8 suppression, quiet-hour timezone and batch isolation are retained", () => {
  const preferences = {
    ...defaultNotificationPreferences,
    quiet_hours_enabled: true,
  };
  assert.equal(
    notificationSuppressionReason(
      { ...preferences, new_marks_enabled: false },
      { status: "active", notifications_enabled: true },
    ),
    "global_notifications_disabled",
  );
  assert.equal(
    notificationSuppressionReason(preferences, {
      status: "active",
      notifications_enabled: false,
    }),
    "linked_account_notifications_disabled",
  );
  assert.equal(
    quietHoursEnd(new Date("2026-09-29T21:00:00Z"), preferences)?.toISOString(),
    "2026-09-30T04:00:00.000Z",
  );
  assert.equal(
    quietHoursEnd(new Date("2026-09-30T04:00:00Z"), preferences),
    null,
  );
  const quiet = {
    quiet_hours_deferred_at: "2026-09-29T21:00:00Z",
    quiet_delivery_key: "2026-09-30T04:00:00Z",
  };
  const batches = deliveryBatches([
    { ...quiet, id: "1" },
    { ...quiet, id: "2" },
    { id: "3", quiet_hours_deferred_at: null },
    { ...quiet, id: "4", quiet_delivery_key: "other-window" },
  ]);
  assert.deepEqual(batches.map((batch) => batch.map((value) => value.id)), [
    ["1", "2"],
    ["4"],
    ["3"],
  ]);
});
