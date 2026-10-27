#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT
javac -encoding UTF-8 -d "$DIR" \
  app/src/main/java/com/htmlshelf/core/SafeZipExtractor.java \
  app/src/main/java/com/htmlshelf/core/SiteHttpServer.java \
  tests/src/CoreTests.java
java -cp "$DIR" CoreTests
