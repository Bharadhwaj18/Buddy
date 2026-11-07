-- Tasks: due time, reminders and repeats.
alter table public.tasks
  add column if not exists due_all_day boolean not null default true,
  add column if not exists remind_at   timestamptz,
  add column if not exists repeat      text check (repeat in ('daily', 'weekdays', 'weekly', 'monthly', 'yearly'));
