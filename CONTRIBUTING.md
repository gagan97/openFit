# Contributing to openFit

Thanks for wanting to help! A few ground rules keep this project easy to work on.

## Getting set up
- JDK 17 + Android SDK 34. Build: `./gradlew :wearApp:assembleDebug :phoneApp:assembleDebug`.
- A Wear OS watch (or emulator) is needed for anything UI-related; the watch app is the main product.
- `local.properties` (SDK path) and `dist/` (built APKs) are gitignored — never commit builds.

## Module map
- `wearApp/` — the Wear OS app: recording service (`ExerciseService`), UI (`MainActivity`,
  `SettingsScreen`), storage/prefs (`WearPrefs`), TCX writer/reader, uploader, sync queue.
- `phoneApp/` — optional Android app: Data-Layer receiver, local store + uploads, osmdroid map view.
- `docs/design/` — the *why* behind behaviour (auto-pause rules, metric provenance, feature matrix).
  **If you change behaviour that a design doc describes, update the doc in the same PR.**
- `docs/ROADMAP.md` — planned work; pick from there or propose something.

## Coding conventions
- Kotlin official style (`kotlin.code.style=official`), Compose for UI.
- **Round-screen safety**: the watch display is a 480 px circle. Anything you add must keep all
  corners of its bounding box inside the circle — the project verifies this by parsing UI dumps
  (see `docs/testing.md`). The standard control row is 44 dp circular icon buttons, 18 dp gaps,
  52 dp bottom spacer, 26 dp horizontal insets.
- **No personal data**: never commit real server URLs, API keys, device names/serials, health data
  or location traces (including in screenshots). Use `example.com` style placeholders.
- Keep the review gate intact: nothing may upload without an explicit user confirmation, and files
  must survive failed uploads indefinitely.

## Testing
- On-device test recipes (pairing, install, dump-driven automation, common gotchas) are in
  `docs/testing.md`. Please test on a real watch when you change recording paths; for pure UI work
  a preview/screenshot is acceptable if noted in the PR.
- Include the exact steps you ran in the PR description ("what I did / what I saw"), plus device
  and OS version. Honest "not tested on hardware" is fine — silent assumptions are not.

## PRs
- One logical change per PR; small beats clever.
- Update `docs/ROADMAP.md` / design docs when behaviour or direction changes.
- By contributing you agree your work is licensed under the repo's MIT license.
