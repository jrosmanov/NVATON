#!/usr/bin/env bash
# Starts the Android emulator with the Mac's webcam as the phone camera and the Mac microphone
# switched on (the emulator mutes the host microphone unless -allow-host-audio is passed).
#
#   scripts/emulator.sh                 # first AVD in the list
#   scripts/emulator.sh Medium_Phone_API_36.1 -no-snapshot-load
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
EMU="$SDK/emulator/emulator"

if [[ $# -gt 0 && "$1" != -* ]]; then
  AVD="$1"
  shift
else
  AVD="$("$EMU" -list-avds | head -n 1)"
fi
if [[ -z "$AVD" ]]; then
  echo "No AVD found. Create one in Android Studio → Device Manager." >&2
  exit 1
fi

# The emulator numbers webcams in the order macOS reports them, and an iPhone (Continuity Camera)
# or a virtual camera can come first. Pick the Mac's own camera by its unique ID.
mac_cam_id="$(system_profiler SPCameraDataType 2>/dev/null | awk -F': ' '/Unique ID/ { print $2; exit }')"
cam="$("$EMU" -webcam-list 2>/dev/null | awk -F"'" -v id="$mac_cam_id" 'id != "" && $4 == id { print $2; exit }')"
cam="${cam:-webcam0}"

echo "AVD: $AVD · camera: $cam · Mac microphone: on"
exec "$EMU" -avd "$AVD" -camera-back "$cam" -camera-front "$cam" -allow-host-audio "$@"
