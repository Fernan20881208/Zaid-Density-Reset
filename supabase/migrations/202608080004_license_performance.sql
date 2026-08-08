create index if not exists licenses_created_by_idx
on public.licenses(created_by);

drop policy if exists profiles_read_self on public.profiles;
create policy profiles_read_self
on public.profiles
for select
to authenticated
using ((select auth.uid()) = user_id);
