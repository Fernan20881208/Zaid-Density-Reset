alter table public.app_config
  add column if not exists game_booster_enabled boolean not null default true,
  add column if not exists game_mode_enabled boolean not null default true,
  add column if not exists battery_mode_enabled boolean not null default true,
  add column if not exists max_performance_enabled boolean not null default true,
  add column if not exists ram_monitor_enabled boolean not null default true,
  add column if not exists battery_monitor_enabled boolean not null default true,
  add column if not exists thermal_monitor_enabled boolean not null default true,
  add column if not exists fps_monitor_enabled boolean not null default true,
  add column if not exists xiaomi_adapter_enabled boolean not null default true,
  add column if not exists samsung_adapter_enabled boolean not null default true,
  add column if not exists oplus_adapter_enabled boolean not null default true,
  add column if not exists aosp_adapter_enabled boolean not null default true;
