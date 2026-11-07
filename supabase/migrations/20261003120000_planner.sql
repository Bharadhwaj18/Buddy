-- v1 Tasks: Microsoft Planner-style plans, buckets and richer tasks (DESIGN.md §4.3).

create table public.plans (
  id                uuid primary key,
  user_id           uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name              text not null,
  color             text,
  position          double precision not null default 0,
  archived          boolean not null default false,
  created_at        timestamptz not null,
  updated_at        timestamptz not null,
  server_updated_at timestamptz not null default clock_timestamp(),
  deleted_at        timestamptz
);

create table public.buckets (
  id                uuid primary key,
  user_id           uuid not null default auth.uid() references auth.users (id) on delete cascade,
  plan_id           uuid not null,
  name              text not null,
  position          double precision not null default 0,
  created_at        timestamptz not null,
  updated_at        timestamptz not null,
  server_updated_at timestamptz not null default clock_timestamp(),
  deleted_at        timestamptz
);

-- Tasks gain Planner fields. Progress: todo (not started) | in_progress | done.
alter table public.tasks drop constraint if exists tasks_status_check;
alter table public.tasks
  add constraint tasks_status_check check (status in ('todo', 'in_progress', 'done')),
  add column plan_id      uuid,
  add column bucket_id    uuid,
  add column priority     text not null default 'normal' check (priority in ('low', 'normal', 'high', 'urgent')),
  add column due_at       timestamptz,
  add column my_day       date,
  add column position     double precision not null default 0,
  add column completed_at timestamptz,
  add column checklist    jsonb not null default '[]'::jsonb;

create index plans_user_server_updated_idx on public.plans (user_id, server_updated_at);
create index buckets_user_server_updated_idx on public.buckets (user_id, server_updated_at);

create trigger plans_sync_stamp before insert or update on public.plans
  for each row execute function public.sync_stamp();
create trigger buckets_sync_stamp before insert or update on public.buckets
  for each row execute function public.sync_stamp();

alter table public.plans enable row level security;
alter table public.buckets enable row level security;

create policy "plans: select own" on public.plans for select to authenticated using (user_id = auth.uid());
create policy "plans: insert own" on public.plans for insert to authenticated with check (user_id = auth.uid());
create policy "plans: update own" on public.plans for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy "buckets: select own" on public.buckets for select to authenticated using (user_id = auth.uid());
create policy "buckets: insert own" on public.buckets for insert to authenticated with check (user_id = auth.uid());
create policy "buckets: update own" on public.buckets for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

revoke all on public.plans, public.buckets from anon;
grant select, insert, update on public.plans, public.buckets to authenticated;
