#!/usr/bin/env bash
# End-to-end test on an Android emulator: add a story, download chapters, read, export.
set -u
OUT=e2e-out
mkdir -p "$OUT"
PKG=com.epubforge
URL="${E2E_URL:-http://10.0.2.2:8000/index.html}"
STEP=0

log() { echo "[e2e] $*" | tee -a "$OUT/steps.txt"; }
A() { timeout 25 adb "$@"; }
shot() { STEP=$((STEP+1)); A exec-out screencap -p > "$OUT/$(printf %02d $STEP)_$1.png"; log "screenshot $1"; }
dump() {
  rm -f "$OUT/ui.xml"
  A shell rm -f /sdcard/ui.xml >/dev/null 2>&1
  local i
  for i in 1 2 3; do
    timeout 20 adb shell uiautomator dump /sdcard/ui.xml > "$OUT/dump.log" 2>&1
    A pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 && [ -s "$OUT/ui.xml" ] && return 0
    echo "dump attempt $i failed: $(tr -d '\r' < "$OUT/dump.log" | tail -1)" >> "$OUT/dump_errors.txt"
    sleep 1
  done
}

# Find a node by text or content-desc (regex) and print "x y" of its centre.
find_node() {
  dump
  python3 - "$1" "$OUT/ui.xml" "${2:-first}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
pat = re.compile(sys.argv[1])
hits = []
try:
    root = ET.parse(sys.argv[2]).getroot()
except Exception:
    sys.exit(1)
for n in root.iter("node"):
    for attr in ("text", "content-desc"):
        v = n.get(attr) or ""
        if v and pat.fullmatch(v):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            hits.append(((x1 + x2) // 2, (y1 + y2) // 2))
if not hits:
    sys.exit(1)
x, y = hits[-1] if sys.argv[3] == "last" else hits[0]
print(x, y)
PY
}

tap() {
  local xy
  if xy=$(find_node "$1" "${2:-first}"); then A shell input tap $xy; log "tap '$1' at $xy"; sleep 1.5; return 0; fi
  log "NOT FOUND: '$1'"; return 1
}

diagnose() { # print what's on screen and recent app errors into the job log
  log "focus: $(A shell dumpsys window | grep -m1 -E 'mCurrentFocus' | tr -d '\r')"
  log "dump errors: $(tail -2 "$OUT/dump_errors.txt" 2>/dev/null | tr '\n' ' ')"
  A logcat -d -v brief '*:E' | grep -iE 'epubforge|AndroidRuntime|FATAL' | tail -15 | while read -r l; do log "logcat: ${l:0:220}"; done
  [ -f "$OUT/ui.xml" ] && log "screen texts: $(grep -oE 'text="[^"]+"' "$OUT/ui.xml" | head -12 | tr '\n' ' ' | cut -c1-400)"
}
wait_for() { # regex, seconds
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do
    if find_node "$1" >/dev/null; then log "found '$1' after $(( $2 - (end - SECONDS) ))s"; return 0; fi
    if find_node "Verify you're human|Verification needed" >/dev/null; then log "VERIFICATION PAGE SHOWN"; shot verification; fi
    sleep 3
  done
  log "TIMEOUT waiting for '$1'"
  diagnose
  return 1
}

adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!

adb install -r app/build/outputs/apk/debug/app-debug.apk | tee -a "$OUT/steps.txt"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
# Simulate a punch-hole camera so the reader's header placement is tested against a cutout.
adb shell cmd overlay enable com.android.internal.display.cutout.emulation.hole >/dev/null 2>&1 || true
sleep 2
log "display cutout: $(adb shell dumpsys display | grep -m1 -oE 'DisplayCutout\{[^}]*\}' | head -c 200)"
# Skip Android's one-time "Viewing full screen" tip so it doesn't cover the reader.
adb shell settings put secure immersive_mode_confirmations confirmed || true
adb shell wm size | tee -a "$OUT/steps.txt"
read W H < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')

# 1. Add the story via share intent (like sharing a link from Chrome).
adb shell am start -n $PKG/.ui.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "$URL"
wait_for "Add to library" 60
shot add_sheet
tap "Add to library"
sleep 2
shot adding

# 2. Story page appears with its chapter list.
wait_for "[0-9]+ chapters?" 180 || { shot story_failed; }
sleep 3
shot story_page

# 3. Download the next 5 chapters from the toolbar menu.
tap "Download"
sleep 1
shot download_menu
tap "Next 5 chapters" || tap "All chapters" || tap "Next chapter"
sleep 4
shot story_downloading

# 4. Downloads tab shows the queue.
adb shell input keyevent KEYCODE_BACK
sleep 2
tap "Downloads" last
sleep 5
shot downloads_tab
tap "Download settings and log" && { sleep 2; shot download_log_sheet; find_node "Copy log" >/dev/null && { adb shell input keyevent KEYCODE_BACK; sleep 1; }; }
wait_for "No downloads" 300 || shot downloads_stuck
shot downloads_done

# 5. Library shows the story with badges.
tap "Library" last
sleep 3
shot library

# Story id from app storage (debug build allows run-as).
SID=$(adb shell run-as $PKG ls files/stories 2>/dev/null | tr -d '\r' | head -1)
log "story id: $SID"
adb shell run-as $PKG ls -la files/stories/$SID files/stories/$SID/ch files/stories/$SID/img > "$OUT/files.txt" 2>&1
A shell run-as $PKG sh -c "head -c 3000 files/stories/$SID/ch/\$(ls files/stories/$SID/ch | head -1)" > "$OUT/chapter_sample.xhtml" 2>&1

# Big images are scaled down when saved: report stored image sizes.
for f in $(A shell "run-as $PKG sh -c 'ls files/stories/*/img/*'" 2>/dev/null | tr -d '\r'); do
  A exec-out run-as $PKG cat "$f" > "$OUT/img_check.bin"
  log "stored image $(basename "$f"): $(python3 -c "import struct,sys; d=open(sys.argv[1],'rb').read(); print(('%dx%d' % struct.unpack('>II', d[16:24])) if d[:4]==b'\x89PNG' else '?', len(d), 'bytes')" "$OUT/img_check.bin")"
done

# Resource use. CPU is the exact CPU time (kernel ticks, 10 ms each) used by the app and its WebView
# renderer; memory is PSS from "Total PSS by process" (the renderer runs as sandboxed_process).
procs() { echo "$(A shell pidof $PKG | tr -d '\r') $(A shell ps -A -o PID,NAME | grep sandboxed_process | awk '{print $1}' | tr '\n' ' ')"; }
cpu_ticks() {
  local t=0 v p
  for p in $(procs); do v=$(A shell cat /proc/$p/stat 2>/dev/null | awk '{print $14+$15}'); t=$((t + ${v:-0})); done
  echo $t
}
mem_kb() { # regex of process names -> summed PSS in KB
  A shell dumpsys meminfo | awk '/Total PSS by process/{f=1;next} f&&/^ *$/{exit} f' | grep -E "$1" \
    | sed -E 's/^ *([0-9,]+)K.*/\1/' | tr -d ',' | awk '{s+=$1} END{print s+0}'
}
measure() { # label: 10 s idle
  local c0=$(cpu_ticks); sleep 10; local c1=$(cpu_ticks)
  log "MEASURE $1: idle_cpu_ms_10s=$(( (c1 - c0) * 10 )) app_pss_kb=$(mem_kb 'com\.epubforge') renderer_pss_kb=$(mem_kb 'sandboxed_process')"
}
reading_session() { # label: a page every 2 s for a minute
  local c0=$(cpu_ticks)
  for i in $(seq 1 30); do A shell input tap $((W * 9 / 10)) $((H / 2)); sleep 2; done
  local c1=$(cpu_ticks)
  log "MEASURE $1: reading_30_pages_cpu_ms=$(( (c1 - c0) * 10 )) app_pss_kb=$(mem_kb 'com\.epubforge') renderer_pss_kb=$(mem_kb 'sandboxed_process')"
}
measure library_idle

# 6. Open the story from the library (first grid cell) and start reading.
A shell input tap $((W * 17 / 100)) $((H * 19 / 100)); sleep 3
wait_for "Start|Resume" 30 || cp "$OUT/ui.xml" "$OUT/ui_no_start.xml"
shot story_after_download
# Is the story screen idle? Count frames drawn over 5 quiet seconds.
A shell dumpsys gfxinfo $PKG reset >/dev/null; sleep 5
log "story screen frames in 5s idle: $(A shell dumpsys gfxinfo $PKG | grep -m1 'Total frames rendered' | tr -d '\r')"
tap "Start|Resume" || { A shell input tap $((W * 83 / 100)) $((H * 90 / 100)); log "tapped FAB position"; }
sleep 8
shot reader_open
bounds_of() { dump; python3 - "$1" "$OUT/ui.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
pat = re.compile(sys.argv[1])
try:
    for n in ET.parse(sys.argv[2]).iter("node"):
        if pat.fullmatch(n.get("text") or "") or pat.fullmatch(n.get("content-desc") or ""):
            print((n.get("text") or n.get("content-desc")) + " " + n.get("bounds")); break
except Exception as e: print("dump failed", e)
PY
}
log "reader header: $(bounds_of 'Chapter 1.*')"
log "reader battery: $(bounds_of '[0-9]+%')"
measure reader_story_idle
reading_session reader_story_session
adb shell input tap $((W * 9 / 10)) $((H / 2)); sleep 2; shot reader_next_page
adb shell input tap $((W * 9 / 10)) $((H / 2)); sleep 2; shot reader_next_page2
adb shell input keyevent KEYCODE_VOLUME_DOWN; sleep 2; shot reader_volume_key
adb shell input swipe $((W * 8 / 10)) $((H / 2)) $((W * 2 / 10)) $((H / 2)) 250; sleep 2; shot reader_swipe
adb shell input tap $((W * 1 / 10)) $((H / 2)); sleep 2; shot reader_prev_page
# Page through to the end of chapter 1 and into chapter 2.
for i in $(seq 1 40); do adb shell input tap $((W * 9 / 10)) $((H / 2)); sleep 0.6; done
sleep 2; shot reader_after_40_pages
adb shell input tap $((W / 2)) $((H / 2)); sleep 2; shot reader_controls
tap "Chapters" && { sleep 2; shot reader_chapters; tap "Chapter 4.*" && { sleep 4; shot reader_jump_ch4; }; }
adb shell input tap $((W / 2)) $((H / 2)); sleep 2
tap "Display" && { sleep 2; shot reader_display; tap "Sepia"; sleep 2; shot reader_display_sepia; adb shell input keyevent KEYCODE_BACK; sleep 1; }
adb shell input tap $((W / 2)) $((H / 2)); sleep 1
shot reader_sepia
adb shell input keyevent KEYCODE_BACK; sleep 3
shot story_after_reading

# 7. Export an EPUB.
tap "More" || A shell input tap $((W * 93 / 100)) $((H * 9 / 100)); sleep 1
shot overflow_menu
tap "Export EPUB" && sleep 2
shot export_dialog
tap "Export"
wait_for "Created .*" 60
shot export_done
adb shell run-as $PKG sh -c 'ls -la cache/exports' >> "$OUT/files.txt" 2>&1
A exec-out run-as $PKG sh -c 'cat cache/exports/*.epub' > "$OUT/export.epub" 2>/dev/null
ls -la "$OUT/export.epub" | tee -a "$OUT/steps.txt"

# 8. Updates and History tabs.
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1
tap "History" last && { sleep 2; shot history_tab; }
tap "Updates" last && { sleep 2; shot updates_tab; }

# 9. Sync and backup.
open_backup_screen() {
  A shell am start -n $PKG/.ui.MainActivity >/dev/null; sleep 3
  to_tabs
  tap "More" last; sleep 1
  tap "Backup and sync"; sleep 2
}
type_into() { # label regex, text
  tap "$1" && { A shell input text "$2"; sleep 0.5; }
}
connect_webdav() {
  open_backup_screen
  tap "WebDAV"; sleep 1
  type_into "Server address" "http://10.0.2.2:8090/"
  type_into "Username" "tester"
  type_into "Password or app password" "secret"
  A shell input keyevent KEYCODE_BACK; sleep 1   # hide the keyboard, which covers the button
  shot "webdav_form_$1"
  tap "Connect"
  wait_for "Sync now" 60 && sleep 4
  shot "webdav_connected_$1"
}
to_tabs() { # back out of sub-screens to the main tabs
  for i in 1 2 3; do find_node "Library" >/dev/null && find_node "More" >/dev/null && return 0; A shell input keyevent KEYCODE_BACK; sleep 1; done
  A shell am start -n $PKG/.ui.MainActivity >/dev/null; sleep 2
}
library_story() { # open the first story from the library
  to_tabs
  tap "Library" last; sleep 2
  A shell input tap $((W * 17 / 100)) $((H * 19 / 100)); sleep 4
}
e2e_log() { A logcat -d -s E2E:I | grep -E "OK|FAILED" | tail -1; }

connect_webdav first
if [ -n "${DAV_ROOT:-}" ] && [ -f "$DAV_ROOT/EpubForge/library.json" ]; then
  log "webdav file: $(wc -c < "$DAV_ROOT/EpubForge/library.json") bytes, story present: $(grep -c 'The Lighthouse Count' "$DAV_ROOT/EpubForge/library.json"), read chapters: $(grep -o '"read":true' "$DAV_ROOT/EpubForge/library.json" | wc -l)"
  cp "$DAV_ROOT/EpubForge/library.json" "$OUT/webdav_library.json"
else
  log "WEBDAV FILE MISSING"
fi

# Backup file (via the debug hook), copied off the device.
A logcat -c
A shell am broadcast -n $PKG/.DebugHooks -a com.epubforge.debug.BACKUP --ez chapters true >/dev/null; sleep 6
log "backup: $(e2e_log)"
A exec-out run-as $PKG cat files/e2e-backup.zip > "$OUT/backup.zip"
unzip -l "$OUT/backup.zip" > "$OUT/backup_contents.txt" 2>&1; log "backup zip entries: $(grep -c 'stories/' "$OUT/backup_contents.txt")"

# A "second device": wipe the app, connect the same WebDAV, and the library should come back.
A shell pm clear $PKG >/dev/null; sleep 2
A shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
connect_webdav second
library_story; shot story_from_webdav
A shell run-as $PKG ls files/stories > "$OUT/after_webdav.txt" 2>&1

# Restore the backup file on top: downloaded chapters come back too.
A push "$OUT/backup.zip" /data/local/tmp/e2e-backup.zip >/dev/null
A shell run-as $PKG cp /data/local/tmp/e2e-backup.zip files/e2e-backup.zip
A logcat -c
A shell am broadcast -n $PKG/.DebugHooks -a com.epubforge.debug.RESTORE >/dev/null; sleep 8
log "restore: $(e2e_log)"
library_story; shot story_after_restore
log "chapter files after restore: $(A shell "run-as $PKG sh -c 'ls files/stories/*/ch | wc -l'" | tr -d '\r')"

# Android's own backup, using the emulator's local transport.
{
  A shell bmgr enable true
  A shell bmgr transport com.android.localtransport/.LocalTransport
  A shell bmgr backupnow $PKG
} > "$OUT/android_backup.txt" 2>&1
A shell pm clear $PKG >/dev/null; sleep 2
TOKEN=$(A shell bmgr list sets 2>/dev/null | grep -oE '^ *[0-9a-f]+' | head -1 | tr -d ' \r')
log "android backup token: ${TOKEN:-none}"
A shell bmgr restore ${TOKEN:-1} $PKG >> "$OUT/android_backup.txt" 2>&1
A shell run-as $PKG ls -la databases files/datastore shared_prefs >> "$OUT/android_backup.txt" 2>&1
A shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
A shell am start -n $PKG/.ui.MainActivity >/dev/null; sleep 4
tap "Library" last; sleep 2; shot library_after_android_restore
grep -E "Backup finished|Success|error|Error|restoreFinished|Package" "$OUT/android_backup.txt" | head -8 | while read l; do log "bmgr: $l"; done

# 10. Import ebook files (EPUB and PDF) and read them.
A shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
for f in keepers-log.epub sample.pdf; do
  A push "${SITE_DIR:-site}/$f" /data/local/tmp/$f >/dev/null
  A shell run-as $PKG cp /data/local/tmp/$f files/$f
  A logcat -c
  A shell am broadcast -n $PKG/.DebugHooks -a com.epubforge.debug.IMPORT --es file $f >/dev/null; sleep 5
  log "import $f: $(e2e_log)"
done
to_tabs; tap "Library" last; sleep 2; shot library_with_books
open_book() { # title regex
  to_tabs; tap "Library" last; sleep 2
  tap "$1" && sleep 3
  shot "book_page_$2"
  tap "Start|Resume" || A shell input tap $((W * 83 / 100)) $((H * 90 / 100))
  sleep 8; shot "book_reader_$2"
  measure "reader_$2_idle"
  [ "$2" = epub ] && reading_session reader_epub_session
  A shell input tap $((W * 9 / 10)) $((H / 2)); sleep 2; shot "book_reader_$2_next"
  A shell input tap $((W / 2)) $((H / 2)); sleep 2; shot "book_reader_$2_controls"
  tap "Chapters" && { sleep 2; shot "book_contents_$2"; A shell input keyevent KEYCODE_BACK; sleep 1; }
  A shell input keyevent KEYCODE_BACK; sleep 1; A shell input keyevent KEYCODE_BACK; sleep 2
  shot "book_page_after_$2"
}
open_book "The Keeper.s Log" epub
open_book "sample" pdf
A logcat -d | grep -E "DIAG ready" | tail -2 | cut -c1-300 | while read l; do log "$l"; done
# 11. In-app updater: pretend to be 1.0.0, find the latest real release, download it.
if [ -n "${E2E_REPO:-}" ]; then
  A shell am start -n $PKG/.ui.MainActivity >/dev/null; sleep 3
  A logcat -c
  A shell am broadcast -n $PKG/.DebugHooks -a com.epubforge.debug.CHECK_UPDATE --es repo "$E2E_REPO" >/dev/null; sleep 6
  log "update check: $(A logcat -d -s E2E:I | grep CHECK_UPDATE | tail -1 | cut -c1-260)"
  if wait_for "Update available" 20; then
    shot update_available
    tap "Update"
    if wait_for "Ready to install|Update failed" 180; then shot update_ready; else shot update_stuck; fi
    log "update download: $(A logcat -d | grep -E 'Updater' | tail -2 | cut -c1-220 | tr '\n' ' ')"
    log "update file: $(A shell "run-as $PKG sh -c 'ls -l cache/updates'" | tr -d '\r' | tail -1)"
    A shell input keyevent KEYCODE_BACK; sleep 1
  fi
fi

A shell input keyevent KEYCODE_HOME; sleep 5
measure background

kill $LOGCAT 2>/dev/null
grep -E " (E|W)/(Reader|W2E|AndroidRuntime|chromium|EpubForge)|FATAL|Exception" "$OUT/logcat.txt" | head -200 > "$OUT/errors.txt"
log "done"
exit 0
