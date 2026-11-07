-- Manually set account balance (cash, wallets, or correcting a bank balance). The app shows the newest of
-- this anchor and the bank's last SMS-reported balance, plus every transaction after it.
alter table public.accounts
  add column if not exists manual_balance_minor bigint,
  add column if not exists manual_balance_at    timestamptz;
