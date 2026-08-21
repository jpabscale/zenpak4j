#!/bin/sh
set -e
# Bootstrap gradle wrapper jar on demand (not stored in repo)
DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PROPS="$DIR/gradle/wrapper/gradle-wrapper.properties"
JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"
if [ -f "$JAR" ]; then
  echo "Wrapper jar already exists: $JAR"
  exit 0
fi
if [ ! -f "$PROPS" ]; then
  echo "Missing $PROPS"
  exit 1
fi
DIST_URL=$(grep distributionUrl "$PROPS" | cut -d'=' -f2- | sed 's/\\://g' | tr -d ' ' 2>/dev/null || true)
VER=$(echo "$DIST_URL" | sed -n 's/.*gradle-\([0-9.]*\)-.*/\1/p')
if [ -z "$VER" ]; then VER="8.13"; fi
URL="https://github.com/gradle/gradle/raw/v${VER}/gradle/wrapper/gradle-wrapper.jar"
echo "Downloading gradle-wrapper.jar $VER from $URL..."
mkdir -p "$(dirname "$JAR")"
if command -v curl >/dev/null 2>&1; then
  curl -fsSL -o "$JAR" "$URL"
elif command -v wget >/dev/null 2>&1; then
  wget -qO "$JAR" "$URL"
else
  echo "Need curl or wget"; exit 1
fi
echo "Downloaded $JAR"
ls -lh "$JAR"
