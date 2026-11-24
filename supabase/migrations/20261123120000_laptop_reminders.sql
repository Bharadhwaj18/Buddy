-- Laptop app: "laptop first, phone 30 seconds later".
--
-- devices: each laptop says "I'm on" about once a minute. When a reminder is due and a laptop was seen
-- in the last couple of minutes, the phone waits 30 s before ringing.
--
-- reminder_acks: what was done about one ring of a task's reminder (task id + its remind_at), on any
-- device. The phone checks it after the 30 s: answered on the laptop -> stay quiet; otherwise ring.
-- The laptop reads it too, so answering on the phone closes the laptop's pop-up.
-- Neither table uses the outbox/cursor sync contract: they are read on demand and only matter for minutes.

create table public.devices (
  id        uuid primary key,
  user_id   uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name      text,
  kind      text not null check (kind in ('laptop', 'phone')),
  last_seen timestamptz not null default clock_timestamp()
);

create index devices_user_kind_idx on public.devices (user_id, kind, last_seen desc);

create table public.reminder_acks (
  task_id      uuid not null,
  remind_at    timestamptz not null,
  user_id      uuid not null default auth.uid() references auth.users (id) on delete cascade,
  action       text not null check (action in ('done', 'dismissed', 'snoozed', 'moved')),
  snooze_until timestamptz,
  device       text,
  -- Set by the server (trigger below), so devices with slightly different clocks compare fairly.
  acked_at     timestamptz not null default clock_timestamp(),
  primary key (task_id, remind_at)
);

create index reminder_acks_user_idx on public.reminder_acks (user_id, acked_at desc);

create or replace function public.stamp_now()
returns trigger
language plpgsql
as $$
begin
  if tg_table_name = 'devices' then
    new.last_seen := clock_timestamp();
  else
    new.acked_at := clock_timestamp();
  end if;
  return new;
end;
$$;

create trigger devices_stamp before insert or update on public.devices
  for each row execute function public.stamp_now();
create trigger reminder_acks_stamp before insert or update on public.reminder_acks
  for each row execute function public.stamp_now();

alter table public.devices enable row level security;
alter table public.reminder_acks enable row level security;

create policy "devices: select own" on public.devices for select to authenticated using (user_id = auth.uid());
create policy "devices: insert own" on public.devices for insert to authenticated with check (user_id = auth.uid());
create policy "devices: update own" on public.devices for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy "reminder_acks: select own" on public.reminder_acks for select to authenticated using (user_id = auth.uid());
create policy "reminder_acks: insert own" on public.reminder_acks for insert to authenticated with check (user_id = auth.uid());
create policy "reminder_acks: update own" on public.reminder_acks for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

revoke all on public.devices, public.reminder_acks from anon;
grant select, insert, update on public.devices, public.reminder_acks to authenticated;
