create table if not exists public.license_private_secrets (
    name text primary key,
    secret_value text not null,
    created_at timestamptz not null default now(),
    rotated_at timestamptz
);

alter table public.license_private_secrets enable row level security;
revoke all on public.license_private_secrets from public, anon, authenticated;

insert into public.license_private_secrets(name, secret_value)
values ('license_signing_secret', encode(gen_random_bytes(64), 'hex'))
on conflict (name) do nothing;

create or replace function public.get_license_signing_secret()
returns text
language sql
security definer
set search_path = public
as $$
    select secret_value
    from public.license_private_secrets
    where name = 'license_signing_secret';
$$;

revoke all on function public.get_license_signing_secret() from public, anon, authenticated;
grant execute on function public.get_license_signing_secret() to service_role;
