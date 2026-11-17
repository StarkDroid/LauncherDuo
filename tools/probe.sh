#!/usr/bin/env bash
# Read-only probe for Launcher Tuner.
#
# Run with the Pixel connected over USB or wireless debugging. It changes nothing:
# every command only reads. Save the output; it is the ground truth for which
# knobs this build actually exposes.
#
#   chmod +x tools/probe.sh
#   ./tools/probe.sh

set -uo pipefail

ADB="${ADB:-adb}"
if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "No device. Enable USB/wireless debugging, connect, then rerun." >&2
  exit 1
fi

section() { printf '\n== %s\n' "$1"; }

# Run a command, untruncated. Greps run remotely so long output isn't cut before
# they see it — that bug made the first version of this script lie.
run() { printf '$ %s\n' "$*"; "$@" 2>&1; }

# Suitable for dumpsys: keep the interesting head only.
runq() { printf '$ %s\n' "$*"; "$@" 2>&1 | head -25; }

section "Device"
run "$ADB" shell getprop ro.product.device
run "$ADB" shell getprop ro.build.version.release
run "$ADB" shell getprop ro.build.version.sdk
run "$ADB" shell getprop ro.build.version.security_patch
run "$ADB" shell getprop ro.build.fingerprint

section "Privileged server"
run "$ADB" shell "pm list packages | grep -iE 'shizuku|sui|dhizuku' || echo '(none installed)'"
run "$ADB" shell "ps -A | grep -i shizuku || echo '(shizuku service not running)'"
run "$ADB" shell "dumpsys package moe.shizuku.privileged.api | grep -E 'versionName|enabled=' | head -2"

section "Pixel Launcher version"
run "$ADB" shell "dumpsys package com.google.android.apps.nexuslauncher | grep -E 'versionName|versionCode' | head -3"

section "Launcher settings activity (deep-link target)"
run "$ADB" shell cmd package resolve-activity --brief \
  -n "com.google.android.apps.nexuslauncher/com.android.launcher3.settings.SettingsActivity"

section "Settings keys mentioning launcher/grid/dock"
run "$ADB" shell "settings list secure | grep -iE 'launcher|grid|dock|qs_tiles' || echo '(none)'"
run "$ADB" shell "settings list system | grep -iE 'launcher|grid|dock' || echo '(none)'"
run "$ADB" shell "settings list global | grep -iE 'launcher|grid|dock' || echo '(none)'"

section "Quick Settings tiles (current)"
run "$ADB" shell settings get secure sysui_qs_tiles

section "pm capabilities"
run "$ADB" shell "pm help 2>&1 | grep -iE 'suspend|disable-user'"
run "$ADB" shell "pm suspend 2>&1 | head -3"

section "Launcher private data boundary (expected: denied)"
run "$ADB" shell run-as com.google.android.apps.nexuslauncher ls /data/data/com.google.android.apps.nexuslauncher/shared_prefs

section "Grid control provider (expected: denied to shell)"
run "$ADB" shell content query --uri content://com.google.android.apps.nexuslauncher.grid_control
run "$ADB" shell "dumpsys package com.google.android.apps.nexuslauncher | grep -A3 'GRID_CONTROL'"

section "Overlay fabrication (expected: root only)"
run "$ADB" shell cmd overlay fabricate 2>&1

section "Installed overlays (first 20)"
run "$ADB" shell cmd overlay list | head -20

section "Home app"
run "$ADB" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
