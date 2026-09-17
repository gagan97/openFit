# Metric provenance — where each number comes from

So we never quietly invent data: every value shown or saved traces to one source, and that source
is documented here. All stored values are **SI/metric** (units are display-only).

| Metric | Source | Notes |
|---|---|---|
| Heart rate (bpm) | Watch optical sensor, via Health Services `HEART_RATE_BPM` (+ `HEART_RATE_BPM_STATS.average`) | We also track max ourselves from the stream for the TCX |
| Distance | `DISTANCE_TOTAL` from Health Services — GPS-derived when GPS is on, step-derived otherwise | Pool swim in water = laps × pool length (25 m) when the platform gives no distance |
| Speed / pace | Derived: active time ÷ distance (display only) | Splits likewise (per km or per mile per the units setting) |
| Elevation gain | `ELEVATION_GAIN` (barometric/GPS blend by the platform) | Recorded in TCX; not yet displayed |
| Location / route | `LOCATION` (GPS) when the sport uses GPS | Stored per trackpoint in TCX |
| **Calories** | **`CALORIES_TOTAL` from Health Services** — cumulative kcal "burned (including basal rate and activity) since the start of the exercise", computed by the platform from HR + motion + your profile | See below |
| Laps | Manual (Lap button) for all sports; platform lap summaries for pool swim | |
| Strokes | `SWIMMING_STROKES_TOTAL` (pool swim) | |

## Calories — the basis (checked, not guessed)
- We do **not** run our own formula. The number we display and write is the platform's
  `CALORIES_TOTAL` (kilocalories, cumulative since exercise start, includes basal rate), which is
  exactly the value Strava-style apps take from the OS on Wear OS.
- The platform's estimate is HR-based + activity + **user profile** (weight, height, age, sex) held
  by Samsung Health / Health Connect. **If the profile is missing or stale, calories will be off** —
  worth keeping the profile current on the phone.
- Sanity anchor: kcal ≈ MET × weight(kg) × hours for steady efforts (e.g. a 70 kg walk at ~3.5 MET
  ≈ 245 kcal/h). Our early test sessions showed 1–5 kcal for ~30–60 s of standing around, which is
  the right order of magnitude (basal + light movement).
- The TCX `<Calories>` element is defined in kcal — what we write is the same number, no conversion.
- The old "samples" row was internal bookkeeping; removed from the UI in v0.7.0.

## Display conventions
- Metric: km, /km, m. Imperial: mi, /mi, ft (elevation when added).
- Units setting affects **display only** — TCX files always carry metric values (per TCX spec) and
  Dreeve converts for its own display.
