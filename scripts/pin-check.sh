#!/bin/sh
set -e
# Check pinned upstream SHAs vs current remote HEADs
REPO_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
REPAK_PIN=$(grep repak-upstream-sha "$REPO_ROOT/gradle/libs.versions.toml" | cut -d'"' -f2)
RETOC_PIN=$(grep retoc-upstream-sha "$REPO_ROOT/gradle/libs.versions.toml" | cut -d'"' -f2)
echo "Pinned repak upstream: $REPAK_PIN"
echo "Pinned retoc upstream: $RETOC_PIN"
echo "Fetching trumank/repak master..."
REPAK_HEAD=$(git ls-remote https://github.com/trumank/repak refs/heads/master | cut -f1)
echo "Remote repak HEAD: $REPAK_HEAD"
if [ "$REPAK_PIN" != "$REPAK_HEAD" ]; then
  echo "repak pin out of date! Diff:"
  git fetch --depth 100 https://github.com/trumank/repak master 2>/dev/null || true
  git log --oneline "$REPAK_PIN".."$REPAK_HEAD" 2>/dev/null | head -20 || echo "(fetch needed)"
else
  echo "repak pin up to date."
fi
echo "Fetching trumank/retoc master..."
RETOC_HEAD=$(git ls-remote https://github.com/trumank/retoc refs/heads/master | cut -f1)
echo "Remote retoc HEAD: $RETOC_HEAD"
if [ "$RETOC_PIN" != "$RETOC_HEAD" ]; then
  echo "retoc pin out of date! Diff:"
  git fetch --depth 100 https://github.com/trumank/retoc master 2>/dev/null || true
  git log --oneline "$RETOC_PIN".."$RETOC_HEAD" 2>/dev/null | head -20 || echo "(fetch needed)"
else
  echo "retoc pin up to date."
fi
