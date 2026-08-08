drop policy if exists licenses_no_client_access on public.licenses;
create policy licenses_no_client_access
on public.licenses
for all
to anon, authenticated
using (false)
with check (false);

drop policy if exists license_devices_no_client_access on public.license_devices;
create policy license_devices_no_client_access
on public.license_devices
for all
to anon, authenticated
using (false)
with check (false);

drop policy if exists license_rate_limits_no_client_access on public.license_rate_limits;
create policy license_rate_limits_no_client_access
on public.license_rate_limits
for all
to anon, authenticated
using (false)
with check (false);

drop policy if exists license_settings_no_client_access on public.license_settings;
create policy license_settings_no_client_access
on public.license_settings
for all
to anon, authenticated
using (false)
with check (false);

drop policy if exists license_private_secrets_no_client_access on public.license_private_secrets;
create policy license_private_secrets_no_client_access
on public.license_private_secrets
for all
to anon, authenticated
using (false)
with check (false);
