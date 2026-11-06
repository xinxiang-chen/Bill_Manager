#!/usr/bin/env bash
set -euo pipefail
RMS_ROOT="$(cd -- "$(dirname -- "$0")" && pwd)"
"$RMS_ROOT/build.sh"
RMS_JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$RMS_JAVA" -cp "$RMS_ROOT/out/classes:$RMS_ROOT/lib/sqlite-jdbc-3.54.1.0.jar" DemoData --reset "$RMS_ROOT/RMS.db"
