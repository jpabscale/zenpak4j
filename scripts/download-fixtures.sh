#!/bin/sh
set -e
# Download repak/retoc test fixtures on demand (never committed).
# Uses pinned SHAs from gradle/libs.versions.toml; prefers sibling checkouts
# ../repak / ../retoc when present. Normalizes layouts so tests always sit at
#   build/fixtures/<tool>/tests/
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
REPAK_SHA=$(grep repak-upstream-sha "$ROOT/gradle/libs.versions.toml" | cut -d'"' -f2)
RETOC_SHA=$(grep retoc-upstream-sha "$ROOT/gradle/libs.versions.toml" | cut -d'"' -f2)
DEST="$ROOT/build/fixtures"
mkdir -p "$DEST"
echo "Pinned SHAs: repak=$REPAK_SHA retoc=$RETOC_SHA"

normalize() {
  # $1 = <DEST>/<tool>; some tarballs nest tests under the crate dir (<root>/<tool>/<tool>)
  if [ -d "$1/$1/tests" ] && [ ! -e "$1/tests" ]; then
    ln -s "$tool/tests" "$1/tests"
  fi
}

fetch_tool() {
  tool=$1; sha=$2
  dest="$DEST/$tool"
  mkdir -p "$dest"
  if [ -d "$ROOT/../$tool/$tool/tests" ]; then
    echo "Using sibling ../$tool fixtures"
    rm -rf "$dest"
    ln -sfn "$ROOT/../$tool" "$dest"
  else
    echo "Downloading $tool fixtures from trumank/$tool@$sha..."
    curl -fsSL "https://github.com/trumank/$tool/archive/${sha}.tar.gz" | tar -xz -C "$dest" --strip-components=1
    normalize "$dest"
  fi
}

fetch_tool repak "$REPAK_SHA"
fetch_tool retoc "$RETOC_SHA"

ok=0
for t in repak retoc; do
  if [ -e "$DEST/$t/$t/tests" ] || [ -e "$DEST/$t/tests" ]; then
    ok=$((ok + 1))
  else
    echo "missing: $t fixtures under $DEST" >&2
  fi
done
[ "$ok" -eq 2 ] || exit 1
echo "Fixtures ready at $DEST"
