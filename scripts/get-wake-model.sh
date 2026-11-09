#!/usr/bin/env bash
# Downloads the offline wake-word model into the Android app's assets (run once after cloning).
# Vosk small Turkish model, Apache-2.0, ~37 MB download / 56 MB unpacked. There is no Azerbaijani
# Vosk model; the Turkish one hears "Əli" as "ali"/"eli", which is all the wake word needs.
set -euo pipefail

cd "$(dirname "$0")/.."
NAME="vosk-model-small-tr-0.3"
DEST="android/app/src/main/assets/wake-model"

if [[ -f "$DEST/final.mdl" ]]; then
  echo "Already installed: $DEST"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fL --progress-bar -o "$TMP/model.zip" "https://alphacephei.com/vosk/models/$NAME.zip"
unzip -q "$TMP/model.zip" -d "$TMP"
mkdir -p "$DEST"
cp -R "$TMP/$NAME/." "$DEST/"
echo "Installed $NAME in $DEST"
