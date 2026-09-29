import {
  deliveryDeepLink,
  isRejectedDeviceToken,
  isTransientAPNSStatus,
  type LockScreenDetail,
  notificationCopy,
  type NotificationEvent,
  quietSummaryDispatchIdentity,
} from "./notification-delivery.ts";
import { fcmData, type PushResult } from "./fcm-delivery.ts";

export const PUSH_PLATFORMS = ["ios", "macos", "android"];

interface PushDevice {
  id: string;
  platform: string;
  environment: string;
}

export interface DispatchEvent extends NotificationEvent {
  quiet_delivery_key?: string | null;
}

interface PushTransports {
  apns: (
    token: string,
    environment: string,
    payload: unknown,
    apnsID: string,
    collapseID: string,
  ) => Promise<Response>;
  fcm: (token: string, data: Record<string, string>) => Promise<PushResult>;
}

/** Reuses v8's durable row identity and quiet-summary identity on both transports. */
export async function sendDevicePush(
  userID: string,
  device: PushDevice,
  token: string,
  events: DispatchEvent[],
  detail: LockScreenDetail,
  rowDispatchID: string,
  transports: PushTransports,
): Promise<PushResult> {
  let dispatchID = rowDispatchID;
  let collapseID = `gradey-mark-${events[0].id}`;
  if (events.length > 1) {
    const quietKey = events[0].quiet_delivery_key;
    if (
      !quietKey || events.some((event) => event.quiet_delivery_key !== quietKey)
    ) {
      throw new Error("invalid_quiet_summary_identity");
    }
    const identity = await quietSummaryDispatchIdentity(quietKey, device.id);
    dispatchID = identity.apnsID;
    collapseID = identity.collapseID;
  }

  if (device.platform === "android") {
    return await transports.fcm(
      token,
      fcmData(userID, events, detail, dispatchID),
    );
  }
  if (device.platform !== "ios" && device.platform !== "macos") {
    return {
      accepted: false,
      invalidateToken: false,
      retry: false,
      reason: "unsupported_platform",
    };
  }
  const response = await transports.apns(
    token,
    device.environment,
    {
      aps: { alert: notificationCopy(events, detail), sound: "default" },
      url: deliveryDeepLink(events),
      eventID: events.length === 1 ? events[0].id : undefined,
      summaryID: events.length > 1 ? events[0].id : undefined,
      summaryCount: events.length > 1 ? events.length : undefined,
    },
    dispatchID,
    collapseID,
  );
  const body = await response.json().catch(() => null);
  // Apple defines a small reason code, never a user-visible provider message.
  const reason =
    typeof body?.reason === "string" && /^[A-Za-z]{1,64}$/.test(body.reason)
      ? body.reason
      : null;
  return {
    accepted: response.ok,
    invalidateToken: !response.ok &&
      isRejectedDeviceToken(response.status, reason),
    retry: !response.ok && isTransientAPNSStatus(response.status),
    reason: reason ?? `apns_status_${response.status}`,
  };
}
