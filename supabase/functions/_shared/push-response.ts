/** Request/configuration failures must not permanently unregister a valid device. */
export async function isInvalidDeviceToken(platform: string, response: Response): Promise<boolean> {
  const payload = await response.clone().json().catch(() => null);
  if (platform === "android") {
    return payload?.error?.details?.some((detail: Record<string, unknown>) =>
      detail["@type"] === "type.googleapis.com/google.firebase.fcm.v1.FcmError" && detail.errorCode === "UNREGISTERED"
    ) === true;
  }
  return response.status === 410 || payload?.reason === "BadDeviceToken" || payload?.reason === "Unregistered";
}
