-- Buddy MCP (AI connectors such as Claude) is READ ONLY, enforced by the database itself.
--
-- An AI connector signs in through Supabase Auth's OAuth server, so its token carries a `client_id`
-- claim. The phone and laptop apps sign in directly (no client_id). These RESTRICTIVE policies are
-- added on top of the existing "own rows" policies: any token with a client_id can't insert, update
-- or delete anything, can't read one-time codes, and can't touch stored files.

do $$
declare
  t text;
begin
  foreach t in array array['accounts', 'buckets', 'devices', 'items', 'plans', 'reminder_acks', 'tasks', 'transactions', 'otp_relay'] loop
    execute format('drop policy if exists "%1$s: no writes from AI connectors" on public.%1$I', t);
    execute format(
      'create policy "%1$s: no writes from AI connectors" on public.%1$I as restrictive for insert to authenticated
         with check ((auth.jwt() ->> ''client_id'') is null)', t);
    execute format('drop policy if exists "%1$s: no updates from AI connectors" on public.%1$I', t);
    execute format(
      'create policy "%1$s: no updates from AI connectors" on public.%1$I as restrictive for update to authenticated
         using ((auth.jwt() ->> ''client_id'') is null) with check ((auth.jwt() ->> ''client_id'') is null)', t);
    execute format('drop policy if exists "%1$s: no deletes from AI connectors" on public.%1$I', t);
    execute format(
      'create policy "%1$s: no deletes from AI connectors" on public.%1$I as restrictive for delete to authenticated
         using ((auth.jwt() ->> ''client_id'') is null)', t);
  end loop;
end $$;

-- One-time codes from the phone are never shown to an AI connector.
drop policy if exists "otp_relay: not for AI connectors" on public.otp_relay;
create policy "otp_relay: not for AI connectors" on public.otp_relay as restrictive for select to authenticated
  using ((auth.jwt() ->> 'client_id') is null);

-- Stored files (documents, hums) stay out of reach of AI connectors too.
drop policy if exists "storage: not for AI connectors" on storage.objects;
create policy "storage: not for AI connectors" on storage.objects as restrictive for all to authenticated
  using ((auth.jwt() ->> 'client_id') is null) with check ((auth.jwt() ->> 'client_id') is null);
