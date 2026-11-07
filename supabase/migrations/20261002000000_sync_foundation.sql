-- M0 spike (d): sync foundation.
-- Every synced table follows the contract in docs/DESIGN.md §6:
--   id uuid (client-generated), user_id, created_at, updated_at (client clock),
--   server_updated_at (set here, used as the pull cursor), deleted_at (soft delete).

-- Shared trigger: stamps server_updated_at and enforces last-write-wins.
-- A stale update (older client updated_at than the stored row) is silently skipped,
-- so an upsert from an out-of-date device can never clobber newer data.
create or replace function public.sync_stamp()
returns trigger
language plpgsql
as $$
begin
  if tg_op = 'UPDATE' and new.updated_at < old.updated_at then
    return null;
  end if;
  new.server_updated_at := clock_timestamp();
  return new;
end;
$$;

-- Tasks: minimal columns for the spike; later migrations add plans, buckets, due dates, etc.
create table public.tasks (
  id                uuid primary key,
  user_id           uuid not null default auth.uid() references auth.users (id) on delete cascade,
  title             text not null,
  notes             text,
  status            text not null default 'todo' check (status in ('todo', 'done')),
  created_at        timestamptz not null,
  updated_at        timestamptz not null,
  server_updated_at timestamptz not null default clock_timestamp(),
  deleted_at        timestamptz
);

create index tasks_user_server_updated_idx on public.tasks (user_id, server_updated_at);

create trigger tasks_sync_stamp
  before insert or update on public.tasks
  for each row execute function public.sync_stamp();

-- Single-user RLS: you only ever see and write your own rows. No hard deletes.
alter table public.tasks enable row level security;

create policy "tasks: select own" on public.tasks
  for select to authenticated using (user_id = auth.uid());
create policy "tasks: insert own" on public.tasks
  for insert to authenticated with check (user_id = auth.uid());
create policy "tasks: update own" on public.tasks
  for update to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

revoke all on public.tasks from anon;
grant select, insert, update on public.tasks to authenticated;
