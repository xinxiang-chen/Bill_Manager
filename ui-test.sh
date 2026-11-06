#!/usr/bin/env bash
set -euo pipefail
RMS_ROOT="$(cd -- "$(dirname -- "$0")" && pwd)"
"$RMS_ROOT/build.sh"
RMS_JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
RMS_JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
mkdir -p "$RMS_ROOT/out/test-classes"
"$RMS_JAVAC" --release 17 -encoding UTF-8 -Xlint:all -cp "$RMS_ROOT/out/classes:$RMS_ROOT/lib/sqlite-jdbc-3.54.1.0.jar" -d "$RMS_ROOT/out/test-classes" "$RMS_ROOT/tests/UiSmokeTests.java"
exec "$RMS_JAVA" -ea -cp "$RMS_ROOT/out/test-classes:$RMS_ROOT/out/classes:$RMS_ROOT/lib/sqlite-jdbc-3.54.1.0.jar" UiSmokeTests
