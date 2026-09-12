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

Status (2026-09-12): **verified on a Pico W (2022 board) with an unrooted
Pixel 11 Pro XL on Android 17.** Flash, pair from Authorizer, type a
username/password with Return suffix, and reconnect after a power cycle
without re-pairing all work with the prebuilt firmware, unchanged. The one
bug found was on the app side (scan results never reached the app, fixed the
same day, see *What was found on hardware*). A rigid USB-A-male to
micro-USB-male adapter is on the way so the board can live in a printed case
as a "dongle".

## Directory

| Path | What |
|---|---|
| `flash.sh` | Waits for the Pico's BOOTSEL drive and copies a UF2 onto it |
| `firmware/prebuilt/authorizer-bridge.picow.uf2` | **The one to flash** (Pico W, tested) |
| `firmware/prebuilt/authorizer-bridge.pico2w.uf2` | Same firmware for a Pico 2 W |
| `firmware/prebuilt/Bluetooth_Unified_Keyboard_Bridge.*.uf2` | Adafruit's unmodified bridge, fallback / sanity check |
| `firmware/prebuilt/SHA256SUMS` | Checksums of the above |
| `firmware/authorizer-bridge/` | Our sketch (`.ino` + `usb_kbd.cpp/.h`) |
| `firmware/src/` | Adafruit's original sketch and its MIT licence, unmodified, for reference |
| `firmware/build.sh` | Rebuilds either variant with arduino-cli |

Everything under `firmware/build/` is ignored by git.

## Flash and test

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
   In practice you will miss the boot banner: the firmware prints it 1.5 s
   after enumeration and ModemManager holds the new port for longer than
   that. The slow blink only starts once the stack is up, so it is proof
   enough. Everything after that (pairing, connect, disconnect, BOOTSEL) is
   logged as long as something has the port open with DTR asserted;
   `arduino-cli monitor` and pyserial both do, a bare `cat` may not.

3. **Pair from the phone** (this is the normal Authorizer flow, the Pico is
   just another "computer"). **Pair only from inside Authorizer.** A device
   bonded from Android Settings shows up in the app as "Paired as Unknown"
   with no *Pair as Keyboard* option, and auto-type refuses it; if that
   happened, *Unpair* it in the app (or Forget in Settings), press BOOTSEL on
   the Pico, and start over from here.
   - Authorizer → drawer → **Bluetooth**. Make sure the *Enable Bluetooth
     feature* switch is on. Leave *FIDO* off: it is only needed for
     security-key use and keeps a foreground service with a persistent
     notification alive. In keyboard-only mode there is no notification.
   - **Start Device Scan**. The button turns into *Stop Scan* with a progress
     bar and `Authorizer Bridge` shows up in *Available Devices* within a
     few seconds.
   - Tap **Pair as Keyboard**. Android shows a pairing prompt; accept it. The
     Pico auto-accepts on its side (it has no display). The log prints
     `SSP confirmation`, then `Incoming HID control channel`, then
     `interrupt`, then `*** Phone connected ***`, and the LED goes solid
     after three quick blinks.
   - The device now sits under *Paired Devices* as "Paired as Keyboard".

4. **Type.** Put the cursor in a text editor on the PC. Open a record in
   Authorizer and use **Auto-Type Bluetooth → Username** (or long-press to
   pick a layout). With the Pico as the only paired keyboard host the tap
   sends straight away; with several, mark the Pico as default from the
   three-dot menu on its card, or pick it in the dialog each time. The LED
   flickers on each report. Try a password with symbols and the Return
   suffix too.

5. **Unplug and replug the Pico.** It must come back to slow-blink on its
   own; then in Authorizer press **Connect** on the paired entry (or just
   auto-type, which connects first). No re-pairing should be needed: BTstack
   stores the link key in flash.

If any step fails, see *What was found on hardware* below.

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

## What was found on hardware (2026-09-12)

Everything on the firmware's "unverified" list from the first draft turned
out fine on a Pico W + Pixel 11 Pro XL (Android 17, unrooted):

- Android's pairing prompt appears and the phone pairs with Secure Simple
  Pairing (the Pico logs `SSP confirmation, passkey ...`); the "no input,
  no output" capability did not need changing.
- The Pico's name is in its inquiry response; the phone lists it by name.
- Nothing on the HID control channel needed answering; interrupt reports
  arrive as expected and type correctly, including symbols and the Return
  suffix.
- The link key survives a Pico power cycle: replug, then auto-type from the
  app, no prompt.

The one real problem was in the **app**, not the firmware: since the Android
17 modernisation every Bluetooth `BroadcastReceiver` was registered with
`RECEIVER_NOT_EXPORTED`. Bluetooth broadcasts are sent by the Bluetooth
process (its own uid, not `system`), and Android silently drops such
broadcasts for non-exported receivers, so the app never saw
`ACTION_DISCOVERY_STARTED` or `ACTION_FOUND`: *Start Device Scan* appeared to
do nothing and the list stayed empty while the phone's own scanner showed the
Pico. Those receivers are now `RECEIVER_EXPORTED`; the actions are protected
broadcasts (even `adb shell am broadcast` gets a permission denial), so this
is not a spoofing surface. Symptom to recognise it if it ever regresses: the
scan button never changes to *Stop Scan*.

If pairing ever fails with `status 0x05` in the Pico log, the fallback is
still to change `SSP_IO_CAPABILITY_NO_INPUT_NO_OUTPUT` to
`SSP_IO_CAPABILITY_DISPLAY_YES_NO` in `bluetoothStart()`. Adafruit's
unmodified `Bluetooth_Unified_Keyboard_Bridge.picow.uf2` is kept as a radio /
USB sanity check; it scans for the phone instead of waiting, so the phone has
to be made discoverable from Android Settings and the resulting bond has to
be re-made from inside Authorizer anyway.

## Security notes

- Pairing is "Just Works": anyone within range while the Pico is unpaired can
  pair to it. Treat the dongle as a trusted key, keep it paired to one phone.
  After pairing, other phones cannot inject keystrokes without the link key.
- The Pico is always discoverable. Cheap to change (`gap_discoverable_control(0)`
  after the first bond) now that the flow is proven; not done yet.
- Nothing typed is logged on either side. Keep it that way (see `CLAUDE.md`).

## Hardware / case

- Pico W, micro-USB. Board 21 × 51 mm, USB connector overhangs ~1.3 mm.
- Adapter: "USB A male to micro USB male adapter", rigid, straight, data
  lines wired. Measure adapter + board before drawing the case.
- Leave BOOTSEL reachable (it doubles as "forget pairings") and a light pipe
  or window for the LED, which is next to the USB connector on the Pico W.
- **Make sure the case does not block the USB connector from seating fully.**
  A case wall or a tight adapter can leave the micro-USB plug a fraction short
  of home, which powers the board (LED lights) but gives a flaky data/power
  contact.

## Troubleshooting: connects but never types

Symptom: the Pico pairs and powers up, but auto-type does nothing. The LED
sits on slow blink or drops back to it, and on the phone the HID link reaches
*connecting* but never *connected*.

Confirm it with the phone's own view of the link (no app logs needed):

```sh
adb shell dumpsys bluetooth_manager | grep -i "<pico-name>"
# [ACL BR/EDR:N ...] = not actually connected; the app has nothing to type to
```

In the app's log the tell is `onConnectionStateChanged: ... state=1` (CONNECTING)
with no following `state=2` (CONNECTED), and `updateDeviceList: still connected
device` looping.

Causes seen on hardware, in order of likelihood:

1. **A half-seated USB cable / connector** (verified 2026-09-12). The Pico
   powers on but the link will not complete, so it reads as "connecting"
   forever. Reseat the cable firmly; if a printed case is in the way, see the
   overhang note above. This looked for a while like an app bug and was not.
2. **A stale link state on the phone** after repeated connect/disconnect
   churn: the phone holds the Pico in a transitional state, and the app then
   treats it as busy and will not start a fresh connection. Fix on the phone,
   not the Pico: toggle Bluetooth off and on, then reopen Authorizer.
   Replugging the Pico alone does not clear this.

The HID keyboard registration itself needs the app to be in the foreground
and unlocked; behind the lock screen `registerApp()` fails and nothing types
(see `CLAUDE.md`), so test with the phone unlocked and the app open.

## References

- Adafruit guide this started from: <https://learn.adafruit.com/pico-bluetooth-keyboard-bridge>
- Sketch source: <https://github.com/adafruit/Adafruit_Learning_System_Guides/tree/main/Bluetooth_Unified_Keyboard_Bridge>
- arduino-pico core (BTstack, Keyboard, BluetoothHCI): <https://github.com/earlephilhower/arduino-pico>
- BTstack API: `~/.arduino15/packages/rp2040/hardware/rp2040/6.1.0/pico-sdk/lib/btstack/src/{gap.h,l2cap.h,btstack_event.h}`
- Bluetooth HID profile 1.1, section 7.4 (transport header bytes)
- Authorizer side: `net/tjado/bluetooth/{Constants,KeyboardReport,HidDeviceApp}.java`,
  pairing flow in `BluetoothFragment.java` and `BluetoothForegroundService.pairAsKeyboard()`
