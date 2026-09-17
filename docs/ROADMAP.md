# Roadmap

**Status:** early but daily-driven. The watch app records, reviews, and uploads to a self-hosted
server; the phone app is an optional companion.

## Shipped
- **0.1 / 0.2** — Health Services recording → TCX; direct watch→server upload (phone as backup).
- **0.3** — launcher icons; live recording notification with stats + pause/stop.
- **0.4** — pool/open-water swim; watch route sketch; phone app list + map (osmdroid).
- **0.5** — Strava-inspired UI: sport capsules, GPS pre-start, 3 swipeable recording pages, summary + splits.
- **0.6** — review gate (**Done** uploads / **Delete** removes; nothing implicit), auto-pause
  (platform, capability-gated — see `design/auto-pause.md`), round-screen layout fixes.
- **0.7** — upload queue + **Settings** (Sync now, Units, Auto-pause toggle), **Lap** button,
  haptics, design docs (`strava-gap-analysis.md`, `metrics-provenance.md`).
- **0.8** — TCX coordinate precision fix (routes render correctly), ongoing-activity chip on the
  watch face, richer notification, sticky review buttons, live-timer fix (checkpoint math + ticker).

## Next (ordered by value)
1. **In-app watch configuration** — set server URL + API key on the watch without a computer
   (today: `scripts/configure-watch.sh`).
2. **TCX lap splits** — write `Lap` elements from the Lap button so servers show intervals.
3. **Elevation + cadence rows** on the stats page (elevation is already recorded).
4. **HR zones / alerts** — zone colour band, optional max-HR buzz.
5. **Treadmill / indoor profiles** (the platform exposes `RUNNING_TREADMILL`).
6. **Watch route breadcrumb** (offline tiles) — small map on the route page.
7. **Screen customisation** — choose which fields appear on each page.
8. **Bulk import** — converter for Strava bulk-export zips and Samsung Health exports → server.

## Deliberately out of scope
- Segments / KOMs, social feeds, live beacon sharing — this is a personal, self-hosted tool.
- Any cloud service: the app talks to *your* server only.

## Architecture notes (for contributors)
- Watch is the source of truth for recording; the phone is a convenience/backup, never a dependency.
- Files are **never** deleted implicitly: explicit Delete on the review screen only; failed uploads
  stay queued indefinitely and retries are safe.
- Everything is SI/metric on disk (TCX); units are a display-time conversion only.
