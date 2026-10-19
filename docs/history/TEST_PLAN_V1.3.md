# Buccaneer Playback & Stability Test Plan (v1.3)

Purpose: collect data that shows *why* playback stutters and pages crash on the Fire TV,
using the built-in diagnostic system. Run this once per build and compare exports.

Device under test: AFTMM, Android 7.1.2, 1.28 GB RAM. Baseline evidence already gathered:
- MemAvailable ~230 MB while the app is running (genuinely tight).
- Six native SIGSEGV crashes in the app process, all on RenderThread/Chrome_InProcGp
  with `oom: 0` — GPU-driver crashes, not OOM kills.
- No Widevine/DRM stack on the device (`/system/lib/mediadrm` absent), so encrypted
  streams can never play; unencrypted HLS/MP4 can.

## Protocol

1. Settings > Diagnostics > enable logging, "Start new diagnostic session" (this writes
   the session metadata: model, WebView provider/version, memory snapshot).
2. For each site (fmhy.net/video, flixer.gd, 7movies.ac, movienig.ht), in the same order:
   a. Open it from the bookmarks panel (this logs `user_navigation` + `page_started`).
   b. Wait for `page_finished` (gives loadMs per site).
   c. Start a video, let it play 60 s. The `media_health` sampler logs buffer-ahead,
      ready/network state, and frame counters every 3 s while playing.
   d. Seek once to a different position, then pause and resume once.
   e. Note any stall/error/pause events (logged automatically) and any crash toast.
3. Repeat steps 2a-2e three times per site (same video if possible) so variance is visible.
4. Mid-run, open Settings > Show diagnostics snapshot twice (memory + WebView state at two
   points in the session).
5. Export the diagnostic log, pull it with adb, and analyze events.jsonl.

## What to look for in the export

- `page_finished.loadMs` per site: first load vs. repeat loads (the "double load" on
  fmhy.net shows up as two `page_started` events for one `user_navigation`).
- `media_health` buffer-ahead dropping to ~0 before `media_stalled` events = network/buffer
  starvation; stalls with a healthy buffer-ahead = decode starvation.
- `render_process_gone` with `didCrash=true` vs. false distinguishes GPU-driver crashes
  from Android memory kills. Cross-check against the crash buffer (`adb logcat -b crash`)
  and `am_low_memory` lines.
- `media_manifest_request` + `media_error` on DRM sites: expected to fail on this device
  (no Widevine) — that is a device limitation, not an app bug.

## Analysis script (run on the host after export)

python3 - <<'EOF'
import json, collections
stalls = collections.Counter(); loads = {}; buf = collections.defaultdict(list)
for line in open("events.jsonl"):
    e = json.loads(line); d = e.get("data", {}); ev = e["event"]
    host = d.get("url","").split("/")[2] if d.get("url","").startswith("http") else ""
    if ev == "page_finished" and host: loads.setdefault(host, []).append(d.get("loadMs"))
    if ev in ("media_stalled","media_error"): stalls[host] += 1
    if ev == "media_health" and d.get("bufferAhead") is not None: buf[host].append(d["bufferAhead"])
for h in set(list(loads)+list(stalls)+list(buf)):
    print(h, "loads:", loads.get(h), "stalls/errors:", stalls.get(h),
          "min buffer-ahead:", min(buf.get(h, [None])))
EOF

## Pass criteria for the next iteration

- No `render_process_gone` during a 3x run on any site (or, if present, all are
  `didCrash=true` GPU-driver signatures, which need a device-side mitigation, not app code).
- Buffer-ahead stays > 5 s during playback on at least 2 of 4 sites.
- fmhy.net shows exactly one `page_started` per `user_navigation` after the fix.
