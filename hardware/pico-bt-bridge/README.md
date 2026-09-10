# Pico Bluetooth-to-USB keyboard bridge

A Raspberry Pi Pico W (or Pico 2 W) that pairs to the phone over Bluetooth
Classic and shows up on any computer as a plain wired USB keyboard. It lets
Authorizer's **Bluetooth** auto-type reach machines with no Bluetooth, and it
is the only way to get USB-style auto-type from a phone that is **not rooted**
(the USB HID gadget path in `doc/HID_SETUP.md` needs root once; Bluetooth HID
is a public Android API and needs nothing).

```
 phone ──Bluetooth Classic HID──▶ Pico W ──USB HID keyboard──▶ any PC / BIOS / KVM
 (Authorizer, BluetoothHidDevice)  (this firmware)             (no driver, no pairing)
```

Status (2026-09-10): firmware written and compiled, **not yet run on hardware**.
Pico W ordered; a rigid USB-A-male to micro-USB-male adapter is on the way
so the board can live in a printed case as a "dongle".

## Directory

| Path | What |
|---|---|
| `flash.sh` | Waits for the Pico's BOOTSEL drive and copies a UF2 onto it |
| `firmware/prebuilt/authorizer-bridge.picow.uf2` | **The one to flash tomorrow** (Pico W) |
| `firmware/prebuilt/authorizer-bridge.pico2w.uf2` | Same firmware for a Pico 2 W |
| `firmware/prebuilt/Bluetooth_Unified_Keyboard_Bridge.*.uf2` | Adafruit's unmodified bridge, fallback / sanity check |
| `firmware/prebuilt/SHA256SUMS` | Checksums of the above |
| `firmware/authorizer-bridge/` | Our sketch (`.ino` + `usb_kbd.cpp/.h`) |
| `firmware/src/` | Adafruit's original sketch and its MIT licence, unmodified, for reference |
| `firmware/build.sh` | Rebuilds either variant with arduino-cli |

Everything under `firmware/build/` is ignored by git.

## Tomorrow: flash and test

1. **Flash.** Unplug the Pico, hold BOOTSEL, plug it into the PC, keep holding
   until a drive called `RPI-RP2` appears. Then:

   ```sh
   cd hardware/pico-bt-bridge
   ./flash.sh                      # copies firmware/prebuilt/authorizer-bridge.picow.uf2
   ```

   The Pico reboots itself. Within a few seconds the green LED blinks slowly:
   Bluetooth is up and it is waiting for the phone. It also appears to the PC
   as a keyboard plus a serial port right away.

2. **Watch the log** (optional but do it for the first pairing):

   ```sh
   ~/.local/bin/arduino-cli monitor -p /dev/ttyACM0 -c baudrate=115200
   ```

   First lines should be `Bluetooth up, address xx:xx:..., name "Authorizer Bridge"`.

3. **Pair from the phone** (this is the normal Authorizer flow, the Pico is
   just another "computer"):
   - Authorizer → drawer → **Bluetooth**. Make sure the *Enable Bluetooth
     feature* switch is on; the foreground-service notification appears.
   - **Start Device Scan**. `Authorizer Bridge` shows up in *Available Devices*.
   - Tap **Pair as Keyboard**. Android shows a pairing prompt; accept it. The
     Pico auto-accepts on its side ("Just Works", it has no display). The log
     prints `Incoming HID control channel`, then `interrupt`, then
     `*** Phone connected ***`, and the LED goes solid after three quick blinks.
   - The device now sits under *Paired Devices* as "Paired as keyboard".

4. **Type.** Put the cursor in a text editor on the PC. Open a record in
   Authorizer and use **Auto-Type Bluetooth → Username** (or long-press to
   pick a layout). The LED flickers on each report. Try a password with
   symbols and the Return suffix too.

5. **Unplug and replug the Pico.** It must come back to slow-blink on its
   own; then in Authorizer press **Connect** on the paired entry (or just
   auto-type, which connects first). No re-pairing should be needed: BTstack
   stores the link key in flash.

If any step fails, see *Troubleshooting* and *Things that are still unverified*.

## LED

| LED | Meaning |
|---|---|
| slow blink (~0.7 s) | waiting for the phone to connect |
| 3 fast blinks, then solid | link established |
| solid, flickers off briefly | connected, a report just went out |
| 5 fast blinks | BOOTSEL pressed: link dropped and all pairings forgotten |

**BOOTSEL while running** = forget all pairings. Then unpair on the phone
too (Authorizer → Bluetooth → device menu → Unpair) before pairing again.

## How the firmware works

`firmware/authorizer-bridge/authorizer-bridge.ino`, about 300 lines:

- Bluetooth Classic only (Android's `BluetoothHidDevice` is BR/EDR; the
  phone cannot do BLE HID, and the ESP32-S3 family cannot do Classic, which is
  why it is a Pico). The Pico is **discoverable + connectable**, name
  `Authorizer Bridge`, class of device "computer", and registers L2CAP
  services on the HID control (0x11) and interrupt (0x13) PSMs with security
  level 2, so pairing is forced on the first connection.
- Incoming L2CAP connections on those two PSMs are accepted; anything else is
  declined. Once both channels are open, each interrupt packet
  (`0xA1`, report ID 1, then the 8-byte boot report Authorizer builds in
  `KeyboardReport`) is forwarded to USB **unchanged**, modifiers plus six
  usage codes. No ASCII round trip, so the layout chosen in Authorizer is
  exactly what the PC sees. Reports without a report ID are handled too.
- `usb_kbd.cpp` subclasses the core's `Keyboard_` to expose a raw
  `sendReport` and waits up to ~20 ms for the endpoint, because the stock
  `sendReport` silently drops a report when the previous one is still in
  flight and a press/release pair from the phone is only milliseconds apart.
- A release-all report goes out whenever the link drops or BOOTSEL is
  pressed, so a key can never stay held on the host.
- Serial prints state changes and Bluetooth addresses only. `DEBUG_REPORTS`
  (off) would print usage codes; never enable it outside the bench.

Differences from Adafruit's original: theirs scans for a keyboard and
connects *to* it (so the phone would have to be made discoverable from
Android Settings every time, and the device would end up bonded without
Authorizer's "keyboard host" tag, which makes auto-type refuse it); ours
sits and waits, matching how the app is designed to pair. Theirs also maps
each key through ASCII, which loses non-US layouts.

## Rebuilding

The toolchain is installed: `arduino-cli` 1.5.1 in `~/.local/bin`, core
`rp2040:rp2040` 6.1.0 (Earle Philhower) in `~/.arduino15`. Nothing else.

```sh
cd hardware/pico-bt-bridge/firmware
./build.sh              # Pico W  -> build/authorizer-bridge-picow/authorizer-bridge.ino.uf2
./build.sh pico2w       # Pico 2 W
./build.sh picow stock  # Adafruit's sketch, for comparison
cp build/authorizer-bridge-picow/authorizer-bridge.ino.uf2 prebuilt/authorizer-bridge.picow.uf2
(cd prebuilt && sha256sum *.uf2 > SHA256SUMS)
```

Board options that matter: `ipbtstack=ipv4btcble` (Bluetooth Classic + BLE
stack; the default has no Bluetooth at all) and the default `usbstack=picosdk`
(the `Keyboard` library refuses to build against TinyUSB-Arduino).

Do not `#include "tusb.h"` in the `.ino`: TinyUSB's `hid.h` and BTstack's
`btstack_hid.h` both define `hid_report_type_t`. That is why the USB sender
lives in its own `usb_kbd.cpp`.

## Things that are still unverified

Ranked by how likely they are to bite, with the fix if they do.

1. **Android's pairing prompt.** With the Pico declaring "no input, no
   output", Android may auto-accept, show a "Pair with Authorizer Bridge?"
   dialog, or only post a notification. If nothing appears, pull down the
   shade. If pairing fails with `status 0x05` in the log, try changing
   `SSP_IO_CAPABILITY_NO_INPUT_NO_OUTPUT` to `SSP_IO_CAPABILITY_DISPLAY_YES_NO`
   in `bluetoothStart()`.
2. **The app's scan may not list the Pico** if its inquiry response lacks the
   name (the name is set before power-on so BTstack puts it in the EIR, but
   this is untested). Android still lists unnamed devices by address; the
   log prints the Pico's address at boot so you can match it.
3. **Android might send a SET_PROTOCOL or GET_REPORT on the control channel**
   and wait for a handshake. The firmware ignores control traffic. If the
   connection opens but nothing types, enable `DEBUG_REPORTS`, watch whether
   interrupt packets arrive, and if the control channel is chatty, answer
   with a HANDSHAKE(SUCCESS) byte `0x00`.
4. **Report length.** Expected 10 bytes (`A1 01` + 8). If the log shows
   packets of another size, adjust `handleInputReport()`.
5. **Reconnect after Pico power cycle.** If the phone says it cannot connect
   after a replug, the link key was not persisted; check that the core's
   `btstack_flash_bank` override is in the build (it is in 6.1.0) and press
   BOOTSEL + re-pair as a workaround.

Fallback if the custom firmware will not pair at all: flash
`Bluetooth_Unified_Keyboard_Bridge.picow.uf2` (Adafruit's). It scans for a
keyboard, so open **Android Settings → Connected devices → Pair new device**
(that makes the phone discoverable) while Authorizer's Bluetooth service is
running; the Pico finds the phone's keyboard class, connects, and Android
prompts to pair. Then in Authorizer, tap **Pair as Keyboard** on the now-bonded
entry so the app tags it as a keyboard host. This proves the radio/USB path
works even if our host-side code needs fixing.

## Security notes

- Pairing is "Just Works": anyone within range while the Pico is unpaired can
  pair to it. Treat the dongle as a trusted key, keep it paired to one phone.
  After pairing, other phones cannot inject keystrokes without the link key.
- The Pico is always discoverable. Cheap to change (`gap_discoverable_control(0)`
  after the first bond) once the flow is proven.
- Nothing typed is logged on either side. Keep it that way (see `CLAUDE.md`).

## Hardware / case

- Pico W, micro-USB. Board 21 × 51 mm, USB connector overhangs ~1.3 mm.
- Adapter: "USB A male to micro USB male adapter", rigid, straight, data
  lines wired. Measure adapter + board before drawing the case.
- Leave BOOTSEL reachable (it doubles as "forget pairings") and a light pipe
  or window for the LED, which is next to the USB connector on the Pico W.

## References

- Adafruit guide this started from: <https://learn.adafruit.com/pico-bluetooth-keyboard-bridge>
- Sketch source: <https://github.com/adafruit/Adafruit_Learning_System_Guides/tree/main/Bluetooth_Unified_Keyboard_Bridge>
- arduino-pico core (BTstack, Keyboard, BluetoothHCI): <https://github.com/earlephilhower/arduino-pico>
- BTstack API: `~/.arduino15/packages/rp2040/hardware/rp2040/6.1.0/pico-sdk/lib/btstack/src/{gap.h,l2cap.h,btstack_event.h}`
- Bluetooth HID profile 1.1, section 7.4 (transport header bytes)
- Authorizer side: `net/tjado/bluetooth/{Constants,KeyboardReport,HidDeviceApp}.java`,
  pairing flow in `BluetoothFragment.java` and `BluetoothForegroundService.pairAsKeyboard()`
