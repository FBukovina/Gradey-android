-- Add Android without changing the existing Apple platforms or authorization policies.
alter table public.device_push_tokens
  drop constraint device_push_tokens_platform_check,
  add constraint device_push_tokens_platform_check
    check (platform in ('ios', 'macos', 'android'));
