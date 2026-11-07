-- Repeats are now iCalendar RRULE strings (e.g. FREQ=WEEKLY;BYDAY=MO,WE,FR), not just five fixed words.
alter table public.tasks drop constraint if exists tasks_repeat_check;
alter table public.tasks add constraint tasks_repeat_check check (
  repeat is null
  or repeat in ('daily', 'weekdays', 'weekly', 'monthly', 'yearly')
  or repeat ~ '^FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)(;[A-Z]+=[A-Z0-9,-]+)*$'
);
