#!/usr/bin/env bash
# Build the bridge firmware with arduino-cli and the Earle Philhower RP2040 core.
#
#   ./build.sh                # authorizer-bridge for Pico W  -> build/authorizer-bridge/*.uf2
#   ./build.sh pico2w         # same, for Pico 2 W
#   ./build.sh picow stock    # Adafruit's unmodified sketch (src/) as a reference build
#
# One-time setup (already done on this machine, kept here for a fresh box):
#   curl -fsSL https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh | BINDIR=~/.local/bin sh
#   arduino-cli config init
#   arduino-cli config add board_manager.additional_urls \
#       https://github.com/earlephilhower/arduino-pico/releases/download/global/package_rp2040_index.json
#   arduino-cli core update-index && arduino-cli core install rp2040:rp2040
set -euo pipefail
export PATH="$HOME/.local/bin:$PATH"
cd "$(dirname "$0")"

BOARD="${1:-picow}"
VARIANT="${2:-authorizer}"

case "$BOARD" in
  picow)  FQBN="rp2040:rp2040:rpipicow:ipbtstack=ipv4btcble" ;;
  pico2w) FQBN="rp2040:rp2040:rpipico2w:ipbtstack=ipv4btcble" ;;
  *) echo "board must be picow or pico2w" >&2; exit 2 ;;
esac

case "$VARIANT" in
  authorizer) SKETCH="authorizer-bridge"; OUT="build/authorizer-bridge-$BOARD" ;;
  stock)
    # arduino-cli needs the folder name to match the .ino name
    SKETCH="build/Bluetooth_Unified_Keyboard_Bridge"
    mkdir -p "$SKETCH"; cp src/Bluetooth_Unified_Keyboard_Bridge.ino "$SKETCH/"
    OUT="build/stock-$BOARD" ;;
  *) echo "variant must be authorizer or stock" >&2; exit 2 ;;
esac

arduino-cli compile -b "$FQBN" --output-dir "$OUT" --warnings default "$SKETCH"
echo
ls -l "$OUT"/*.uf2
