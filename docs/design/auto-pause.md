# Auto-pause — research, rules, design

**Asked for:** stop moving → recording pauses automatically; start moving again → it resumes (Strava-style).

## Research

### How Strava does it
- **Cycling** — GPS-movement based: pauses only when completely stopped, resumes when moving again. On by default.
- **Running** — motion/accelerometer based: detects rest from running movement.
- **Scope: runs and rides only.** The app toggle is *off / runs / rides*; walks are never auto-paused — for walks Strava computes "moving time" server-side at upload instead.
- **Manual pause wins:** "if you manually pause the app, you must resume it before continuing, even if auto-pause is enabled."
- With auto-pause off, Strava still does server-side resting-time calculation — *unless* there are manual pause events, in which case timer time is trusted as-is.
- Industry thresholds (for reference): Huawei pauses after ~10 s of being still; Garmin uses a speed threshold (~<2 km/h) plus a dwell time; Strava's classic algorithm: stay inside a small GPS radius for a dwell window → pause; leave the radius → resume.
- Known GPS caveat (theirs): if you go indoors where GPS is obscured, auto-pause can't see you stopped — manual pause recommended.

### What Wear OS / Health Services gives us (authoritative, from the 1.0.0 SDK source)
- `ExerciseConfig.isAutoPauseAndResumeEnabled` — per-exercise request flag.
- `ExerciseCapabilities.autoPauseAndResumeEnabledExercises: Set<ExerciseType>` + `ExerciseTypeCapabilities.supportsAutoPauseAndResume` — **support varies by watch hardware and sport**; we must gate on it.
- Explicit lifecycle states: `AUTO_PAUSING`(5), `AUTO_PAUSED`(6), `AUTO_RESUMING`(8) — distinct from `USER_PAUSING/USER_PAUSED/USER_RESUMING`. `isPaused` = USER_PAUSED||AUTO_PAUSED; `AUTO_*` states only occur for manually started sessions.
- **Paused time never counts toward `activeDuration`** (the SDK freezes the checkpoint while paused) → our elapsed timer, splits, and TCX totals are automatically *moving time*.
- Pause signals reach the sensor MCU with a slight (~200 ms) delay; state updates can lag a button press → always render UI from delivered state (we do).
- `overrideAutoPauseAndResumeForActiveExercise(enabled)` exists → we *can* add an in-run toggle later (not in the first cut).

## Rules for openFit (v0.6.1)

| Sport | Auto-pause | Why |
|---|---|---|
| Running | **ON** | Strava default; red lights / breaks excluded from pace |
| Cycling | **ON** | Strava default |
| Walking | **ON** | Our call — most common activity; stand-still excluded. (Strava excludes walks server-side; flip-able if the behaviour ever feels wrong.) |
| Open water | OFF | Safety: never stop tracking mid-swim; GPS is poor in water |
| Gym (StrengthTraining) | OFF | Stationary by nature |
| Pool swim | OFF | Laps/strokes; stationary is normal |

Only enabled where the device reports support for that sport (capability gate, logged at every start).

### Behavioural rules
1. Auto-pause affects the recording only — service, notification and sensors state stay alive.
2. Auto-paused → watch shows «auto-paused — resumes when you move»; notification shows «auto-paused». Movement resumes it with no input.
3. Manual pause always requires a manual resume (platform semantics, matches Strava).
4. The Resume button stays available while auto-paused (forces resume — e.g. you want stationary time to count).
5. Paused time is excluded from elapsed time; distance is untouched; pace/averages recompute on moving time; per-km splits use active time.
6. If the platform lacks support for a sport, we run without auto-pause and log it once per start (no user-facing noise).

### Verification plan (morning walk test)
1. Start a **Walk** outdoors; walk until GPS is green, then **stand still ~30 s**.
2. Expect: watch flips to «auto-paused — resumes when you move» (~10 s), notification shows «auto-paused», elapsed timer freezes.
3. Walk again → resumes automatically, timer continues.
4. Optional: manual Pause → confirm it does **not** self-resume (must tap Resume).
5. After the test I pull logcat: the capability line (`autoPause capabilities: supported=[…]`) + AUTO_PAUSING/AUTO_PAUSED/AUTO_RESUMING transitions to confirm the platform fired.

### Fallback (only if the platform's auto-pause proves unreliable on a device)
Own speed-based rule inside the service: pause when speed < 0.4 m/s sustained 8 s (GPS speed, or distance-delta/Δt), resume when > 0.8 m/s sustained 3 s (hysteresis), driving `pauseExercise()/resumeExercise()` with the same UI/notification hooks. **Not built** — decision made after the device test above.

## Sources
- support.strava.com/en-us/articles/15402141-auto-pause (Strava auto-pause rules), 15401874 (troubleshooting)
- developer.android.com/health-and-fitness/health-services/active-data (states, pause latency)
- health-services-client 1.0.0 sources: `ExerciseState.kt`, `ExerciseConfig.kt`, `ExerciseCapabilities.kt`, `ExerciseClientExtension.kt`
- Google "Testing Wear OS fitness apps" talk (device capability variance incl. auto-pause)

## Empirical: Galaxy Watch 7 capability set (2026-09-16, from logcat)
`autoPauseAndResumeEnabledExercises = [ALPINE_SKIING, BACKPACKING, BIKING, CROSS_COUNTRY_SKIING, GOLF, HIKING,
HORSE_RIDING, INLINE_SKATING, MOUNTAIN_BIKING, UNKNOWN, ORIENTEERING, PADDLING, PARA_GLIDING, ROLLER_SKATING,
ROWING, RUNNING, RUNNING_TREADMILL, SAILING, SKATING, SKIING, SNOWBOARDING, SNOWSHOEING, SURFING, WALKING, YACHTING]`

→ **Walking ✓, Running ✓, Biking ✓** are supported (our three ON sports). **Swimming (pool + open water) and
StrengthTraining are absent** — the platform doesn't offer auto-pause for them at all, which matches our OFF policy
(pool swim laps/strokes stationarity is normal; open-water = never stop recording for safety).
Verified at start: `requested=true → enabled=true for WALKING` / `… for RUNNING`.
