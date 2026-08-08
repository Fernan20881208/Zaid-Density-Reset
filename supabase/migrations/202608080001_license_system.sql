create extension if not exists pgcrypto;

do $$ begin
    create type public.license_status as enum (
        'unused', 'active', 'expired', 'revoked', 'disabled'
    );
exception when duplicate_object then null;
end $$;

do $$ begin
    create type public.license_duration_start_mode as enum (
        'first_activation', 'generation'
    );
exception when duplicate_object then null;
end $$;

create table if not exists public.profiles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    role text not null check (role in ('admin')),
    created_at timestamptz not null default now()
);

create table if not exists public.licenses (
    id uuid primary key default gen_random_uuid(),
    key_hash text not null unique check (key_hash ~ '^[0-9a-f]{64}$'),
    key_prefix text not null,
    key_suffix text not null,
    status public.license_status not null default 'unused',
    created_at timestamptz not null default now(),
    activated_at timestamptz,
    expires_at timestamptz,
    duration_days integer check (duration_days is null or duration_days > 0),
    duration_start_mode public.license_duration_start_mode not null default 'first_activation',
    max_devices integer not null default 1 check (max_devices between 1 and 100),
    device_hash text check (device_hash is null or device_hash ~ '^[0-9a-f]{64}$'),
    last_validation timestamptz,
    last_activation_ip inet,
    label text,
    notes text,
    revoked_at timestamptz,
    disabled_at timestamptz,
    created_by uuid references auth.users(id) on delete set null
);

create index if not exists licenses_status_idx on public.licenses(status);
create index if not exists licenses_created_at_idx on public.licenses(created_at desc);
create index if not exists licenses_key_prefix_idx on public.licenses(key_prefix);
create index if not exists licenses_label_idx on public.licenses using gin(to_tsvector('simple', coalesce(label, '')));

create table if not exists public.license_devices (
    id uuid primary key default gen_random_uuid(),
    license_id uuid not null references public.licenses(id) on delete cascade,
    device_hash text not null check (device_hash ~ '^[0-9a-f]{64}$'),
    activated_at timestamptz not null default now(),
    last_validation timestamptz,
    unique (license_id, device_hash)
);

create index if not exists license_devices_license_idx on public.license_devices(license_id);
create index if not exists license_devices_hash_idx on public.license_devices(device_hash);

create table if not exists public.license_rate_limits (
    fingerprint text not null,
    bucket_start timestamptz not null,
    attempts integer not null default 1,
    primary key (fingerprint, bucket_start)
);

create table if not exists public.license_settings (
    singleton boolean primary key default true check (singleton),
    minimum_app_version_code integer not null default 1 check (minimum_app_version_code > 0),
    offline_grace_hours integer not null default 12 check (offline_grace_hours between 0 and 168),
    token_lifetime_hours integer not null default 168 check (token_lifetime_hours between 1 and 8760),
    updated_at timestamptz not null default now()
);

insert into public.license_settings(singleton)
values (true)
on conflict (singleton) do nothing;

alter table public.profiles enable row level security;
alter table public.licenses enable row level security;
alter table public.license_devices enable row level security;
alter table public.license_rate_limits enable row level security;
alter table public.license_settings enable row level security;

drop policy if exists profiles_read_self on public.profiles;
create policy profiles_read_self
on public.profiles
for select
to authenticated
using (auth.uid() = user_id);

revoke all on public.licenses from anon, authenticated;
revoke all on public.license_devices from anon, authenticated;
revoke all on public.license_rate_limits from anon, authenticated;
revoke all on public.license_settings from anon, authenticated;

create or replace function public.consume_license_rate_limit(
    p_fingerprint text,
    p_limit integer default 5
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    v_bucket timestamptz := date_trunc('minute', now());
    v_attempts integer;
begin
    delete from public.license_rate_limits
    where bucket_start < now() - interval '10 minutes';

    insert into public.license_rate_limits(fingerprint, bucket_start, attempts)
    values (p_fingerprint, v_bucket, 1)
    on conflict (fingerprint, bucket_start)
    do update set attempts = public.license_rate_limits.attempts + 1
    returning attempts into v_attempts;

    return v_attempts <= p_limit;
end;
$$;

create or replace function public.activate_license(
    p_key_hash text,
    p_device_hash text,
    p_ip inet
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_license public.licenses%rowtype;
    v_device_count integer;
    v_device_exists boolean;
    v_now timestamptz := now();
    v_expires timestamptz;
begin
    select * into v_license
    from public.licenses
    where key_hash = p_key_hash
    for update;

    if not found then
        return jsonb_build_object('success', false, 'code', 'INVALID_KEY');
    end if;

    if v_license.status = 'revoked' then
        return jsonb_build_object('success', false, 'code', 'LICENSE_REVOKED');
    end if;
    if v_license.status = 'disabled' then
        return jsonb_build_object('success', false, 'code', 'LICENSE_DISABLED');
    end if;
    if v_license.status = 'expired' then
        return jsonb_build_object('success', false, 'code', 'LICENSE_EXPIRED', 'expires_at', v_license.expires_at);
    end if;
    if v_license.expires_at is not null and v_license.expires_at <= v_now then
        update public.licenses
        set status = 'expired'
        where id = v_license.id;
        return jsonb_build_object('success', false, 'code', 'LICENSE_EXPIRED', 'expires_at', v_license.expires_at);
    end if;

    select exists(
        select 1 from public.license_devices
        where license_id = v_license.id and device_hash = p_device_hash
    ) into v_device_exists;

    if not v_device_exists then
        select count(*) into v_device_count
        from public.license_devices
        where license_id = v_license.id;

        if v_device_count >= v_license.max_devices then
            return jsonb_build_object(
                'success', false,
                'code', case when v_license.max_devices = 1 then 'DEVICE_MISMATCH' else 'DEVICE_LIMIT' end
            );
        end if;

        insert into public.license_devices(
            license_id, device_hash, activated_at, last_validation
        ) values (
            v_license.id, p_device_hash, v_now, v_now
        );
    else
        update public.license_devices
        set last_validation = v_now
        where license_id = v_license.id and device_hash = p_device_hash;
    end if;

    v_expires := v_license.expires_at;
    if v_license.status = 'unused' then
        if v_license.duration_start_mode = 'first_activation'
           and v_license.duration_days is not null then
            v_expires := v_now + make_interval(days => v_license.duration_days);
        end if;

        update public.licenses
        set status = 'active',
            activated_at = coalesce(activated_at, v_now),
            expires_at = v_expires,
            device_hash = coalesce(device_hash, p_device_hash),
            last_validation = v_now,
            last_activation_ip = p_ip
        where id = v_license.id;
    else
        update public.licenses
        set device_hash = coalesce(device_hash, p_device_hash),
            last_validation = v_now,
            last_activation_ip = p_ip
        where id = v_license.id;
    end if;

    return jsonb_build_object(
        'success', true,
        'license_id', v_license.id,
        'status', 'active',
        'expires_at', v_expires
    );
end;
$$;

create or replace function public.validate_license_session(
    p_license_id uuid,
    p_device_hash text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_license public.licenses%rowtype;
    v_now timestamptz := now();
begin
    select * into v_license
    from public.licenses
    where id = p_license_id
    for update;

    if not found then
        return jsonb_build_object('success', false, 'code', 'INVALID_SESSION');
    end if;

    if v_license.status = 'revoked' then
        return jsonb_build_object('success', false, 'code', 'LICENSE_REVOKED');
    end if;
    if v_license.status = 'disabled' then
        return jsonb_build_object('success', false, 'code', 'LICENSE_DISABLED');
    end if;
    if v_license.status = 'unused' then
        return jsonb_build_object('success', false, 'code', 'INVALID_SESSION');
    end if;
    if v_license.status = 'expired' then
        return jsonb_build_object('success', false, 'code', 'LICENSE_EXPIRED', 'expires_at', v_license.expires_at);
    end if;
    if v_license.expires_at is not null and v_license.expires_at <= v_now then
        update public.licenses set status = 'expired' where id = v_license.id;
        return jsonb_build_object('success', false, 'code', 'LICENSE_EXPIRED', 'expires_at', v_license.expires_at);
    end if;

    if not exists(
        select 1 from public.license_devices
        where license_id = v_license.id and device_hash = p_device_hash
    ) then
        return jsonb_build_object('success', false, 'code', 'DEVICE_MISMATCH');
    end if;

    update public.license_devices
    set last_validation = v_now
    where license_id = v_license.id and device_hash = p_device_hash;

    update public.licenses
    set last_validation = v_now
    where id = v_license.id;

    return jsonb_build_object(
        'success', true,
        'license_id', v_license.id,
        'status', 'active',
        'expires_at', v_license.expires_at
    );
end;
$$;

revoke all on function public.consume_license_rate_limit(text, integer) from public, anon, authenticated;
revoke all on function public.activate_license(text, text, inet) from public, anon, authenticated;
revoke all on function public.validate_license_session(uuid, text) from public, anon, authenticated;

grant execute on function public.consume_license_rate_limit(text, integer) to service_role;
grant execute on function public.activate_license(text, text, inet) to service_role;
grant execute on function public.validate_license_session(uuid, text) to service_role;
