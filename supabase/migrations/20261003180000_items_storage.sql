-- v1.5/v2 modules share one flexible synced table: habits, habit logs, workouts (incl. runs), meals,
-- journal, notes, goals, people, reading, vehicles + logs, wishlist, documents, holdings, insights.
-- `kind` says what the row is, `data` holds the module's fields, `occurred_on` is its date (if any),
-- `ref_id` links a child to its parent (e.g. habit_log -> habit).

create table public.items (
  id                uuid primary key,
  user_id           uuid not null default auth.uid() references auth.users (id) on delete cascade,
  kind              text not null,
  data              jsonb not null default '{}'::jsonb,
  occurred_on       date,
  ref_id            uuid,
  created_at        timestamptz not null,
  updated_at        timestamptz not null,
  server_updated_at timestamptz not null default clock_timestamp(),
  deleted_at        timestamptz
);

create index items_user_server_updated_idx on public.items (user_id, server_updated_at);
create index items_user_kind_idx on public.items (user_id, kind, occurred_on desc);

create trigger items_sync_stamp before insert or update on public.items
  for each row execute function public.sync_stamp();

alter table public.items enable row level security;
create policy "items: select own" on public.items for select to authenticated using (user_id = auth.uid());
create policy "items: insert own" on public.items for insert to authenticated with check (user_id = auth.uid());
create policy "items: update own" on public.items for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
revoke all on public.items from anon;
grant select, insert, update on public.items to authenticated;

-- Storage: a private document vault (each user only sees their own folder) and a private
-- releases bucket the app reads to update itself (DESIGN.md §2).
insert into storage.buckets (id, name, public)
values ('vault', 'vault', false), ('releases', 'releases', false)
on conflict (id) do nothing;

create policy "vault: own folder" on storage.objects for all to authenticated
  using (bucket_id = 'vault' and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id = 'vault' and (storage.foldername(name))[1] = auth.uid()::text);

create policy "releases: signed-in can read" on storage.objects for select to authenticated
  using (bucket_id = 'releases');
