import { strict as assert } from "node:assert";
import { test } from "node:test";
import { isInvalidDeviceToken } from "./push-response.ts";

test("FCM payload and credential failures do not invalidate devices", async () => {
  const response = new Response(JSON.stringify({ error: { status: "INVALID_ARGUMENT" } }), { status: 400 });
  assert.equal(await isInvalidDeviceToken("android", response), false);
  assert.equal(await isInvalidDeviceToken("android", new Response("unauthorized", { status: 401 })), false);
});

test("FCM explicitly unregistered tokens are invalidated", async () => {
  const response = new Response(JSON.stringify({ error: { details: [{ "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", errorCode: "UNREGISTERED" }] } }), { status: 404 });
  assert.equal(await isInvalidDeviceToken("android", response), true);
});

test("APNs topic configuration failures preserve tokens while revoked tokens are removed", async () => {
  assert.equal(await isInvalidDeviceToken("ios", new Response(JSON.stringify({ reason: "TopicDisallowed" }), { status: 400 })), false);
  assert.equal(await isInvalidDeviceToken("ios", new Response(JSON.stringify({ reason: "BadDeviceToken" }), { status: 400 })), true);
  assert.equal(await isInvalidDeviceToken("ios", new Response("", { status: 410 })), true);
});
