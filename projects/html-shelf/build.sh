#!/usr/bin/env bash
set -euo pipefail
gradle :app:assembleDebug --no-daemon
printf 'APK: app/build/outputs/apk/debug/app-debug.apk\n'
