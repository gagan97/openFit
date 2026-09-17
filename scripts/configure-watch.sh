#!/usr/bin/env bash
# Configure the openFit watch app's server settings from a computer (debug builds).
#
# Usage:
#   scripts/configure-watch.sh <base-url> <api-key> [adb-serial]
#
# Example:
#   scripts/configure-watch.sh https://stats.example.com drv_xxxxxxxx <ip>:<port>
#
# The debug app stores its config in shared_prefs/openfit_wear.xml (keys: dreeve_base, dreeve_key).
# This writes that file directly via `run-as` — no typing on the watch required.
set -euo pipefail

BASE="${1:?usage: configure-watch.sh <base-url> <api-key> [adb-serial]}"
KEY="${2:?usage: configure-watch.sh <base-url> <api-key> [adb-serial]}"
SERIAL="${3:-}"
PKG="dev.openfit.debug"

if [ -n "$SERIAL" ]; then
  ADB=(adb -s "$SERIAL")
else
  ADB=(adb)
fi

XML="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name=\"dreeve_base\">${BASE}</string>
    <string name=\"dreeve_key\">${KEY}</string>
</map>"

printf '%s\n' "$XML" | "${ADB[@]}" shell "run-as ${PKG} sh -c 'mkdir -p shared_prefs && cat > shared_prefs/openfit_wear.xml'"

echo "Wrote config to ${PKG} on the watch:"
echo "  base: ${BASE}"
echo "  key : ${KEY:0:8}… (hidden)"
echo "Verify in the app: Settings → the sync status line should no longer show 'Dreeve not configured'."
