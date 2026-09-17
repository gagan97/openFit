# Strava gap analysis — what openFit has, what's missing, what's deliberately out

Compiled 2026-09-16 (overnight build). Scope: the **watch app** (Strava's own watch experience) +
the essentials of the phone app. Stats/analysis/social live in **Dreeve** (our server) — not duplicated here.

## Recording & controls
| Feature | Strava | openFit | Notes |
|---|---|---|---|
| Sport profiles | run/ride/swim/walk + many | 6: walk, run, ride, gym, pool swim, open water | treadmill type exists in the SDK — slotted |
| Start / Pause / Resume / Stop | ✓ | ✓ | live notification actions too |
| Auto-pause | ✓ (runs/rides; GPS for rides, accelerometer for runs) | ✓ v0.6.1 (platform, capability-gated; walk/run/ride when the device supports it) | manual pause always wins |
| **Laps (manual)** | ✓ side button | ✓ v0.7.0 — Lap button + live lap count + haptic | next: write lap splits into the TCX |
| Audio cues (splits) | ✓ (phone audio) | ✓ v0.7.0 as **haptics** (split/lap/pause/resume) | watch speakers are rare; haptics are the wrist-native equivalent |
| Auto-pause haptic feedback | — | ✓ v0.7.0 | |
| Screen pages / data fields | configurable | 3 fixed pages (metrics / stats / route) | customisation later |
| Route navigation / breadcrumb | ✓ | ✗ | M4 (needs offline map data) |
| Segments / KOMs | ✓ | ✗ | **Deliberately out of scope** (personal tool, no social layer) |
| Live beacon / safety | ✓ | ✗ | deliberately out (self-hosted, privacy) |
| HR zones / alerts | partial | ✗ | candidate: zone colour + max-HR alerts |
| Cadence | ✓ (runs) | ✗ | SDK has step-rate data types — candidate |
| Elevation | ✓ | recorded (ELEVATION_GAIN) but not displayed | easy add to stats page |
| Indoor/treadmill mode | ✓ | ✗ | SDK RUNNING_TREADMILL supported — candidate |
| Workout/interval targets | ✓ | ✗ | far future |

## Data integrity & sync
| Feature | Strava | openFit | Notes |
|---|---|---|---|
| Offline recording | ✓ | ✓ (file written locally, always) | |
| Upload queue with retry | ✓ | ✓ v0.7.0 — Sync now in Settings; files kept until Dreeve confirms | dedupe-safe retries |
| Manual "sync pending" | implicit | ✓ v0.7.0 | the user's ask: never lose an activity |
| Review before upload (Done/Delete) | ✗ (auto) | ✓ v0.6.0 (our gate) | user-requested |
| Units (metric/imperial) | ✓ | ✓ v0.7.0 (Settings; display-only — files stay metric) | |
| Direct watch→server sync | via phone | ✓ direct (phone as backup) | validated |

## Server-side (Dreeve — already ours)
Activity list & detail pages, per-activity Leaflet route maps + heatmaps, splits, best efforts,
personal records, weekly/monthly stats, gear tracking (Dreeve supports gear), goals (Dreeve has
challenges/goals), segments — Dreeve computes "best efforts" on imported activities. Nothing to build.

## Not features we need
Club feeds / social / kudos — Dreeve is single-user. Route builder — not requested. Photos on
activities — Dreeve admin can attach. Payment/subscription — N/A.

## Next (candidates, ordered by value)
1. TCX lap splits from manual laps (so Dreeve shows intervals).
2. Elevation row on the stats page (data already recorded).
3. HR zone colour band + optional max-HR buzz.
4. Treadmill/indoor run profile (RUNNING_TREADMILL).
5. Cadence (steps/min) on the stats page for runs/walks.
6. Watch-screen customisation (field picker).
7. Route breadcrumb (M4, offline tiles).
