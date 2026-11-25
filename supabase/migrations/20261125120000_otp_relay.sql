-- OTPs on the laptop.
--
-- When a one-time code SMS arrives and the laptop app is on, the phone puts the code here; the laptop shows it
-- (with Copy) and deletes the row. Rows only live for minutes: every insert also clears that user's rows older
-- than 10 minutes, so nothing piles up even if the laptop never picks one up. Not part of the outbox/cursor sync.

create table public.otp_relay (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null default auth.uid() references auth.users (id) on delete cascade,
  code       text not null check (char_length(code) between 3 and 10),
  source     text check (char_length(source) <= 40),
  created_at timestamptz not null default clock_timestamp()
);

create index otp_relay_user_idx on public.otp_relay (user_id, created_at desc);

create or replace function public.otp_relay_tidy()
returns trigger
language plpgsql
as $$
begin
  new.created_at := clock_timestamp();
  delete from public.otp_relay where user_id = new.user_id and created_at < clock_timestamp() - interval '10 minutes';
  return new;
end;
$$;

create trigger otp_relay_tidy before insert on public.otp_relay
  for each row execute function public.otp_relay_tidy();

alter table public.otp_relay enable row level security;

create policy "otp_relay: select own" on public.otp_relay for select to authenticated using (user_id = auth.uid());
create policy "otp_relay: insert own" on public.otp_relay for insert to authenticated with check (user_id = auth.uid());
create policy "otp_relay: delete own" on public.otp_relay for delete to authenticated using (user_id = auth.uid());

revoke all on public.otp_relay from anon;
grant select, insert, delete on public.otp_relay to authenticated;

-- The laptop listens for new codes instead of asking every few seconds.
alter publication supabase_realtime add table public.otp_relay;
