begin;

do $$
declare
    v_hash text := encode(digest('DR-AAAA-BBBB-CCCC-DDDD', 'sha256'), 'hex');
    v_id uuid;
    v_result jsonb;
    v_expiry timestamptz;
begin
    insert into public.licenses(
        key_hash, key_prefix, key_suffix, duration_days,
        duration_start_mode, max_devices
    ) values (
        v_hash, 'DR-AAAA', 'DDDD', 1, 'first_activation', 1
    ) returning id into v_id;

    select public.activate_license(v_hash, repeat('a', 64), null) into v_result;
    if not (v_result->>'success')::boolean then
        raise exception 'new key activation failed: %', v_result;
    end if;

    select expires_at into v_expiry from public.licenses where id = v_id;
    if v_expiry is null or v_expiry < now() + interval '23 hours' then
        raise exception '1-day first activation expiry was not calculated';
    end if;

    select public.activate_license(v_hash, repeat('b', 64), null) into v_result;
    if v_result->>'code' <> 'DEVICE_MISMATCH' then
        raise exception 'device mismatch was not enforced: %', v_result;
    end if;

    delete from public.license_devices where license_id = v_id;
    update public.licenses set device_hash = null where id = v_id;
    select public.activate_license(v_hash, repeat('b', 64), null) into v_result;
    if not (v_result->>'success')::boolean then
        raise exception 'device reset did not permit a new device: %', v_result;
    end if;

    update public.licenses set status = 'disabled', disabled_at = now() where id = v_id;
    select public.validate_license_session(v_id, repeat('b', 64)) into v_result;
    if v_result->>'code' <> 'LICENSE_DISABLED' then
        raise exception 'disabled license validation failed: %', v_result;
    end if;

    update public.licenses set status = 'active', disabled_at = null where id = v_id;
    select public.validate_license_session(v_id, repeat('b', 64)) into v_result;
    if not (v_result->>'success')::boolean then
        raise exception 'reactivation failed: %', v_result;
    end if;

    update public.licenses set status = 'revoked', revoked_at = now() where id = v_id;
    select public.validate_license_session(v_id, repeat('b', 64)) into v_result;
    if v_result->>'code' <> 'LICENSE_REVOKED' then
        raise exception 'revoked license validation failed: %', v_result;
    end if;
end $$;

do $$
declare
    v_permanent text := encode(digest('DR-EEEE-FFFF-GGGG-HHHH', 'sha256'), 'hex');
    v_30day text := encode(digest('DR-JJJJ-KKKK-LLLL-MMMM', 'sha256'), 'hex');
    v_expired text := encode(digest('DR-NNNN-PPPP-QQQQ-RRRR', 'sha256'), 'hex');
    v_result jsonb;
begin
    insert into public.licenses(key_hash, key_prefix, key_suffix, duration_days, duration_start_mode)
    values (v_permanent, 'DR-EEEE', 'HHHH', null, 'first_activation');
    select public.activate_license(v_permanent, repeat('c', 64), null) into v_result;
    if not (v_result->>'success')::boolean or v_result->>'expires_at' is not null then
        raise exception 'permanent license behavior failed: %', v_result;
    end if;

    insert into public.licenses(key_hash, key_prefix, key_suffix, duration_days, duration_start_mode)
    values (v_30day, 'DR-JJJJ', 'MMMM', 30, 'first_activation');
    select public.activate_license(v_30day, repeat('d', 64), null) into v_result;
    if not (v_result->>'success')::boolean then
        raise exception '30-day license activation failed: %', v_result;
    end if;

    insert into public.licenses(
        key_hash, key_prefix, key_suffix, duration_days,
        duration_start_mode, expires_at
    ) values (
        v_expired, 'DR-NNNN', 'RRRR', 1,
        'generation', now() - interval '1 minute'
    );
    select public.activate_license(v_expired, repeat('e', 64), null) into v_result;
    if v_result->>'code' <> 'LICENSE_EXPIRED' then
        raise exception 'generation-time expiration failed: %', v_result;
    end if;

    select public.activate_license(repeat('0', 64), repeat('f', 64), null) into v_result;
    if v_result->>'code' <> 'INVALID_KEY' then
        raise exception 'invalid key response failed: %', v_result;
    end if;
end $$;

do $$
declare
    v_allowed boolean;
    i integer;
    v_fingerprint text := encode(digest('rate-limit-integration-test-' || clock_timestamp()::text, 'sha256'), 'hex');
begin
    for i in 1..5 loop
        select public.consume_license_rate_limit(v_fingerprint, 5) into v_allowed;
        if not v_allowed then raise exception 'rate limit triggered before attempt 6'; end if;
    end loop;
    select public.consume_license_rate_limit(v_fingerprint, 5) into v_allowed;
    if v_allowed then raise exception 'rate limit did not reject attempt 6'; end if;
end $$;

rollback;
