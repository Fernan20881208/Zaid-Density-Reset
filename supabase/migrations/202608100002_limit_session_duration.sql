begin;

update public.app_config
set game_session_duration_seconds = least(game_session_duration_seconds, 150)
where singleton = true;

alter table public.app_config
  drop constraint if exists app_config_game_session_duration_seconds_check;

alter table public.app_config
  add constraint app_config_game_session_duration_seconds_check
  check (game_session_duration_seconds between 5 and 150);

commit;
