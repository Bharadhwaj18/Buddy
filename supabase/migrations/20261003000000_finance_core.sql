-- v1 finance core: accounts + transactions, following the sync contract in DESIGN.md §6.
-- IDs are deterministic on the device (derived from bank/last4 and bank/reference), so a reinstall
-- or re-scan produces the same IDs and upserts instead of duplicating.

create table public.accounts (
  id                uuid primary key,
  user_id           uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name              text not null,
  bank              text,
  kind              text not null default 'savings'
                    check (kind in ('savings', 'current', 'credit_card', 'wallet', 'cash', 'loan', 'other')),
  last4             text,
  is_primary        boolean not null default false,
  include_in_totals boolean not null default true,
  archived          boolean not null default false,
  created_at        timestamptz not null,
  updated_at        timestamptz not null,
  server_updated_at timestamptz not null default clock_timestamp(),
  deleted_at        timestamptz
);

create table public.transactions (
  id                  uuid primary key,
  user_id             uuid not null default auth.uid() references auth.users (id) on delete cascade,
  -- No FK: the device guarantees integrity, and a strict FK could wedge the sync queue on ordering.
  account_id          uuid,
  direction           text not null check (direction in ('debit', 'credit')),
  amount_minor        bigint not null check (amount_minor >= 0),
  currency            text not null default 'INR',
  occurred_at         timestamptz not null,
  payee               text,
  counterparty_raw    text,
  channel             text,
  reference           text,
  category            text not null default 'other',
  category_source     text not null default 'auto' check (category_source in ('auto', 'user')),
  note                text,
  is_transfer         boolean not null default false,
  transfer_group      uuid,
  source              text not null check (source in ('sms', 'manual', 'voice', 'import')),
  dedupe_key          text not null,
  balance_after_minor bigint,
  created_at          timestamptz not null,
  updated_at          timestamptz not null,
  server_updated_at   timestamptz not null default clock_timestamp(),
  deleted_at          timestamptz
);

create index accounts_user_server_updated_idx on public.accounts (user_id, server_updated_at);
create index transactions_user_server_updated_idx on public.transactions (user_id, server_updated_at);
create index transactions_user_occurred_idx on public.transactions (user_id, occurred_at desc);

create trigger accounts_sync_stamp before insert or update on public.accounts
  for each row execute function public.sync_stamp();
create trigger transactions_sync_stamp before insert or update on public.transactions
  for each row execute function public.sync_stamp();

alter table public.accounts enable row level security;
alter table public.transactions enable row level security;

create policy "accounts: select own" on public.accounts for select to authenticated using (user_id = auth.uid());
create policy "accounts: insert own" on public.accounts for insert to authenticated with check (user_id = auth.uid());
create policy "accounts: update own" on public.accounts for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy "transactions: select own" on public.transactions for select to authenticated using (user_id = auth.uid());
create policy "transactions: insert own" on public.transactions for insert to authenticated with check (user_id = auth.uid());
create policy "transactions: update own" on public.transactions for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

revoke all on public.accounts, public.transactions from anon;
grant select, insert, update on public.accounts, public.transactions to authenticated;
