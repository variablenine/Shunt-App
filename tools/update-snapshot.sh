#!/usr/bin/env bash
# Refresh the bundled DeFlock snapshot (:solver offline fallback).
# The data is ODbL — see LICENSE-DATA.md. Run from the repo root.
set -euo pipefail

DEST="solver/src/main/resources/deflock-snapshot"
INDEX_URL="https://cdn.deflock.me/regions/index.json"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

curl -fsS "$INDEX_URL" -o "$WORK/index.json"

TILE_URL_TEMPLATE=$(python3 -c "import json;print(json.load(open('$WORK/index.json'))['tile_url'])")
mkdir -p "$WORK/tiles"

python3 -c "
import json
for key in json.load(open('$WORK/index.json'))['regions']:
    lat, lon = key.split('/')
    url = '$TILE_URL_TEMPLATE'.replace('{lat}', lat).replace('{lon}', lon)
    print(url, lat + '_' + lon)
" | while read -r url name; do
  echo "curl -fsS -o '$WORK/tiles/$name.json' '$url'"
done | xargs -P5 -I{} sh -c '{}'

rm -rf "$DEST"
mkdir -p "$DEST/tiles"
cp "$WORK/index.json" "$DEST/index.json"
for f in "$WORK"/tiles/*.json; do
  gzip -c9 "$f" > "$DEST/tiles/$(basename "$f").gz"
done

echo "Snapshot refreshed: $(du -sh "$DEST" | cut -f1) in $DEST"
