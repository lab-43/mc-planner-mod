#!/usr/bin/env bash
# Runs the Minecraft-side tests that need no running game (geometry of the overlay, HUD arrows) against the 26.2 jars.
#   MC_CP=<classpath> or MC_CP_FILE=<file>  tools/test-mc.sh
set -euo pipefail
MOD="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
[ -x "$JAVA_HOME/bin/javac" ] && "$JAVA_HOME/bin/javac" --release 25 -version >/dev/null 2>&1 || JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
CP="${MC_CP:-$(cat "${MC_CP_FILE:-/home/claude/mc/cp.txt}")}"
OUT="$MOD/build/mc-test"
rm -rf "$OUT" && mkdir -p "$OUT"
"$JAVA_HOME/bin/javac" --release 25 -encoding UTF-8 -proc:none -cp "$CP" -d "$OUT" \
  $(find "$MOD/src/main/java" -name '*.java') $(find "$MOD/src/test/java/dev/lab43/mcplanner/mc" -name '*.java')
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP:$OUT" dev.lab43.mcplanner.mc.render.MeshTest
