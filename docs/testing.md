# Testing on a real device

Recipes collected while building this app. They apply to any Wear OS 3+ watch and any computer
with `adb` (Android platform-tools).

## One-time setup
1. On the watch: Settings → About watch → Software information → tap *Software version* 5× to
   unlock **Developer options**.
2. Enable **ADB debugging** and **Wireless debugging**. The watch shows an IP and a port
   (the port changes every time wireless debugging is re-enabled).
3. From the computer:
   ```bash
   adb pair <watch-ip>:<pair-port>    # type the 6-digit code shown on the watch (one time)
   adb connect <watch-ip>:<port>
   adb devices
   ```
   After pairing once, the watch trusts this computer's key — future sessions only need
   `adb connect`.

## Install / update
```bash
./gradlew :wearApp:assembleDebug
adb -s <watch-ip>:<port> install -r wearApp/build/outputs/apk/debug/wearApp-debug.apk
adb -s <watch-ip>:<port> shell dumpsys package dev.openfit.debug | grep versionName
```
`adb install` works even while the watch screen is off.

## Configuring a build without a UI
The debug app reads its server config from `SharedPreferences`; `scripts/configure-watch.sh`
writes it remotely (debug builds only, via `run-as`). The prefs file is
`shared_prefs/openfit_wear.xml` with string keys `dreeve_base` and `dreeve_key`.

## Driving the UI from a computer (dump-driven automation)
The most reliable pattern for scripted checks:
1. `adb shell input keyevent KEYCODE_WAKEUP` **before every** `uiautomator dump` — a watch in
   ambient/AOD mode otherwise reports the watch face, not your app.
2. Dump and parse: `adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml`,
   then find a node by `text="…"` or `content-desc="…"` and tap its centre
   (`adb shell input tap X Y`). Blind coordinate taps drift as layouts change.
3. While a flow runs, keep the display awake (the screen sleeps within seconds and **Wear locks on
   screen-off if a PIN is set**): a background `input tap 240 466` every 2 s on a harmless empty
   area works.
4. Screenshots: `adb exec-out screencap -p > shot.png` — retry a few times; a sleeping screen
   yields a black frame.

### Gotchas you will hit
- **A locked watch silently swallows automation**: `am start` reports success while the app opens
  *behind* the lock screen; dumps show the watch face and gestures land on the lock/lock carousel.
  Check for the lock screen (`Incorrect` / `Emergency call` text) and stop — never try to bypass a
  user lock.
- `am start` is a **no-op for reset** when the activity is already running (`launchMode=singleTask`).
  Use the in-app Cancel/Back affordances, or `am force-stop` (which also ends a recording *without*
  saving — use deliberately).
- Long flows: split them into stages (< ~5 minutes each); `python3 -u` if you redirect logs to a
  file.

## Functional checklist (release sanity)
Run through this on the watch after changes that touch recording:
1. Pre-start: sport screen shows the GPS icon going green when a fix is available (if the sport uses GPS).
2. Record ~30 s: the timer ticks **continuously** (check twice, ≥10 s apart — if it only jumps on
   pause/resume, the duration computation regressed).
3. Lap: the lap counter on the stats page increments.
4. Auto-pause: stand still ~20 s (walk/run/ride) — status flips to *auto-paused*; moving resumes it.
   Manual pause must **not** self-resume.
5. Stop → **Review** screen: **Done** (uploads) and **Delete** (with confirm) work; the activity
   file disappears on delete. `run-as dev.openfit.debug ls files/activities` shows the queue.
6. With the network off, tap Done — the file stays queued; Settings → **Sync now** uploads it once
   back online. Re-sending must not create duplicates (server-side de-dup).
7. Round-safety: dump the recording screen and verify every node's bounding-box corners are within
   the 240 px circle around (240,240) of the 480×480 display.
