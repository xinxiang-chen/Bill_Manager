#!/usr/bin/env bash
set -euo pipefail
RMS_ROOT="$(cd -- "$(dirname -- "$0")" && pwd)"
"$RMS_ROOT/build.sh"
RMS_JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$RMS_JAVA" -Duser.language=en -Duser.country=US -Drms.database="$RMS_ROOT/RMS.db" -jar "$RMS_ROOT/out/Bill_Manager.jar"
