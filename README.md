# EpubForge

An offline reader for web novels — and your own ebooks — on Android. Add a story from Royal Road or any of the
400+ sites [WebToEpub](https://github.com/dteviot/WebToEpub) supports, download its
chapters, and read them in a built-in reader — with a library, update checks and a
download queue modelled on [Mihon](https://github.com/mihonapp/mihon). Any downloaded
range can be exported as an EPUB.

No accounts, no ads, no trackers, no proprietary libraries. Sync and backups go to
services you choose.

## Features

- **Library like Mihon.** Add a story by link (or share a link to the app). The story page
  lists every chapter with its read and download state; download the next few, a range,
  unread, or everything.
- **Chapter-by-chapter downloads.** A persistent download queue with pause, resume, retry,
  per-story settings (images, author's notes, request delay) and a log.
- **Built-in reader** on [Readest](https://github.com/readest/readest)'s fork of
  [foliate-js](https://github.com/johnfactotum/foliate-js): paginated or scrolled, themes
  (light, sepia, dark, black and more), fonts, spacing, margins, volume-key paging, and it
  remembers where you stopped.
- **Updates.** Checks your library for new chapters in the background, downloads them if
  you like, and notifies you. Unread counts show on each story.
- **Your own ebooks.** Import EPUB, MOBI, AZW3, FB2, CBZ and PDF files (from the library, or
  open them with EpubForge from a file manager) and read them in the same reader, with
  their table of contents, cover and progress tracking.
- **EPUB export.** Export all downloaded chapters or a range as a valid EPUB 3 file.
- **Polite to websites.** Requests are paced per site (Royal Road: 2.5 s apart), `Retry-After`
  is honoured and the app backs off on 429/503. When a site shows a verification page
  (Cloudflare and similar) you complete it once in an in-app browser and downloads resume.
  EpubForge doesn't try to defeat these checks.
- **Backup and sync**, all optional:
  - **WebDAV sync** — Nextcloud, ownCloud, Koofr, pCloud, Box, most NAS boxes, any WebDAV server.
  - **Supabase sync** — your own free Supabase project; near-instant sync with an account.
  - **Backup files** — create and restore a zip by hand, or let the app write one daily or
    weekly to a folder you pick (put it in a Syncthing or Nextcloud folder to keep copies
    off the phone).
  - **Android backup** — the phone's own backup and phone-to-phone transfer.

  Sync covers the library, chapter lists, reading progress and history. Downloaded
  chapters stay on each device unless you include them in a backup file. Passwords and
  sign-in tokens are never put in backups.

## Install

Download the APK from [Releases](../../releases). Each release is built by GitHub Actions
from the tagged source with the latest WebToEpub.

## Setting up sync

### WebDAV

*More → Backup and sync → WebDAV*, then enter the server address, username and password.
EpubForge creates an `EpubForge` folder there with one file, `library.json`.

- **Nextcloud:** `https://your.server/remote.php/dav/files/USERNAME/` with an
  [app password](https://docs.nextcloud.com/server/latest/user_manual/en/session_management.html#managing-devices).
- **Koofr:** `https://app.koofr.net/dav/Koofr/` with an app password.
- Other providers list their WebDAV address in their help pages.

Writes are conflict-safe: if two devices sync at the same moment, the second one re-reads,
merges and tries again.

### Supabase

1. Create a free project at [supabase.com](https://supabase.com).
2. Open the project's **SQL Editor**, paste [`docs/supabase.sql`](docs/supabase.sql) (the app
   can also copy it for you) and run it. It creates one table, `epubforge_sync`, protected so
   each user can only read and write their own row.
3. In *Project Settings → API*, copy the **Project URL** and the **anon / publishable key**.
4. In the app: *More → Backup and sync → Supabase*, enter both, then create an account or
   sign in with an email and password. Use the same account on your other devices.

By default Supabase asks new users to confirm their email; either confirm it, or turn off
*Confirm email* under *Authentication → Sign In / Providers* if the project is only for you.

## Building

Requirements: JDK 17, Android SDK (API 35), Node.js 20.

```bash
git clone https://github.com/dteviot/WebToEpub.git webtoepub
(cd webtoepub && npm install --omit=dev --ignore-scripts \
  && mkdir -p plugin/@zip.js/zip.js/dist plugin/dompurify/dist \
  && cp node_modules/@zip.js/zip.js/dist/zip-no-worker.min.js plugin/@zip.js/zip.js/dist/ \
  && cp node_modules/dompurify/dist/purify.min.js plugin/dompurify/dist/)
git clone https://github.com/readest/foliate-js.git foliate
gradle :app:assembleDebug        # or open the folder in Android Studio
```

Release builds are signed when `app/release.jks` exists and `KEYSTORE_PASSWORD` and
`KEY_ALIAS` are set; otherwise they fall back to the debug key. In CI the keystore comes from
the `KEYSTORE_BASE64` secret. Releases are published by pushing a tag such as `v1.1.0`.

### Tests

- `gradle :app:testDebugUnitTest` — EPUB export validated with
  [EPUBCheck](https://github.com/w3c/epubcheck), error messages, and the sync backends.
- **Sync tests** workflow — runs the WebDAV and Supabase backends against a real
  [wsgidav](https://github.com/mar10/wsgidav) server and a local Supabase stack.
- **Emulator test** workflow — installs the debug APK on an Android emulator and drives it
  end to end: adds a story from a local test site, downloads chapters, reads (paging,
  chapter list, themes), exports an EPUB, syncs through WebDAV to a "second device",
  restores a backup file and runs Android's backup and restore.

## How it works

| Piece | Where |
|---|---|
| WebToEpub copied into assets with a shim and driver injected | `app/build.gradle.kts` (`prepareWebToEpub`) |
| `chrome.*` APIs, `fetch`/XHR routed to native code | `app/src/main/w2e-shim/android-shim.js` |
| Headless driver: analyse a story, fetch one chapter at a time | `app/src/main/w2e-shim/android-driver.js` |
| Native networking: per-host pacing, backoff, verification pause | `net/` |
| Download queue, update checks, foreground service | `engine/` |
| Offline storage and EPUB export | `data/` |
| Reader (foliate-js in a WebView) | `reader/`, `app/src/main/reader-web/` |
| Sync (merge by last change) and backends | `sync/` |
| Backup files and automatic backups | `backup/` |

## License

GPL-3.0-or-later. EpubForge bundles WebToEpub (GPL-3.0) and foliate-js (MIT).
