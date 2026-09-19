# Phone app → Dreeve companion: plan

Status: **implemented (phone 0.5.0)** — shipped on `openFit-dev`, ported to `openFit`.
Verified on an Android 16 emulator against a real Dreeve instance: dashboard, activities list
(201 rows, wide table, horizontal scroll), segments (48 real segments), activity detail (map +
stats + GPX button), in-page (SPA) link navigation, drawer, bottom bar, Sync and Settings tabs.

## Implementation notes (hard-won)

- **The app renders the user's own Dreeve pages** in a WebView inside a native shell, which is why
  every statistic matches the server exactly. Dreeve's own chrome is hidden with injected CSS
  (`div.antialiased>nav`, `aside#drawer-navigation`, the breadcrumb) — re-asserted with a
  `MutationObserver` + slow interval because Dreeve's client-side rendering replaces the style node.
- **Viewport:** use `useWideViewPort = true` with `loadWithOverviewMode = true`. With wide-viewport
  off, the page sees a bogus viewport height (e.g. 2793 px instead of ~870) and Dreeve's
  `max-h-[calc(100vh-285px)]` table wrapper resolves to **0 px**, collapsing every list to a thin
  strip while the rows are in the DOM all along (432 cells rendered, invisible).
  `documents/design` note: we additionally force `.scroll-area{max-height:none;overflow-x:auto;
  overflow-y:visible}` so the page scrolls naturally and wide tables stay horizontally reachable.
- **Navigation:** Dreeve is a client-side app; the current URL must be tracked via
  `WebViewClient.doUpdateVisitedHistory` (not just `onPageFinished`) or the top bar's context
  actions (GPX on activity pages) never update.
- **Debugging WebViews:** `WebChromeClient.onConsoleMessage → Logcat` plus a small fetch/XHR probe
  in debug builds turns "the page looks empty" into a concrete answer (`OF-FETCH 200 …`); worth
  keeping while the app targets an internal API.
- **Data access:** pages + `/api/internal/fragment/{page|partial|data}/{path}` (what Dreeve's own
  frontend uses), `GET /api/v1/status` for the connection test, and
  `GET /api/internal/activity/{id}/route.gpx` for GPX export. Treat the internal routes as optional
  — they are Dreeve's, not a public contract.

## Original plan (kept for context)

## 1. Vision

The watch is the recorder; the phone app should become the **companion that lets you read and manage
everything your watch feeds into Dreeve** — dashboard, activities, segments, gear, heatmaps — without
opening a browser. It must work against **any** Dreeve instance (it is what users self-host), so:

**Principle: no server-side changes, no Dreeve fork, no sidecar service.** The app talks only to the
public surface every Dreeve instance already exposes.

## 2. Architecture

A native shell around Dreeve's own rendering.

```
┌─ openFit phone app (Compose, Material 3, Dreeve design tokens) ────────────┐
│  Bottom navigation:  Dashboard · Activities · Sync · Settings             │
│                                                                           │
│  ┌ WebView ─ Dreeve pages ──────────────────────────────┐  ┌ native ────┐ │
│  │ GET {server}/dashboard | /activities/{id} | /segments │  │ Sync       │ │
│  │      /heatmap | /gear | /badges | /rewind …           │  │ Settings   │ │
│  │  - responsive pages (viewport already mobile-ready)   │  │ Map (osm)  │ │
│  │  - theme injected (data-theme=dark ⇄ app theme)       │  │ Upload     │ │
│  │  - per-tab back stack, pull-to-refresh                │  │ queue      │ │
│  └───────────────────────────────────────────────────────┘  └────────────┘ │
└───────────────────────────────────────────────────────────────────────────┘
```

**Why WebView for the stats and not a full native re-implementation**

| Option | Ship cost | Looks like Dreeve | New Dreeve features appear | Works for any instance |
|---|---|---|---|---|
| **A. Hybrid native shell + Dreeve pages (recommended)** | low | exactly (it *is* Dreeve) | yes, free | yes |
| B. Fully native UI on a JSON sidecar (read `strava.db`) | high | re-implemented, drifts | no | **no** (needs our sidecar) |
| C. Plain WebView wrapper | very low | yes | yes | yes |

Option B is what "a real app" instinct suggests, but it means shipping a **second server component**:
the app would only work on instances where someone also runs our bridge, and every Dreeve release
would need matching work. Option A gets 100% of Dreeve's stats (including future ones) for a fraction
of the cost. We take A, with C's mechanics and a native layer that adds what a browser can't.

**Native pieces (the app's own value beyond a browser)**
- server configuration + connection test (`GET /api/v1/status` with the API key)
- upload queue status and manual sync of activities recorded by *our* watch (works while offline)
- local map/list of activities received over the Wearable Data Layer (osmdroid) — already built
- share/export an activity as GPX (`GET /api/internal/activity/{id}/route.gpx`)
- deep links into the right Dreeve page from a notification

## 3. Feature analysis — Strava phone app ⇄ Dreeve ⇄ our plan

Legend: ✅ available today · ⚠️ partial/needs thought · ❌ not in Dreeve (skip)

| Strava feature | Dreeve equivalent | Plan |
|---|---|---|
| Home feed of followed athletes (kudos, comments) | ❌ (single-athlete, no social) | skip — deliberately out of scope |
| **Dashboard** (weekly/monthly summary cards, charts) | `/dashboard` (+ monthly-stats, calendar) | ✅ tab, WebView |
| **Activities list** (filters by type/date) | `/activities` | ✅ tab, WebView |
| **Activity detail**: map, streams, charts, splits, laps | `/activities/{id}` | ✅ WebView (+ native GPX export) |
| Relative effort / HR zones | Dreeve charts + HR zones | ✅ via detail page |
| Best efforts (1k/5k/10k …) | `/best-efforts` | ✅ via Dashboard/More |
| **Segments** (explore, efforts, personal bests, KOM) | `/segments` + segment fragments (efforts, data table) — **local only, no leaderboards** | ✅ personal bests; no social ranking |
| Global + personal **heatmap** | `/heatmap` (routes + countries) | ✅ via More |
| **Eddington** number | `/eddington` | ✅ via More |
| **Milestones** (totals, achievements) | `/milestones` | ✅ via More |
| **Rewind** (year in review) | `/rewind` | ✅ via More |
| **Badges** | `/badges` + `/badge/{name}.svg` | ✅ via More |
| **Gear** (shoes/bikes, mileage) + maintenance reminders | `/admin/gear`, maintenance logs/config, recording devices | ✅ Gear screen (Dreeve's own pages) |
| **Training log / fitness & freshness** | ⚠️ no direct equivalent yet (charts on dashboard) | ⚠️ check on build; show dashboard charts |
| Challenges / clubs | ❌ | skip |
| Safety beacon / live location | ❌ | skip (self-hosted, privacy) |
| **Record** an activity | our **watch app** does this; phone recording is not planned | ✅ handled by watch |
| **Upload / sync from a device** | our watch uploads directly; phone is fallback | ✅ native Sync tab |
| Routes / route builder | ❌ (heatmaps only) | skip for v1 |
| Segments live during activity | ❌ | skip |
| Social sharing of an activity | ❌ | GPX export instead (v1), image share later |
| Notifications (kudos, comments, goals) | ❌ social; ⚠️ gear-maintenance + import-failure notifications exist in Dreeve | ⚠️ later: surface Dreeve's own notification feed if it exists |

Everything Dreeve makes available would therefore be reachable from the app — its stats are the app's
stats.

## 4. Screens & tabs (v1)

```
[ Dashboard ]  [ Activities ]  [ Sync ]  [ Settings ]        ← bottom nav
```

**Dashboard** — WebView on `/dashboard`. Pull-to-refresh. The site's responsive layout already
stacks its cards for phones.
**Activities** — WebView on `/activities`; tapping an activity opens detail (`/activities/{id}`) with
its own back stack. Long-press/link detection → native "Open GPX in…" share.
**Sync** — native: upload queue (files received from the watch), per-item status, "Sync pending",
server reachability chip, last-upload result, and the local list + map (osmdroid) of received activities.
**Settings** — native: server URL, API key, **Test connection** (`/api/v1/status`), units
(display-only), theme (light/dark/system), "Open Dreeve admin" link, app version, and diagnostics
(last upload error, queue counts). The key is stored in `EncryptedSharedPreferences` (currently plain
prefs — fix while we're here).

**More/additional tabs** (Dashboard sub-nav or a "More" grid — open question §8):
Each of `/segments`, `/heatmap`, `/eddington`, `/milestones`, `/rewind`, `/badges`, `/gear` gets a
route; cheap to add since they are all WebView routes with titles and icons.

## 5. Look and feel = Dreeve

Design tokens read from Dreeve's own CSS (Tailwind v4 custom properties):

| Token | Light | Dark |
|---|---|---|
| primary | `#f26722` | `#f26722` |
| primary-soft | `#fff1e9` | `color-mix(primary 10%, transparent)` |
| surface / bg | `#ffffff` | `#202830` |
| text (black) | `#000000` | `#f0f6fc` |
| grey-yo | `#cccccc` | — |

- Native chrome (bottom nav, toolbars, cards, buttons) uses these tokens — Material 3 dynamic color
  disabled so Dreeve's orange is authoritative.
- Theme is applied to the WebView too (`data-theme=dark|light` on `<html>` via JS injection) so the
  embedded pages match the app chrome.
- Typography: Dreeve uses its Tailwind sans stack; we mirror sizes/weights rather than fight it.
- The app's own branding stays openFit (icon/name); the **content** is Dreeve's — with the Dreeve
  wordmark untouched inside the WebView.

## 6. Data access — the exact contract

| Purpose | Call | Notes |
|---|---|---|
| Connection test | `GET /api/v1/status` | needs `Authorization: Bearer <key>` (verified: 401 without) |
| Upload a file | `POST /api/v1/activity/upload` (multipart) | already used by us |
| Any page | `GET {server}/dashboard`, `/activities`, `/activities/{id}`, `/segments`, `/heatmap`, `/eddington`, `/milestones`, `/monthly-stats`, `/rewind`, `/badges` | anonymous on the reference instance; see §7 |
| Page fragment (chrome-less) | `GET /api/internal/fragment/page/{path}` | verified working (`page/dashboard` → 200 HTML). `partial/*` and `data/*` exist too; enumerate on build |
| Activity GPX | `GET /api/internal/activity/{id}/route.gpx` | for export/share |
| Badge image | `GET /badge/{name}.svg` | |

⚠️ `/api/internal/*` is what Dreeve's own frontend uses — stable in practice but **internal**: treat
failures as soft (fall back to the full page in the WebView) and pin nothing.

## 7. Auth & privacy (needs a decision)

The reference instance currently serves **everything anonymously** — dashboard, activities (with maps
and heart rate), heatmaps — to anyone who has the URL, and its page title contains the owner's name.

Options:
1. **Leave anonymous, ship anyway** — simplest; the app needs no auth UI.
2. **Recommend the operator protects the instance** (reverse-proxy basic auth, Cloudflare Access, or
   Tailscale-only) and support **HTTP Basic** credentials + custom headers in the app (a few lines),
   so protected instances work too.
3. Full login flow — Dreeve has no multi-user auth; not applicable.

Recommendation: **2** — support optional Basic auth / extra header in Settings, document the privacy
note in the README, and (separately) offer to lock down the reference instance.

## 8. Open questions (blocking the build)

1. **Approach**: confirm option A (hybrid: native shell + Dreeve pages in WebView) vs B (native UI +
   sidecar reading `strava.db`). A ships much sooner and works for everyone; B is a bigger, riskier
   build with a server component to run.
2. **Tab set for v1**: Dashboard · Activities · Sync · Settings, with segments/heatmap/eddington/
   milestones/rewind/badges/gear reachable from a "More" screen — or promote some to their own tabs?
3. **Auth**: go with recommendation 2 (optional Basic auth support + privacy note)? And should I
   protect the reference instance (it is publicly readable today)?
4. **Phone recording**: confirm the phone stays a companion (recording = watch only) — Strava can
   record on the phone, but we already have the watch for that.
5. **Branding**: app named openFit with Dreeve's look (recommended) vs something Dreeve-branded.

## 9. Milestones (once §8 is answered)

- **M-P1 — Shell & settings** (native): bottom nav, theming with Dreeve tokens, Settings with server
  URL + key + Test connection, EncryptedSharedPreferences, deep-linkable routes. Deliverable: app
  installs, connects, shows Dreeve's dashboard.
- **M-P2 — Browsing**: Activities list → detail with back stack, pull-to-refresh, GPX export/share,
  More screen for the remaining pages, error/offline states.
- **M-P3 — Sync & maps**: native Sync tab (queue, statuses, manual sync) + local list/map of received
  activities; upload-path sanitising guard (sensor-sentinel protection).
- **M-P4 — Polish**: dark/light switching, notifications on upload result, gear/maintenance surfacing,
  optional Basic auth, README/docs for other users, port to the public repo + release.

## 10. Risks

| Risk | Mitigation |
|---|---|
| `/api/internal/*` changes in a Dreeve release | only used for optimisations (chrome-less fragments, GPX); everything else is a normal page load |
| WebView feels like a browser | hide chrome via fragments where it works; native tabs/gestures; the app's own Sync/Settings are native |
| Instance protected by a proxy | optional Basic auth support (§7) |
| Repo split drift (dev vs public) | develop on `openFit-dev`, port via the `public` branch flow already in use |
