import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

// Test import map replaces only client.ts; production source is unchanged.
let client: SupabaseClient;
export function setClient(value: unknown) {
  client = value as SupabaseClient;
}
export function adminClient() {
  return client;
}
export function providerSecretKey() {
  return "fixture-provider-secret-key";
}
