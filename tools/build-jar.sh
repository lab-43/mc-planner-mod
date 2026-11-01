#!/usr/bin/env bash
# Builds the mod jar for Minecraft 26.2 WITHOUT Gradle: javac against the jars of a Minecraft install, then zip.
# Minecraft 26.x is not obfuscated and the mod uses no mixins, so this jar is the same as Loom's output.
# (The normal build is Gradle — `./gradlew build` — this script is for machines without access to Fabric's maven.)
#
#   MC_CP=<classpath: client jar + libraries + Fabric API module jars + Mod Menu>  tools/build-jar.sh [version]
#   or MC_CP_FILE=<file with that classpath>
set -euo pipefail
MOD="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
VERSION="${1:-$(grep '^mod_version=' "$MOD/gradle.properties" | cut -d= -f2)}"
MC="${MC_VERSION:-$(grep '^minecraft_version=' "$MOD/gradle.properties" | cut -d= -f2)}"
CP="${MC_CP:-$(cat "${MC_CP_FILE:-/home/claude/mc/cp.txt}")}"

OUT="$MOD/build/local"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$MOD/build/libs"

echo "javac --release 25 (Minecraft $MC)"
"$JAVA_HOME/bin/javac" --release 25 -encoding UTF-8 -Xlint:all,-classfile,-processing,-serial -proc:none \
  -cp "$CP" -d "$OUT/classes" $(find "$MOD/src/main/java" -name '*.java')

cp -r "$MOD/src/main/resources/." "$OUT/classes/"
sed -i "s/\${version}/$VERSION/g" "$OUT/classes/fabric.mod.json"
[ -f "$MOD/LICENSE" ] && cp "$MOD/LICENSE" "$OUT/classes/LICENSE_mcplanner" || true

JAR="$MOD/build/libs/mcplanner-$VERSION+mc$MC.jar"
rm -f "$JAR"
( cd "$OUT/classes" && "$JAVA_HOME/bin/jar" --create --file "$JAR" --manifest=<(printf 'Fabric-Mapping-Namespace: official\nFabric-Minecraft-Version: %s\nImplementation-Version: %s\n' "$MC" "$VERSION") . )
echo "built $JAR ($(du -h "$JAR" | cut -f1))"
