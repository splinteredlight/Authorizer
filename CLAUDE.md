# CLAUDE.md

Guidance for working in this repository. Read this before touching code.

## What this is

Authorizer is a fork of Jeff Harris's PasswdSafe for Android with Tjado Mäcke's
auto-type layer: a Password Safe (`.psafe3`) manager that acts as a USB or
Bluetooth HID keyboard and types credentials into a connected computer.
This fork (`splinteredlight/Authorizer`) modernised it for Android 17 and
replaced the custom-kernel HID assumption with configfs + runtime SELinux
patching. Upstream is `tejado/Authorizer` (remote `upstream`, dormant since
2023; `git fetch upstream` and `git log master..upstream/master` show whether
that has changed). `master` is the main line; do new work on short-lived
branches off it and merge them back with a merge commit.

Licensing: PasswdSafe code is Artistic License 2.0, Authorizer additions are
GPL-3.0. Keep both headers and the `assets/license-*.txt` files intact.

## Build

- Run Gradle with JDK 21: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.
- Gradle 9.7.1, AGP 9.4.0, `compileSdk 37` (`compileSdkMinor 2`),
  `targetSdk 37`, `minSdk 26`. Java 17 source level, no Kotlin sources.
- SDK at `~/Android/Sdk` (`local.properties` is gitignored).
- `./gradlew :authorizer:assembleDebug` for a quick compile.
- **Always build and test the release too**: `./gradlew :authorizer:assembleRelease`.
  R8 runs in full mode and has already broken code that was fine in debug
  (`Class.getPackage()` returning null). Anything reflective needs a keep rule
  in `authorizer/proguard-rules.pro` or a literal.
- `./gradlew :authorizer:lintDebug` must report 0 errors. SARIF output is
  disabled because Lint 9.4 crashes writing it. `assembleRelease` also runs
  `lintVitalRelease`, which fails on API-gated theme attributes without
  `tools:targetApi`.
- Signing: `sign/sign.gradle` + `sign/keystore.jks` (gitignored). Debug and
  release share the key so `adb install -r` replaces either in place.

## Things AGP 9 changed that bite here

- Resource ids are not constants: no `switch` on `R.id.*`, use `if`.
- Jetifier is gone: no pre-AndroidX dependencies. Iconics 5 is used because
  record icons are stored by font name inside the psafe3 file.
- `android.nonTransitiveRClass=false` and `nonFinalResIds=false` are still
  set in `gradle.properties`; migrating them is a separate job.
- AndroidTreeView (`atv`) excludes `com.android.support` transitively.
- Receivers for Bluetooth broadcasts (`ACTION_FOUND`, `ACTION_STATE_CHANGED`,
  ...) must be registered `RECEIVER_EXPORTED`. They come from the Bluetooth
  process, not the system uid, so Android drops them for non-exported
  receivers; the scan then silently shows nothing. The actions are protected
  broadcasts, so exporting is not a spoofing surface. Verified on device
  2026-09-12, do not "fix" this back.

## Layout

- `authorizer/src/main/java/org/pwsafe/lib/` — Password Safe V3 file format,
  crypto (Twofish, iterated SHA-256 KDF, HMAC). Do not change on-disk format.
- `net/tjado/passwdsafe/` — UI (PasswdSafe activity, fragments, preferences).
- `net/tjado/authorizer/` — keyboard layouts (`UsbHidKbd_*`), `OutputUsbKeyboard`
  (writes 8-byte boot-keyboard reports to `/dev/hidgN` as the app's own UID),
  `OutputBluetoothKeyboard`.
- `net/tjado/authorizer/hid/` — `HidGadgetSetup` (root, one-shot, via libsu),
  `HidStatus` (non-root probe), `HidNotReadyException`.
- `net/tjado/webauthn/` — FIDO2/U2F authenticator over Bluetooth HID.
- `doc/MODERNIZATION_ASSESSMENT.md` — the 2026 audit and plan.
- `doc/UI_DESIGN.md` — the Material 3 redesign: colour roles, icons,
  components, and the traps (menu rebuild loop, preference icon space).
- `doc/HID_SETUP.md` — how the HID path works, manual test, troubleshooting.
- `doc/magisk/service.sh` — Magisk module script that re-applies HID access at boot.
- `hardware/pico-bt-bridge/` — Raspberry Pi Pico W firmware that turns the
  phone's Bluetooth auto-type into a USB keyboard on any PC (works with an
  unrooted phone). Separate toolchain, see below.

## UI rules

- One theme, `Theme.Authorizer` (Material 3 DayNight, no window action
  bar). Colour roles are generated into `values/colors_m3.xml` and
  `values-night/colors_m3.xml`; regenerate both, never hand-edit one.
  The theme preference only sets AppCompat's night mode; dynamic colour is
  a separate switch, default on.
- Icons are tinted vectors in `drawable/ic_*.xml`. No PNG icon variants,
  no `_light`/`_dark` pairs. Record icons remain Iconics fonts (names are
  stored in the file).
- The activity owns the `MaterialToolbar`. `invalidateOptionsMenu()`
  rebuilds the whole menu with a Toolbar action bar; use
  `refreshOptionsMenu()` from menu callbacks or the SearchView collapses
  the moment it opens (this happened).
- Password forms set `importantForAutofill="noExcludeDescendants"`.
- New screens follow `doc/UI_DESIGN.md`: 16 dp `screen_gutter`,
  `TextAppearance.Authorizer.Section` headings, `Authorizer.DetailRow`
  label/value pairs, `MaterialAlertDialogBuilder` for dialogs.

## HID design rules

- Root is used only in `HidGadgetSetup.prepare()`. Never spawn `su` on the
  typing path, and never put a secret in a shell command line.
- Setup picks a `hid.*` function with `protocol 1` and `report_length 8`, or
  creates `hid.authorizer`. Other tools' functions (e.g. android-hid-client's
  report-ID keyboard on `hidg0`) are never modified. The node is resolved from
  the function's `dev` attribute and persisted in `usbHidDevicePref`.
- SELinux rule: `allow <own domain> device chr_file { getattr open read write ioctl }`
  via `magiskpolicy --live` or `ksud sepolicy patch`. The domain and MLS
  categories are read from `/proc/self/attr/current`, never hard-coded.
- Reports are 8 bytes, no report ID. A release report follows every press in
  a `finally` block so a key can't stay held on the host.
- Never write reports back to back: `write()` returns as soon as the host
  polls (1 ms) and hosts drop keys at that rate. Each key is held and then
  spaced by `usbHidKeyDelayPref` (default 10 ms), and one all-keys-up report
  is sent before the first key. Typing goes through `UsbAutoType` on its
  worker thread, never on the main thread (writes block while the host is
  not polling).
- After re-binding the UDC in setup, wait for `/sys/class/udc/<udc>/state`
  to read `configured` (plus a settle delay) before reporting ready; the host
  re-enumerates and early reports are lost.
- If libsu returns a non-root shell, close it before returning, or every
  later attempt fails until the process restarts.
- `BluetoothForegroundService` must run as a foreground service whenever HID
  is active: `BluetoothHidDevice.registerApp()` fails with "app is not
  foreground" unless the process has foreground importance, and a bound-only
  service does not provide it (auto-type then silently does nothing).
  Register in `onStartCommand` *after* `startForeground`, in that order, or
  the registration races the foreground-importance update and fails on cold
  start. To avoid a persistent notification in keyboard mode, the activity
  ties the service to being in front: `startForegroundService` from a
  foreground-safe point (`onResume`, and a guarded try in the STATE_ON
  handler) and `stopService` in `onStop`, so the (LOW-importance, mandatory)
  notification exists only while the app is open. FIDO leaves the service
  running on `onStop` because it must answer with the app closed; FIDO
  defaults to off. Never call `startForegroundService` from `onStart`
  unguarded: it throws `BackgroundServiceStartNotAllowedException` behind the
  lock screen. A foreground-service notification cannot go below
  IMPORTANCE_LOW, and channel importance is locked after creation, so the
  quiet channel uses a new id (`BluetoothServiceChannelQuiet`) and deletes
  the old one.
- FIDO objects (`Authenticator`, `TransactionManager`, credential backend)
  are owned by `PasswdSafeApp`, never the activity. The app tracks two
  things separately: the resumed activity (prompt host only, null when
  paused) and the `FidoFileAccess` that holds the open file (the activity,
  until destroyed). The service answers when the file is usable or
  `FidoKeyCache.canServe()`; see `doc/FIDO_AUTOMATION.md`.
- Bluetooth connect failures: `BluetoothHidDevice` callbacks deliver a freshly
  unparceled `BluetoothDevice`, so compare with `equals`, never `==`. Android's
  `HidDeviceService` only leaves STATE_CONNECTING on a native event, and
  `disconnect()` on a link that never came up is a no-op
  ("HID_DevDisconnect returned 4"), so a target stuck in CONNECTING must be
  cleared by re-issuing `connect()` (native answers "already in progress" if
  one is genuinely in flight, otherwise pages, fails in ~5 s and delivers
  CLOSE). `HidDeviceController` does this plus a 15 s timeout backstop;
  verified on device 2026-09-12 with the Pico unpowered.
- FIDO reconnect: the phone is the HID peripheral and PCs never dial HID
  peripherals, so the service must redial. It cycles through
  `getFidoHostsByPreference()` (last connected first, no default needed)
  with backoff, and at once on `ACTION_ACL_CONNECTED`; see
  `doc/FIDO_AUTOMATION.md` step 3. Keep the loop out of keyboard mode and
  out of any pairing or auto-type in flight, or it steals the link.
- In `doc/magisk/service.sh`, call `/system/bin/stat` and `/system/bin/chcon`
  explicitly: Magisk's busybox `stat` has no `%C`.

## Pico Bluetooth bridge (`hardware/pico-bt-bridge/`)

Start with its `README.md`; it has the step-by-step for flashing and testing
and what was learned on hardware (verified 2026-09-12, Pico W + Pixel 11).

- Toolchain: `~/.local/bin/arduino-cli` with core `rp2040:rp2040` 6.1.0
  (Earle Philhower). Build with `firmware/build.sh`, flash with `flash.sh`
  (hold BOOTSEL while plugging in). Board option `ipbtstack=ipv4btcble` is
  required or there is no Bluetooth stack; USB stack stays `picosdk`.
- The Pico is the Bluetooth *host*: discoverable, accepts the phone's incoming
  HID connection, and the app pairs to it via Bluetooth → Start Device Scan →
  Pair as Keyboard. Do not make it scan for the phone; the app only auto-types
  to devices it paired itself (`BluetoothDeviceListing.HID_KEYBOARD_HOST`).
- Bluetooth Classic only: `BluetoothHidDevice` is BR/EDR, and Android reserves
  the BLE HID service, so BLE-only chips (ESP32-S3, C3, C6) cannot be used.
- Forward the 8-byte report unchanged; never map through ASCII (loses layouts).
- Never `#include "tusb.h"` in the `.ino`: it clashes with BTstack's
  `hid_report_type_t`. USB code lives in `usb_kbd.cpp`.
- Same logging rule as the app: no usage codes or text on serial. The
  `DEBUG_REPORTS` flag exists for the bench only and must stay `false` in
  committed code.
- Prebuilt UF2s are committed under `firmware/prebuilt/` with `SHA256SUMS`;
  refresh both when the sketch changes.

## Security rules

- New files use `PwsFileHeaderV3.DEFAULT_ITER` (262144) iterations. Do not lower.
- Biometric saved-password keys: AES-GCM, `setInvalidatedByBiometricEnrollment(true)`,
  StrongBox with TEE fallback. Legacy CBC keys are detected via `KeyInfo` and
  still decrypt; keep that path until users have re-saved.
- `FidoKeyCache` holds FIDO records only, never passwords. Its keystore key
  deliberately has no user authentication (pocket operation); do not add
  `setUserAuthenticationRequired` or `setUnlockedDeviceRequired` to it, and
  do not widen what it stores.
- FIDO client PIN reference is an AndroidKeyStore HMAC, compared with
  `MessageDigest.isEqual`. Never store a plain hash of the PIN.
- Never log typed characters, HID reports, usernames, or passwords, even
  behind `BuildConfig.DEBUG`.
- Clipboard copies of secrets must set `EXTRA_IS_SENSITIVE`.

## Testing on the emulator

The AVD `Medium_Phone_API_36` (`~/Android/Sdk/emulator/emulator -avd
Medium_Phone_API_36`) is enough for layout work. Grant
`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` and `POST_NOTIFICATIONS` with
`pm grant` (denying Bluetooth used to crash the app; now it just disables
HID). `adb shell cmd uimode night yes|no` switches dark mode, and
`adb exec-out screencap -p > shot.png` gives a screenshot to read.

## Testing on the device

Two phones, one per HID path:

- Pixel 9 Pro XL ("komodo"), Android 17, Magisk, SELinux enforcing: rooted,
  so it is the device for the USB HID gadget path (`adb` and root shell work).
- Pixel 11 Pro XL ("kodiak"): not rooted, so it types through the Pico
  Bluetooth bridge; use it to check the unrooted experience (no `su`, USB
  auto-type unavailable).

Both lock after 30 s, so UI automation needs them unlocked. Pick the device
with `adb -s <serial>` when both are plugged in.

```sh
adb install -r authorizer/build/outputs/apk/release/authorizer-release.apk
adb shell su -c 'ls -lZ /dev/hidg*'                                     # app-owned node with categories
adb shell su -c 'cat /data/adb/modules/authorizer-hid/last-run.log'     # boot module result
adb logcat -s HidGadgetSetup OutputUsbKeyboard AndroidRuntime:E
```

For a keystroke test without the app, build 8-byte reports on the PC,
base64 them, and write with `dd bs=8` on the device (see `doc/HID_SETUP.md`);
Android's `printf` has no `\x` and nested quoting mangles octal.

Launch the activity with the full launcher intent, or it finishes immediately:
`am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n net.tjado.passwdsafe/.PasswdSafe`.

## Conventions

- Small, single-purpose commits with a body that explains why. End commit
  messages with the `Claude-Session:` line when working in a Claude session.
- Explain each significant change so the maintainer can follow it without
  the conversation history.
- Ask before destructive or irreversible actions (uninstalling the app wipes
  its data and changes its uid; `setenforce`; touching the Magisk database).
