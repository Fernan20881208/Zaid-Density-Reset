begin;

alter table public.app_config
  add column if not exists sensi_very_high_enabled boolean not null default true,
  add column if not exists sensi_medium_high_enabled boolean not null default true,
  add column if not exists sensi_very_high_density integer not null default 46,
  add column if not exists sensi_medium_high_density integer not null default 176;

alter table public.app_config
  drop constraint if exists app_config_sensi_very_high_density_check,
  drop constraint if exists app_config_sensi_medium_high_density_check;

alter table public.app_config
  add constraint app_config_sensi_very_high_density_check
    check (sensi_very_high_density between 20 and 1000),
  add constraint app_config_sensi_medium_high_density_check
    check (sensi_medium_high_density between 20 and 1000);

update public.app_config
set
  sensi_very_high_density = case
    when sensi_very_high_density between 20 and 1000 then sensi_very_high_density
    else 46
  end,
  sensi_medium_high_density = case
    when sensi_medium_high_density between 20 and 1000 then sensi_medium_high_density
    else 176
  end
where singleton = true;

commit;
