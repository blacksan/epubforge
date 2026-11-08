package com.epubforge.sync

/** Mirrors docs/supabase.sql so the app can copy it to the clipboard. Keep the two in sync. */
object SupabaseSetup {
    val SQL = """-- EpubForge sync table for your own Supabase project.
-- Run once in the Supabase dashboard: SQL Editor -> New query -> paste -> Run.
-- Each signed-in user can only see and change their own row (row-level security).

create table if not exists public.epubforge_sync (
  user_id    uuid primary key default auth.uid() references auth.users (id) on delete cascade,
  data       jsonb       not null,
  version    bigint      not null default 1,
  updated_at timestamptz not null default now()
);

alter table public.epubforge_sync enable row level security;

drop policy if exists "Own sync row" on public.epubforge_sync;
create policy "Own sync row" on public.epubforge_sync
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.epubforge_sync to authenticated;

create or replace function public.epubforge_touch() returns trigger
language plpgsql set search_path = '' as ${"$"}${"$"}
begin
  new.updated_at := now();
  return new;
end ${"$"}${"$"};

drop trigger if exists epubforge_touch on public.epubforge_sync;
create trigger epubforge_touch before update on public.epubforge_sync
  for each row execute function public.epubforge_touch();
"""
}
