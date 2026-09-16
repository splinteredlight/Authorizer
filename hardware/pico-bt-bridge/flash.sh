#!/usr/bin/env bash
# Copy a UF2 onto a Pico that is in BOOTSEL mode.
#
#   ./flash.sh                                  # authorizer-bridge for Pico W (default)
#   ./flash.sh firmware/prebuilt/Bluetooth_Unified_Keyboard_Bridge.picow.uf2   # any UF2
#
# Put the Pico in BOOTSEL mode first: unplug it, hold the BOOTSEL button, plug
# it in, keep holding until the RPI-RP2 (Pico W) or RP2350 (Pico 2 W) drive
# shows up. The script waits up to 60 s for that drive, copies the file, and
# the Pico reboots into the new firmware by itself.
set -euo pipefail
cd "$(dirname "$0")"

UF2="${1:-firmware/prebuilt/authorizer-bridge.picow.uf2}"
[ -f "$UF2" ] || { echo "no such file: $UF2" >&2; exit 1; }

find_mount() {
  for d in /media/"$USER"/RPI-RP2 /media/"$USER"/RP2350 /run/media/"$USER"/RPI-RP2 /run/media/"$USER"/RP2350; do
    [ -d "$d" ] && [ -w "$d" ] && { echo "$d"; return 0; }
  done
  return 1
}

echo "Waiting for the Pico's BOOTSEL drive (RPI-RP2 / RP2350)..."
for _ in $(seq 60); do
  if MNT=$(find_mount); then
    break
  fi
  # Drive present but not auto-mounted? Ask udisks to mount it.
  DEV=$(lsblk -rno NAME,LABEL 2>/dev/null | awk '$2=="RPI-RP2"||$2=="RP2350"{print "/dev/"$1; exit}')
  if [ -n "${DEV:-}" ] && command -v udisksctl >/dev/null; then
    udisksctl mount -b "$DEV" >/dev/null 2>&1 || true
  fi
  sleep 1
done
[ -n "${MNT:-}" ] || { echo "BOOTSEL drive not found. Hold BOOTSEL while plugging in." >&2; exit 1; }

echo "Found $MNT, copying $(basename "$UF2") ($(stat -c %s "$UF2") bytes)"
cp "$UF2" "$MNT/"
sync
echo "Done. The Pico reboots itself; the LED should start blinking slowly within a few seconds."
echo "Serial log: arduino-cli monitor -p /dev/ttyACM0 -c baudrate=115200"
