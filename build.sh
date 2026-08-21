#!/usr/bin/env bash
# Compile the bridge into build/dist/printly.jar. Needs nothing but a JDK 21+.
#
# Third-party jars are vendored in lib/ rather than fetched: there is no Maven or
# Gradle here on purpose, and a warehouse build must not depend on the network.
set -euo pipefail
cd "$(dirname "$0")"

OUT=build
rm -rf "$OUT/classes" "$OUT/dist"
mkdir -p "$OUT/classes" "$OUT/dist/lib"

cp lib/*.jar "$OUT/dist/lib/"

# javac wants a colon-separated classpath; the jar manifest wants space-separated
# relative entries. Both are derived from lib/, so upgrading a jar needs no edit here.
CLASSPATH_ARG=$(find lib -name '*.jar' | sort | tr '\n' ':')
MANIFEST_CP=$(find lib -name '*.jar' | sort | tr '\n' ' ')

find src/main/java -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 --release 21 -Xlint:all -cp "$CLASSPATH_ARG" -d "$OUT/classes" "@$OUT/sources.txt"

# Class-Path is what makes both `java -jar` and the jpackage launcher find PDFBox.
# Without it the app starts fine and then dies on the first PDF.
printf 'Class-Path: %s\n' "$MANIFEST_CP" > "$OUT/manifest.mf"

jar --create --file "$OUT/dist/printly.jar" \
    --manifest "$OUT/manifest.mf" \
    --main-class com.jagdushah.printly.Main \
    -C "$OUT/classes" .

echo "built $OUT/dist/printly.jar"
echo "run:  java -jar $OUT/dist/printly.jar"
