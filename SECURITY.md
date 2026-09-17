# Security

## Reporting a vulnerability
Please use GitHub's **private vulnerability reporting** (Security → *Report a vulnerability* on this
repository) rather than a public issue. Include steps to reproduce and what you expected to happen.
You'll get an acknowledgement, and fixes are released as soon as reasonably possible.

## What this app handles
- **Health data** (heart rate, location tracks, activity files) — recorded on the watch, stored
  locally, and uploaded **only** to the server URL the user configures. Nothing is sent anywhere else.
- **A server API key** — stored in the app's private `SharedPreferences` on the watch/phone. It is
  never written to activity files or logs.

## Operator notes (self-hosting)
- The upload endpoint is protected only by its Bearer key. Terminate TLS in front of your stats
  server and keep the key secret; rotate it if you ever paste it somewhere it doesn't belong.
- The watch app ships as a **debug-signed build** for sideloading — that's fine for personal use,
  but don't treat the APK signature as a trust boundary for anything sensitive.
- Uploaded TCX files contain your GPS tracks. If that matters to you, host the server where you're
  comfortable storing that data.
