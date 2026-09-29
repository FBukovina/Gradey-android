import {
  deliveryDeepLink,
  type LockScreenDetail,
  notificationCopy,
  type NotificationEvent,
} from "./notification-delivery.ts";

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const MESSAGING_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const FCM_ERROR_TYPE = "type.googleapis.com/google.firebase.fcm.v1.FcmError";
const encoder = new TextEncoder();
const MAX_ACCOUNT_IDS = 32;
const MAX_ACCOUNT_SCOPE_BYTES = 2048;
const MAX_DATA_BYTES = 3500;
const ACCOUNT_ID = /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/;

export interface FCMConfiguration {
  projectID: string;
  clientEmail: string;
  privateKey: string;
}

export interface PushResult {
  accepted: boolean;
  invalidateToken: boolean;
  retry: boolean;
  reason: string;
  retryAfterMilliseconds?: number;
}

export class FCMFailure extends Error {
  constructor(readonly reason: string, readonly retryAfterMilliseconds = 0) {
    super(reason);
  }
}

/** Explicitly opt in only after matching the Android Firebase project. */
export function readFCMConfiguration(
  env: (key: string) => string | undefined,
): FCMConfiguration | null {
  if (env("FCM_ENABLED") !== "true") return null;
  const projectID = env("FCM_PROJECT_ID") ?? "";
  const clientEmail = env("FCM_CLIENT_EMAIL") ?? "";
  const privateKey = (env("FCM_PRIVATE_KEY") ?? "").replace(/\\n/g, "\n");
  if (
    !/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(projectID) ||
    !/^[^\s@]+@[^\s@]+\.iam\.gserviceaccount\.com$/.test(clientEmail) ||
    !privateKey.startsWith("-----BEGIN PRIVATE KEY-----\n") ||
    !privateKey.trimEnd().endsWith("-----END PRIVATE KEY-----")
  ) throw new FCMFailure("fcm_configuration_invalid");
  return { projectID, clientEmail, privateKey };
}

/** No notification object: Android must re-check identity/privacy before display. */
export function fcmData(
  userID: string,
  events: NotificationEvent[],
  detail: LockScreenDetail,
  dispatchID: string,
): Record<string, string> {
  if (
    !userID || !dispatchID || events.length === 0 ||
    events.some((event) => event.user_id !== userID)
  ) throw new FCMFailure("fcm_invalid_dispatch");
  const accountIDs = [
    ...new Set(events.map((event) => event.linked_account_id)),
  ]
    .sort();
  const encodedAccountIDs = JSON.stringify(accountIDs);
  if (
    accountIDs.length > MAX_ACCOUNT_IDS ||
    accountIDs.some((id) => typeof id !== "string" || !ACCOUNT_ID.test(id)) ||
    encoder.encode(encodedAccountIDs).byteLength > MAX_ACCOUNT_SCOPE_BYTES
  ) throw new FCMFailure("fcm_invalid_account_scope");
  // Unknown stored preferences fail closed instead of revealing a mark.
  const safeDetail = ["mark_and_subject", "full_details"].includes(detail)
    ? detail
    : "private_summary";
  // The captured APNs helper clips UTF-16 code units. Prepare Android-only text
  // at complete code-point boundaries before that helper can split an emoji.
  const subjectLimit = events.length === 1 ? 96 : 72;
  const subjectText = (value: string | null | undefined) =>
    value
      ? clippedUTF16(events.length === 1 ? value : value.trim(), subjectLimit)
      : value;
  const copy = notificationCopy(
    events.map((event) => ({
      ...event,
      mark_text: clippedUTF16(event.mark_text, 32),
      subject_name: subjectText(event.subject_name),
      subject_abbrev: subjectText(event.subject_abbrev),
    })),
    safeDetail,
  );
  const data: Record<string, string> = {
    schemaVersion: "1",
    type: "new_marks",
    userID,
    dispatchID,
    title: copy.title,
    body: copy.body,
    url: deliveryDeepLink(events),
    lockScreenDetail: safeDetail,
    count: String(events.length),
    accountIDs: encodedAccountIDs,
  };
  // A quiet summary can span schools. Never label it as only the first school.
  if (accountIDs.length === 1) data.accountID = accountIDs[0];
  if (events.length === 1) {
    const event = events[0];
    data.eventID = event.id;
    if (safeDetail !== "private_summary") {
      data.markText = clipped(event.mark_text, 32);
      const subject = safeDetail === "full_details"
        ? event.subject_name || event.subject_abbrev
        : event.subject_abbrev;
      if (subject) data.subjectName = clipped(subject, 96);
    }
  } else {
    data.summaryID = events[0].id;
    data.summaryCount = String(events.length);
  }
  // Keep the complete account/owner/dispatch identity. Long display copy can
  // shrink to fit; rejecting the entire digest would repeatedly lose its alert.
  for (const field of ["body", "subjectName", "markText", "title"]) {
    const overflow = jsonBytes(data) - MAX_DATA_BYTES;
    if (overflow <= 0) break;
    const value = data[field];
    if (value == null) continue;
    data[field] = clippedJSONBytes(
      value,
      Math.max(2, jsonBytes(value) - overflow),
    );
  }
  // Conservative headroom under FCM's 4096-byte keys+values limit. An invalid
  // required envelope still fails rather than truncating identifiers or scope.
  if (jsonBytes(data) > MAX_DATA_BYTES) {
    throw new FCMFailure("fcm_payload_too_large");
  }
  return data;
}

/** Only a typed FCM UNREGISTERED invalidates a token, never a generic 400/404. */
export async function classifyFCMResponse(
  response: Response,
  now = Date.now(),
): Promise<PushResult> {
  const body = await response.json().catch(() => null);
  if (response.ok) {
    const accepted = typeof body?.name === "string" &&
      /^projects\/[^/]+\/messages\/.+/.test(body.name);
    return {
      accepted,
      invalidateToken: false,
      retry: !accepted,
      reason: accepted ? "accepted" : "fcm_response_invalid",
    };
  }
  const details = Array.isArray(body?.error?.details) ? body.error.details : [];
  const typed = details.find((value: Record<string, unknown>) =>
    value?.["@type"] === FCM_ERROR_TYPE && typeof value.errorCode === "string"
  )?.errorCode;
  const knownCodes = [
    "UNREGISTERED",
    "INVALID_ARGUMENT",
    "SENDER_ID_MISMATCH",
    "QUOTA_EXCEEDED",
    "UNAVAILABLE",
    "INTERNAL",
    "THIRD_PARTY_AUTH_ERROR",
    "UNSPECIFIED_ERROR",
  ];
  const code = knownCodes.includes(typed) ? typed : null;
  const invalidateToken = response.status === 404 && code === "UNREGISTERED";
  const retry = !invalidateToken && (
    response.status === 401 || response.status === 403 ||
    response.status === 408 || response.status === 429 || response.status >= 500
  );
  return {
    accepted: false,
    invalidateToken,
    retry,
    // Never persist provider messages/field violations (may include tokens).
    reason: code
      ? `fcm_${code.toLowerCase()}`
      : `fcm_status_${response.status}`,
    retryAfterMilliseconds: retry ? retryAfterMilliseconds(response, now) : 0,
  };
}

export function retryAfterMilliseconds(
  response: Response,
  now: number,
): number {
  const header = response.headers.get("retry-after");
  if (!header) return 0;
  const delay = /^\d+$/.test(header)
    ? Number(header) * 1000
    : Date.parse(header) - now;
  return Number.isFinite(delay) ? Math.max(0, delay) : 0;
}

interface FCMDependencies {
  fetch: typeof fetch;
  subtle: SubtleCrypto;
  now: () => number;
}

/** One token refresh in flight per isolate; no keys/tokens in thrown errors. */
export class FCMClient {
  private cachedToken: { value: string; expiresAt: number } | null = null;
  private refreshing: Promise<string> | null = null;

  constructor(
    private readonly configuration: FCMConfiguration,
    private readonly dependencies: FCMDependencies = {
      fetch,
      subtle: crypto.subtle,
      now: Date.now,
    },
  ) {}

  async send(token: string, data: Record<string, string>): Promise<PushResult> {
    try {
      if (!token) throw new FCMFailure("fcm_missing_device_token");
      const accessToken = await this.accessToken();
      const response = await this.dependencies.fetch(
        `https://fcm.googleapis.com/v1/projects/${this.configuration.projectID}/messages:send`,
        {
          method: "POST",
          redirect: "error",
          headers: {
            authorization: `Bearer ${accessToken}`,
            "content-type": "application/json",
          },
          body: JSON.stringify({
            message: {
              token,
              data,
              android: {
                priority: "HIGH",
                ttl: "3600s",
                // No collapse_key: distinct grade events must not replace each
                // other or hit FCM's four simultaneously stored collapse keys.
              },
            },
          }),
          signal: AbortSignal.timeout(15_000),
        },
      );
      if (response.status === 401) this.cachedToken = null;
      return await classifyFCMResponse(response, this.dependencies.now());
    } catch (error) {
      // An ambiguous send may already have arrived. Retries retain dispatchID;
      // the Android consumer uses that identity to deduplicate display.
      return {
        accepted: false,
        invalidateToken: false,
        retry: true,
        reason: error instanceof FCMFailure
          ? error.reason
          : "fcm_transport_error",
        retryAfterMilliseconds: error instanceof FCMFailure
          ? error.retryAfterMilliseconds
          : 0,
      };
    }
  }

  private async accessToken(): Promise<string> {
    if (
      this.cachedToken && this.cachedToken.expiresAt > this.dependencies.now()
    ) {
      return this.cachedToken.value;
    }
    if (this.refreshing) return await this.refreshing;
    this.refreshing = this.exchangeToken();
    try {
      return await this.refreshing;
    } finally {
      this.refreshing = null;
    }
  }

  private async exchangeToken(): Promise<string> {
    const now = Math.floor(this.dependencies.now() / 1000);
    let assertion: string;
    try {
      const unsigned = `${
        base64URL(JSON.stringify({ alg: "RS256", typ: "JWT" }))
      }.${
        base64URL(JSON.stringify({
          iss: this.configuration.clientEmail,
          scope: MESSAGING_SCOPE,
          aud: TOKEN_URL,
          iat: now,
          exp: now + 3600,
        }))
      }`;
      const pem = this.configuration.privateKey.replace(
        /-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g,
        "",
      );
      const key = await this.dependencies.subtle.importKey(
        "pkcs8",
        Uint8Array.from(atob(pem), (character) => character.charCodeAt(0)),
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["sign"],
      );
      const signature = await this.dependencies.subtle.sign(
        "RSASSA-PKCS1-v1_5",
        key,
        encoder.encode(unsigned),
      );
      assertion = `${unsigned}.${base64URL(new Uint8Array(signature))}`;
    } catch {
      throw new FCMFailure("fcm_signing_failed");
    }
    const response = await this.dependencies.fetch(TOKEN_URL, {
      method: "POST",
      redirect: "error",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }),
      signal: AbortSignal.timeout(15_000),
    });
    if (!response.ok) {
      throw new FCMFailure(
        `fcm_oauth_status_${response.status}`,
        retryAfterMilliseconds(response, this.dependencies.now()),
      );
    }
    const body = await response.json().catch(() => null);
    if (
      typeof body?.access_token !== "string" || body.access_token.length < 1 ||
      body?.token_type?.toLowerCase() !== "bearer" ||
      typeof body?.expires_in !== "number" ||
      !Number.isFinite(body.expires_in) ||
      body.expires_in <= 60
    ) throw new FCMFailure("fcm_oauth_response_invalid");
    this.cachedToken = {
      value: body.access_token,
      expiresAt: this.dependencies.now() +
        (Math.min(body.expires_in, 3600) - 60) * 1000,
    };
    return body.access_token;
  }
}

function base64URL(value: string | Uint8Array): string {
  const bytes = typeof value === "string" ? encoder.encode(value) : value;
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(
    /\//g,
    "_",
  ).replace(/=+$/g, "");
}

function clipped(value: string, length: number): string {
  return Array.from(value).slice(0, length).join("");
}

function jsonBytes(value: unknown): number {
  return encoder.encode(JSON.stringify(value)).byteLength;
}

/** Retains the captured helper's UTF-16 limit without cutting a surrogate pair. */
function clippedUTF16(value: string, maximumLength: number): string {
  if (value.length <= maximumLength) return value;
  let prefix = "";
  for (const symbol of value) {
    if (prefix.length + symbol.length > maximumLength - 1) break;
    prefix += symbol;
  }
  return `${prefix.trimEnd()}…`;
}

/** JSON escaping and UTF-8 size both count; prefixes contain whole code points. */
function clippedJSONBytes(value: string, maximumBytes: number): string {
  if (jsonBytes(value) <= maximumBytes) return value;
  if (jsonBytes("…") > maximumBytes) return "";
  const symbols = Array.from(value);
  let lower = 0;
  let upper = symbols.length;
  while (lower < upper) {
    const middle = Math.ceil((lower + upper) / 2);
    if (jsonBytes(symbols.slice(0, middle).join("") + "…") <= maximumBytes) {
      lower = middle;
    } else upper = middle - 1;
  }
  return symbols.slice(0, lower).join("") + "…";
}
