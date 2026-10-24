#!/usr/bin/env bash
# Build a public-safe copy of the CI evidence dir. Allowlist only: screenshots,
# PASS/FAIL summaries, install/progress text, JUnit XML. Never json, UI dumps,
# dumpsys or raw logcat. Text is redacted, then fail-closed scanned.
# usage: publish_safe.sh SRC_DIR DEST_DIR
set -u
SRC="${1:-shots}"; DEST="${2:-shots-public}"
rm -rf "$DEST"; mkdir -p "$DEST"
REDACT='s/hrt_[A-Za-z0-9_-]+/hrt_<redacted>/g; s/AIza[0-9A-Za-z_-]{20,}/AIza<redacted>/g; s/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_.-]+/JWT_REDACTED/g; s/(ghp|gho|ghs|github_pat)_[A-Za-z0-9_]{20,}/GH_REDACTED/g; s/[A-Fa-f0-9]{40,}/HEX_REDACTED/g; s/[A-Za-z0-9_-]{48,}/LONG_REDACTED/g'
shopt -s nullglob
for f in "$SRC"/*.png "$SRC"/test-results/*.xml "$SRC"/TEST-*.xml; do cp "$f" "$DEST"/; done
for f in "$SRC"/progress.txt "$SRC"/ui3.txt "$SRC"/shared_brain.txt "$SRC"/install*.txt "$SRC"/upgrade*.txt "$SRC"/phase.txt "$SRC"/logcat_fcm_excerpt.txt "$SRC"/*summary*.txt "$SRC"/*result*.txt; do
  [ -f "$f" ] && sed -E "$REDACT" "$f" > "$DEST/$(basename "$f")"
done
[ -f "$SRC/unit-tests.html" ] && sed -E "$REDACT" "$SRC/unit-tests.html" > "$DEST/unit-tests.html"
# fail closed: drop any text file that still looks like it holds a secret
for f in "$DEST"/*.txt "$DEST"/*.xml "$DEST"/*.html; do
  [ -f "$f" ] && grep -qE 'hrt_[A-Za-z0-9]|AIza[0-9A-Za-z_-]{20,}|BEGIN [A-Z ]*PRIVATE KEY' "$f" && rm -f "$f" && echo "dropped unsafe file $(basename "$f")"
done
ls "$DEST" | wc -l
