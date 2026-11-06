#!/usr/bin/env bash
set -euo pipefail
RMS_ROOT="$(cd -- "$(dirname -- "$0")" && pwd)"
cd "$RMS_ROOT"
RMS_JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
RMS_JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
RMS_DRIVER="$RMS_ROOT/lib/sqlite-jdbc-3.54.1.0.jar"
if [[ ! -f "$RMS_DRIVER" ]]; then
  echo "Missing SQLite JDBC driver: $RMS_DRIVER" >&2
  exit 1
fi
mkdir -p out
rm -rf out/classes
mkdir -p out/classes
"$RMS_JAVAC" --release 17 -encoding UTF-8 -Xlint:all -d out/classes src/*.java
RMS_PACKAGE="$(mktemp -d "$RMS_ROOT/out/package.XXXXXX")"
trap 'rm -rf "$RMS_PACKAGE"' EXIT
(
  cd "$RMS_PACKAGE"
  "$RMS_JAR" xf "$RMS_DRIVER"
)
rm -f "$RMS_PACKAGE/META-INF/MANIFEST.MF" "$RMS_PACKAGE/META-INF/INDEX.LIST"
rm -f "$RMS_PACKAGE"/META-INF/*.SF "$RMS_PACKAGE"/META-INF/*.RSA "$RMS_PACKAGE"/META-INF/*.DSA
find "$RMS_PACKAGE" -name module-info.class -delete
cp -R out/classes/. "$RMS_PACKAGE/"
"$RMS_JAR" --create --file out/Bill_Manager.jar --main-class MainWindow -C "$RMS_PACKAGE" .
echo "Built: $RMS_ROOT/out/Bill_Manager.jar"
