#!/usr/bin/env bash
# Compiles + runs the SmokeTest in src/test/kotlin against the live
# Idlix API. The provider Kotlin source itself depends on the Cloudstream
# Android stub (not available on plain JVM), so the smoke test is
# deliberately self-contained: it only reuses IdlixModels.kt and mirrors
# the wire flow with OkHttp + Jackson directly.
#
# Usage:  IdlixProvider/src/test/runSmokeTest.sh
set -euo pipefail

# IdlixProvider/ module root, regardless of where the script is invoked from.
MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB_DIR="$MODULE_DIR/build/smoketest-libs"
OUT_DIR="$MODULE_DIR/build/smoketest-classes"
SRC_DIR="$MODULE_DIR/src/test/kotlin"
MODELS="$MODULE_DIR/src/main/kotlin/com/idlix/IdlixModels.kt"
SMOKE="$SRC_DIR/com/idlix/SmokeTest.kt"

CENTRAL="https://repo1.maven.org/maven2"

# (groupPath:artifact:version) — must be a self-contained dependency set.
JARS=(
  "com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar"
  "com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar"
  "org/jetbrains/kotlin/kotlin-stdlib/1.9.22/kotlin-stdlib-1.9.22.jar"
  "org/jetbrains/kotlin/kotlin-reflect/1.9.22/kotlin-reflect-1.9.22.jar"
  "com/fasterxml/jackson/core/jackson-core/2.13.1/jackson-core-2.13.1.jar"
  "com/fasterxml/jackson/core/jackson-annotations/2.13.1/jackson-annotations-2.13.1.jar"
  "com/fasterxml/jackson/core/jackson-databind/2.13.1/jackson-databind-2.13.1.jar"
  "com/fasterxml/jackson/module/jackson-module-kotlin/2.13.1/jackson-module-kotlin-2.13.1.jar"
)

mkdir -p "$LIB_DIR" "$OUT_DIR"

echo "==> resolving dependencies into $LIB_DIR"
for path in "${JARS[@]}"; do
  jar_name="$(basename "$path")"
  if [[ ! -f "$LIB_DIR/$jar_name" ]]; then
    echo "    -> downloading $jar_name"
    curl --fail --silent --show-error --location "$CENTRAL/$path" -o "$LIB_DIR/$jar_name"
  fi
done

CLASSPATH=""
for jar in "$LIB_DIR"/*.jar; do
  CLASSPATH="$CLASSPATH:$jar"
done
CLASSPATH="${CLASSPATH#:}"

echo "==> compiling SmokeTest.kt + IdlixModels.kt"
kotlinc \
  -d "$OUT_DIR" \
  -classpath "$CLASSPATH" \
  -jvm-target 17 \
  -nowarn \
  "$MODELS" \
  "$SMOKE"

echo "==> running com.idlix.SmokeTest"
exec java -cp "$OUT_DIR:$CLASSPATH" com.idlix.SmokeTest
