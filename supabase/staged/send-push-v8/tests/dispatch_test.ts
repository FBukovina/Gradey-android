import { strict as assert } from "node:assert";
import { setClient } from "./client-stub.ts";
import { defaultNotificationPreferences } from "../functions/_shared/notification-delivery.ts";

// Execute the actual v8 entrypoint against in-memory PostgREST/RPC responses.
// Deno permissions provide no network, environment, filesystem, or real push.
// deno-lint-ignore no-explicit-any
type Row = Record<string, any>;
const rsa = await crypto.subtle.generateKey(
  {
    name: "RSASSA-PKCS1-v1_5",
    modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]),
    hash: "SHA-256",
  },
  true,
  ["sign", "verify"],
);
const ec = await crypto.subtle.generateKey(
  { name: "ECDSA", namedCurve: "P-256" },
  true,
  ["sign", "verify"],
);
async function pem(key: CryptoKey) {
  const bytes = new Uint8Array(await crypto.subtle.exportKey("pkcs8", key));
  return `-----BEGIN PRIVATE KEY-----\n${
    btoa(String.fromCharCode(...bytes))
  }\n-----END PRIVATE KEY-----`;
}
const variables: Record<string, string> = {
  CRON_SECRET: "fixture-cron",
  FCM_ENABLED: "true",
  FCM_PROJECT_ID: "fixture-project",
  FCM_CLIENT_EMAIL: "test@fixture-project.iam.gserviceaccount.com",
  FCM_PRIVATE_KEY: await pem(rsa.privateKey),
  APNS_TOPIC: "fixture.bundle",
  APNS_TEAM_ID: "fixture-team",
  APNS_KEY_ID: "fixture-key",
  APNS_PRIVATE_KEY_P8: await pem(ec.privateKey),
};
const oldEnvGet = Deno.env.get;
const oldServe = Deno.serve;
const oldFetch = globalThis.fetch;
let currentFetch: typeof fetch;
const fixtureFetch: typeof fetch = (url, options) => currentFetch(url, options);
let handler: (req: Request) => Promise<Response>;
// Capture registration without opening a socket.
Deno.serve = ((fn: (req: Request) => Promise<Response>) => {
  handler = fn;
}) as typeof Deno.serve;
await import("../functions/send-apns/index.ts");
Deno.serve = oldServe;

class Query implements PromiseLike<{ data: Row[]; error: null }> {
  private filters: ((row: Row) => boolean)[] = [];
  private patch: Row | null = null;
  private inserts: Row[] | null = null;
  constructor(private rows: Row[]) {}
  select(_fields?: string) {
    return this;
  }
  eq(key: string, value: unknown) {
    this.filters.push((row) => row[key] === value);
    return this;
  }
  in(key: string, values: unknown[]) {
    this.filters.push((row) => values.includes(row[key]));
    return this;
  }
  is(key: string, value: unknown) {
    return this.eq(key, value);
  }
  update(patch: Row) {
    this.patch = patch;
    return this;
  }
  upsert(inserts: Row[], _options: unknown) {
    this.inserts = inserts;
    return this;
  }
  async maybeSingle() {
    return { data: (await this).data[0] ?? null, error: null };
  }
  then<TResult1 = { data: Row[]; error: null }, TResult2 = never>(
    resolve?:
      | ((
        value: { data: Row[]; error: null },
      ) => TResult1 | PromiseLike<TResult1>)
      | null,
    reject?: ((reason: unknown) => TResult2 | PromiseLike<TResult2>) | null,
  ): PromiseLike<TResult1 | TResult2> {
    if (this.inserts) {
      for (const value of this.inserts) {
        if (
          this.rows.some((row) =>
            row.event_id === value.event_id && row.device_id === value.device_id
          )
        ) continue;
        this.rows.push({
          id: `delivery-${value.event_id}-${value.device_id}`,
          apns_id: `dispatch-${value.event_id}-${value.device_id}`,
          delivery_due_at: new Date(0).toISOString(),
          delivery_attempt_count: 0,
          accepted_at: null,
          suppressed_at: null,
          ...value,
        });
      }
      this.inserts = null;
    }
    const selected = this.rows.filter((row) =>
      this.filters.every((filter) => filter(row))
    );
    if (this.patch) selected.forEach((row) => Object.assign(row, this.patch));
    // Supabase returns serialized snapshots rather than references to live rows.
    return Promise.resolve({ data: structuredClone(selected), error: null })
      .then(resolve, reject);
  }
}

class Database {
  tables: Record<string, Row[]> = {
    notification_preferences: [{
      user_id: "u",
      ...defaultNotificationPreferences,
      lock_screen_detail: "private_summary",
    }],
    linked_accounts: [{
      id: "a",
      user_id: "u",
      status: "active",
      notifications_enabled: true,
    }],
    device_push_tokens: ["ios", "android"].map((platform) => ({
      id: platform,
      platform,
      user_id: "u",
      environment: "production",
      token_secret_id: `${platform}-secret`,
      invalidated_at: null,
    })),
    new_mark_events: [{
      id: "e",
      user_id: "u",
      linked_account_id: "a",
      subject_abbrev: "MAT",
      subject_name: "Math",
      mark_text: "1",
      delivery_attempt_count: 0,
      delivery_targets_created_at: null,
      delivered_at: null,
      suppressed_at: null,
      quiet_hours_deferred_at: null,
    }],
    new_mark_event_deliveries: [],
  };
  claims = 0;
  finalized = 0;
  from(table: string) {
    assert.ok(this.tables[table], table);
    return new Query(this.tables[table]);
  }
  async rpc(name: string, args: Row) {
    if (name === "claim_new_mark_events") {
      this.claims++;
      const events = this.tables.new_mark_events.filter((row) =>
        row.delivered_at == null && row.suppressed_at == null &&
        (row.delivery_due_at == null ||
          Date.parse(row.delivery_due_at) <= Date.now())
      );
      for (const event of events) event.claim_token = args.p_claim_token;
      return { data: structuredClone(events), error: null };
    }
    if (name === "read_provider_secret") {
      return {
        data: { token: `${args.p_secret_id}-fixture-token` },
        error: null,
      };
    }
    if (name === "finalize_new_mark_event_deliveries") {
      this.finalized++;
      for (
        const event of this.tables.new_mark_events.filter((row) =>
          args.p_event_ids.includes(row.id)
        )
      ) {
        const rows = this.tables.new_mark_event_deliveries.filter((row) =>
          row.event_id === event.id
        );
        const pending = rows.filter((row) =>
          !row.accepted_at && !row.suppressed_at
        );
        event.claim_token = null;
        if (pending.length) {
          event.delivery_due_at = pending.map((row) =>
            row.delivery_due_at
          ).sort()[0];
        } else event.delivered_at = new Date().toISOString();
      }
      return { error: null, data: null };
    }
    throw new Error(`unexpected fixture RPC ${name}`);
  }
  makeDue() {
    for (
      const row of [
        ...this.tables.new_mark_events,
        ...this.tables.new_mark_event_deliveries,
      ]
    ) row.delivery_due_at = new Date(0).toISOString();
  }
}

async function dispatch(
  database: Database,
  fcmStatus: number,
  enabled = true,
  details: unknown[] = [],
) {
  const sends: { platform: string; payload: Row }[] = [];
  setClient(database);
  Deno.env.get = (key: string) =>
    key === "FCM_ENABLED" ? String(enabled) : variables[key];
  globalThis.fetch = fixtureFetch;
  currentFetch = async (url, options) => {
    const destination = String(url);
    if (destination === "https://oauth2.googleapis.com/token") {
      return Response.json({
        access_token: "fixture-access",
        token_type: "Bearer",
        expires_in: 3600,
      });
    }
    const payload = JSON.parse(options?.body as string);
    if (
      destination.startsWith(
        "https://api.push.apple.com/3/device/ios-secret-fixture-token",
      )
    ) {
      sends.push({ platform: "ios", payload });
      return new Response(null, { status: 200 });
    }
    assert.equal(
      destination,
      "https://fcm.googleapis.com/v1/projects/fixture-project/messages:send",
    );
    sends.push({ platform: "android", payload });
    return Response.json(
      fcmStatus === 200
        ? { name: "projects/fixture-project/messages/fixture-message" }
        : { error: { message: "secret-never-persist", details } },
      { status: fcmStatus, headers: { "retry-after": "900" } },
    );
  };
  try {
    const response = await handler(
      new Request("https://fixture.invalid/send-apns", {
        method: "POST",
        headers: { "x-cron-secret": "fixture-cron" },
        body: "{}",
      }),
    );
    assert.equal(response.status, 200);
    return { counters: await response.json(), sends };
  } finally {
    Deno.env.get = oldEnvGet;
    globalThis.fetch = oldFetch;
  }
}

Deno.test("actual dispatcher seals Apple+Android targets, retries only unfinished device with same identity", async () => {
  const database = new Database();
  const before = Date.now();
  const first = await dispatch(database, 503);
  assert.deepEqual(first.sends.map((send) => send.platform), [
    "ios",
    "android",
  ]);
  assert.equal(first.counters.sent, 1);
  assert.equal(first.counters.retried, 1);
  assert.equal(database.tables.new_mark_event_deliveries.length, 2);
  assert.equal(database.tables.new_mark_events[0].claim_token, null);
  assert.ok(database.tables.new_mark_events[0].delivery_targets_created_at);
  const android = database.tables.new_mark_event_deliveries.find((row) =>
    row.device_id === "android"
  )!;
  assert.ok(Date.parse(android.delivery_due_at) >= before + 900_000);
  assert.equal(android.delivery_attempt_count, 1);
  assert.equal(android.last_delivery_error, "fcm_status_503");
  const identity = first.sends[1].payload.message.data.dispatchID;
  database.makeDue();
  const second = await dispatch(database, 200);
  assert.deepEqual(second.sends.map((send) => send.platform), ["android"]);
  assert.equal(second.sends[0].payload.message.data.dispatchID, identity);
  assert.equal(second.counters.sent, 1);
  assert.ok(database.tables.new_mark_events[0].delivered_at);
  assert.equal(database.tables.new_mark_event_deliveries.length, 2);
  assert.equal(database.claims, 2);
  assert.equal(database.finalized, 2);
});

Deno.test("actual dispatcher leaves disabled FCM queued without consuming retries or repeating accepted Apple", async () => {
  const database = new Database();
  const first = await dispatch(database, 200, false);
  assert.deepEqual(first.sends.map((send) => send.platform), ["ios"]);
  assert.equal(first.counters.rescheduled, 1);
  const android = database.tables.new_mark_event_deliveries.find((row) =>
    row.device_id === "android"
  )!;
  assert.equal(android.delivery_attempt_count, 0);
  assert.equal(android.suppressed_at, null);
  assert.equal(android.last_delivery_error, "fcm_configuration_unavailable");
  database.makeDue();
  assert.deepEqual(
    (await dispatch(database, 200)).sends.map((send) => send.platform),
    ["android"],
  );
});

Deno.test("actual dispatcher invalidates only explicit FCM rejection, permanent payload errors retain token", async () => {
  const malformed = new Database();
  const first = await dispatch(malformed, 400);
  assert.equal(first.counters.invalidatedTokens, 0);
  assert.equal(malformed.tables.device_push_tokens[1].invalidated_at, null);
  assert.ok(malformed.tables.new_mark_event_deliveries[1].suppressed_at);
  const expired = new Database();
  const second = await dispatch(expired, 404, true, [{
    "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError",
    errorCode: "UNREGISTERED",
  }]);
  assert.equal(second.counters.invalidatedTokens, 1);
  assert.ok(expired.tables.device_push_tokens[1].invalidated_at);
  assert.equal(expired.tables.device_push_tokens[0].invalidated_at, null);
});

Deno.test("actual dispatcher suppresses disabled accounts before sealing targets or sending", async () => {
  const database = new Database();
  database.tables.linked_accounts[0].notifications_enabled = false;
  const result = await dispatch(database, 200);
  assert.equal(result.sends.length, 0);
  assert.equal(database.tables.new_mark_event_deliveries.length, 0);
  assert.equal(
    database.tables.new_mark_events[0].suppression_reason,
    "linked_account_notifications_disabled",
  );
});

Deno.test("actual quiet-hours delivery seals devices at release and produces one cross-account summary per device", async () => {
  const database = new Database();
  const now = new Date();
  const minute = now.getUTCHours() * 60 + now.getUTCMinutes();
  Object.assign(database.tables.notification_preferences[0], {
    quiet_hours_enabled: true,
    quiet_hours_time_zone: "UTC",
    quiet_hours_start_minute: minute,
    quiet_hours_end_minute: (minute + 2) % 1440,
  });
  database.tables.linked_accounts.push({
    id: "a2",
    user_id: "u",
    status: "active",
    notifications_enabled: true,
  });
  database.tables.new_mark_events.push({
    ...database.tables.new_mark_events[0],
    id: "e2",
    linked_account_id: "a2",
  });
  const quiet = await dispatch(database, 200);
  assert.equal(quiet.sends.length, 0);
  assert.equal(quiet.counters.rescheduled, 2);
  assert.equal(database.tables.new_mark_event_deliveries.length, 0);
  assert.ok(database.tables.new_mark_events[0].quiet_delivery_key);
  assert.equal(
    database.tables.new_mark_events[0].quiet_delivery_key,
    database.tables.new_mark_events[1].quiet_delivery_key,
  );
  database.tables.notification_preferences[0].quiet_hours_enabled = false;
  database.tables.device_push_tokens.push({
    ...database.tables.device_push_tokens[1],
    id: "new-android",
  });
  database.makeDue();
  const released = await dispatch(database, 200);
  assert.equal(released.sends.length, 3);
  assert.equal(database.tables.new_mark_event_deliveries.length, 6);
  assert.equal(released.counters.sent, 6);
  for (
    const send of released.sends.filter((send) => send.platform === "android")
  ) {
    assert.equal(send.payload.message.data.summaryCount, "2");
    assert.equal(send.payload.message.data.accountID, undefined);
    assert.equal(send.payload.message.data.subjectName, undefined);
  }
  assert.notEqual(
    released.sends[1].payload.message.data.dispatchID,
    released.sends[2].payload.message.data.dispatchID,
  );
});

Deno.test("actual dispatcher retains the five-attempt bound and excludes other owners", async () => {
  const database = new Database();
  database.tables.device_push_tokens.push({
    ...database.tables.device_push_tokens[1],
    id: "other-owner-device",
    user_id: "other-owner",
  });
  await dispatch(database, 503);
  const android = database.tables.new_mark_event_deliveries.find((row) =>
    row.device_id === "android"
  )!;
  android.delivery_attempt_count = 4;
  database.makeDue();
  const last = await dispatch(database, 503);
  assert.equal(last.sends.length, 1);
  assert.equal(android.delivery_attempt_count, 5);
  assert.equal(android.suppression_reason, "retry_limit_reached");
  assert.ok(android.suppressed_at);
  assert.equal(
    database.tables.new_mark_event_deliveries.some((row) =>
      row.device_id === "other-owner-device"
    ),
    false,
  );
});
