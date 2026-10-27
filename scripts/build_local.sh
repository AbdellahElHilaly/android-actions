#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/prepare.py "$@"
cd .factory-work/project
gradle --no-daemon --console=plain :app:assembleDebug
