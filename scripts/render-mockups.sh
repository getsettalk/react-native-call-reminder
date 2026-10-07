#!/usr/bin/env bash
# Renders the README mockups (docs/mockups/*.html) to PNGs in docs/images/
# with headless Chrome. Usage: scripts/render-mockups.sh [name ...]
set -euo pipefail
cd "$(dirname "$0")/.."
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
# name:width:height (CSS px; rendered at 2x)
SHOTS=(android:1100:900 ios:780:900 customize:1100:960 flow:1240:410 diagnostics:1160:640)
want=("$@")
for spec in "${SHOTS[@]}"; do
  IFS=: read -r name w h <<<"$spec"
  if [ ${#want[@]} -gt 0 ] && [[ ! " ${want[*]} " =~ " ${name} " ]]; then continue; fi
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=2 \
    --window-size="$w,$h" --screenshot="$PWD/docs/images/$name.png" \
    "file://$PWD/docs/mockups/$name.html" >/dev/null 2>&1
  echo "docs/images/$name.png"
done
