#!/bin/bash
# Build MediaRemote.ino for the Seeed XIAO nRF52840 Plus and convert it to a .uf2
# that can be dragged onto the board's bootloader drive.
#
# Needs: arduino-cli, the Seeed nRF52 core 1.1.13 and the Seeed_GFX2 v1.0.0 library
# (see firmware/BUILDING.md). With the Arduino IDE installed normally, just use
# Upload in the IDE instead; this script is for building a .uf2 to share.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-$HERE/build}"
CORE="${CORE:-$HOME/.arduino15/packages/Seeeduino/hardware/nrf52/1.1.13}"   # Seeed core location

arduino-cli compile --fqbn Seeeduino:nrf52:xiaonRF52840Plus \
  --build-path "$OUT" \
  --build-property "recipe.objcopy.zip.pattern=true" \
  "$@" "$HERE/MediaRemote"

python3 "$CORE/tools/uf2conv/uf2conv.py" -f 0xADA52840 -c \
  -o "$HERE/prebuilt/MediaRemote.uf2" "$OUT/MediaRemote.ino.hex"
echo "Wrote $HERE/prebuilt/MediaRemote.uf2"
