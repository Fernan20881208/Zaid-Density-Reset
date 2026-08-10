begin;

create table if not exists public.app_config (
  singleton boolean primary key default true check (singleton),
  maintenance_mode boolean not null default false,
  maintenance_message text,
  min_supported_version_code bigint not null default 12 check (min_supported_version_code > 0),
  latest_version_code bigint check (latest_version_code is null or latest_version_code > 0),
  force_update boolean not null default false,
  free_fire_enabled boolean not null default true,
  free_fire_max_enabled boolean not null default true,
  sensi_ultra_enabled boolean not null default true,
  sensi_high_enabled boolean not null default true,
  sensi_low_enabled boolean not null default true,
  sensi_ultra_density integer not null default 20 check (sensi_ultra_density between 20 and 1000),
  sensi_high_density integer not null default 72 check (sensi_high_density between 20 and 1000),
  sensi_low_density integer not null default 280 check (sensi_low_density between 20 and 1000),
  game_session_duration_seconds integer not null default 30 check (game_session_duration_seconds between 5 and 300),
  announcement_enabled boolean not null default false,
  announcement_title text,
  announcement_message text,
  quick_tile_enabled boolean not null default true,
  github_updates_enabled boolean not null default true,
  blocked_version_codes bigint[] not null default '{}'::bigint[],
  updated_at timestamptz not null default now()
);

insert into public.app_config (
  singleton,
  min_supported_version_code,
  latest_version_code
) values (
  true,
  12,
  12
)
on conflict (singleton) do nothing;

create or replace function public.touch_app_config_updated_at()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists app_config_touch_updated_at on public.app_config;
create trigger app_config_touch_updated_at
before update on public.app_config
for each row execute function public.touch_app_config_updated_at();

alter table public.app_config enable row level security;
revoke all on public.app_config from anon, authenticated;
grant select, update on public.app_config to service_role;

commit;
