#!/usr/bin/env bash
# Compiles the mod's version-independent core (+ its tests) with --release 17 against Gson + slf4j
# (the jars Minecraft ships) and runs the test harness. Usage: apps/fabric-mod/tools/test-core.sh
#
# Override the locations with JAVA_HOME, GSON_JAR, SLF4J_JAR (or MC_LIBS = a Minecraft libraries folder).
set -euo pipefail

MOD="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
MC_LIBS="${MC_LIBS:-/home/claude/mc/libraries}"
GSON_JAR="${GSON_JAR:-$(ls "$MC_LIBS"/com/google/code/gson/gson/*/gson-*.jar 2>/dev/null | sort -V | tail -n 1)}"
SLF4J_JAR="${SLF4J_JAR:-$(ls "$MC_LIBS"/org/slf4j/slf4j-api/*/slf4j-api-*.jar 2>/dev/null | sort -V | tail -n 1)}"

for f in "$JAVA_HOME/bin/javac" "$GSON_JAR" "$SLF4J_JAR"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; exit 2; }
done

OUT="$MOD/build/core-test"
rm -rf "$OUT"
mkdir -p "$OUT/main" "$OUT/test"
CP="$GSON_JAR:$SLF4J_JAR"

echo "javac --release 17: main"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -Xlint:all,-classfile -Werror \
  -cp "$CP" -d "$OUT/main" $(find "$MOD/src/main/java/dev/lab43/mcplanner/core" -name '*.java')

echo "javac --release 17: test"
"$JAVA_HOME/bin/javac" --release 17 -encoding UTF-8 -Xlint:all,-classfile \
  -cp "$CP:$OUT/main" -d "$OUT/test" $(find "$MOD/src/test/java/dev/lab43/mcplanner/core" -name '*.java')

echo "running tests"
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$CP:$OUT/main:$OUT/test:$MOD/src/test/resources" dev.lab43.mcplanner.core.TestMain
