# Authorizer — Modernization Assessment (Phase 1)

Date: 2026-09-09. Branch: `modernize-2026` (fork of tejado/Authorizer @ 06e8f7d8).
Target: Pixel 9 Pro XL ("komodo"), Android 17 (API 37), Magisk root, SELinux enforcing,
stock kernel with `CONFIG_USB_CONFIGFS_F_HID=y`, HID gadget already created by hand
under `/config/usb_gadget/g1` and bound to UDC `11210000.dwc3`, node `/dev/hidg0`.

Authorizer is a fork of Jeff Harris's PasswdSafe for Android (Artistic License 2.0
code) plus Tjado Mäcke's GPL-3.0 auto-type layer. Both licenses and the
`assets/license-*.txt` attribution files must be preserved.

---

## 1(a) Build health

| Component | In repo | Required for API 37 today | Verdict |
|---|---|---|---|
| Gradle wrapper | 8.0 | 9.x (AGP 9.4 needs Gradle ≥ 9.1; current stable 9.7.1) | **Bump** |
| Android Gradle Plugin | 8.1.1 | 9.4.0 (compileSdk 37 requires AGP ≥ 9.2) | **Bump** |
| JDK | source/target 17; Gradle 8.0 cannot run on JDK 21 | JDK 17 works for AGP 9.x; JDK 21 recommended | Machine has both 17 and 21 |
| Kotlin | no Kotlin sources; only `kotlin-bom:1.8.22` for dedup | Not needed unless Kotlin files are added. libsu is Kotlin-compatible Java. | Optional |
| NDK | pinned `21.3.6528147` (2020) | r27+ for 16 KB page-size alignment (mandatory for targetSdk ≥ 35 on 16 KB devices); current r30 | **Bump** |
| CMake | `cmake_minimum_required 3.6` | CMake ≥ 3.22 (bundled 3.22.1 / 4.1.2) | Bump min version |
| SDK platform | compileSdk 34, `platforms;android-34` installed | `platforms;android-37` (minor revisions 37.0–37.2 available) | **Install** |
| Build tools | 36.0.0 installed | 37.0.0 available | Install |
| cmdline-tools | not installed | needed for `sdkmanager` | Install |
| `android.enableJetifier=true` | on | Removed in AGP 9 | **Remove** (needs all deps AndroidX-native) |
| `androidx.legacy:legacy-support-v4` / `legacy-preference-v14` | used | Dead 2018 libraries; `tools:overrideLibrary="android.support.v14.preference"` in the manifest | **Remove** |
| `android.nonTransitiveRClass=false`, `nonFinalResIds=false` | set | AGP 9 defaults flipped; both still honoured | Keep for now, migrate later |
| `org.gradle.unsafe.configuration-cache` | set | renamed `org.gradle.configuration-cache` | Rename |
| `buildDir` in `clean` task | used | Removed in Gradle 9 (`layout.buildDirectory`) | Fix |
| `Class.forName(...).newInstance()` | in `OutputUsbKeyboard` | Deprecated (fine, but replace while touching) | Cosmetic |

Empirical result of building the untouched tree with JDK 17 + Gradle 8.0 + AGP 8.1.1:
see section "Baseline build" at the end of this document.

Blockers, in order:
1. Gradle 8.0 does not run on JDK 21 (machine default). JDK 17 works for baseline only.
2. AGP 8.1.1 cannot compile against API 37. AGP 9.x drops Jetifier, so every
   `com.mikepenz:*:2.x` AAR (pre-AndroidX, 2017) has to go or be replaced.
3. NDK 21 binaries are not 16 KB-aligned; Play/Android 15+ enforce this. The native
   code is only a 300-line SHA-256 stretcher (`sha256.cpp`) — easier to drop the NDK
   entirely than keep it (see security section for why it is safe to drop).
4. `legacy-support-v4` and `legacy-preference-v14` pull the pre-AndroidX preference
   framework; the app already also depends on `androidx.preference:1.2.1`.

## 1(b) SDK and API audit

Current: `minSdk 21`, `compileSdk 34`, `targetSdk 34`. Proposed: `minSdk 26`
(matches the reference HID app; drops five ApiCompat shims), `compileSdk 37`, `targetSdk 37`.

Things that break or misbehave at targetSdk 35–37:

- **Foreground service type missing.** `BluetoothForegroundService` calls
  `startForeground` with no `android:foregroundServiceType` in the manifest and no type
  argument. Since target 34 this throws `MissingForegroundServiceTypeException`. Needs
  `connectedDevice` type plus `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission.
- **Runtime `registerReceiver` without export flag.** Seven call sites
  (`PasswdSafe`, `BluetoothFragment` ×3, `BluetoothForegroundService`,
  `FileTimeoutReceiver`, `otp/ScanActivity`) register receivers with no
  `RECEIVER_EXPORTED/NOT_EXPORTED` flag. Target 34+ throws `SecurityException` for
  non-system broadcasts. Use `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`.
- **PendingIntent mutability.** Already handled: all five sites OR in
  `ApiCompat.getPendingIntentImmutableFlag()`. No change needed.
- **Storage.** `WRITE_EXTERNAL_STORAGE`/`READ_EXTERNAL_STORAGE` are dead above API 32;
  the `file://` intent filters (`.psafe3`, `.dat`, `.ibak`) and
  `Preferences.PREF_FILE_DIR_DEF = Environment.getExternalStorageDirectory()` are the
  legacy direct-path browser. The app already supports SAF (`ACTION_OPEN_DOCUMENT`,
  `takePersistableUriPermission`, content `openInputStream`), so the legacy path only
  needs to be removed, not replaced. `USE_FINGERPRINT` permission and the
  `MULTIWINDOW_LAUNCHER` / Samsung multiwindow meta-data are dead weight.
- **Edge-to-edge.** Target 35+ forces edge-to-edge; the AppCompat activity with an
  action bar will draw under the status bar until insets are handled. Cosmetic, but
  visible on day one.
- **Alarms.** Only inexact `AlarmManager.set()` is used; no `SCHEDULE_EXACT_ALARM` needed.
- **`POST_NOTIFICATIONS`** is declared but I found no runtime request path; verify the
  expiry notification still fires on API 33+.
- **Bluetooth.** Permissions are already split for API 31+. Fine.
- **USB.** The app never uses `android.hardware.usb` for auto-type; the only USB-host
  code is the GPG-backup `USB_DEVICE_ATTACHED` receiver. Nothing to migrate.
- **Deprecated widgets.** `android.inputmethodservice.Keyboard`/`KeyboardView` in
  `PasswdSafeIME` are deprecated since API 29 but still present in API 37 — works,
  warns. `Theme.Holo` used by the two OTP activities is ugly but functional.
- **16 KB page size.** See NDK row above.

## 1(c) Dependency audit

| Dependency | In repo | Current | Notes |
|---|---|---|---|
| `org.bouncycastle:bcprov/bcpg-jdk18on` | 1.71.1 | 1.85.2 | 1.71 has CVE-2023-33201 (LDAP CertStore), CVE-2024-29857 (EC point DoS), CVE-2024-30171 (RSA timing), CVE-2024-30172 (Ed25519 loop). Only used for the GPG backup export; still bump. |
| `com.google.guava:guava` | 31.1-android | 33.7.1 | CVE-2023-2976 (temp-dir perms; Android irrelevant but flagged by scanners). |
| `com.google.code.gson:gson` | 2.9.0 | 2.14.0 | No open CVE; bump. |
| `com.github.tony19:logback-android` | 3.0.0 | 3.0.0 | Unmaintained; only routes to logcat. Replace with `android.util.Log` and delete `logback.xml`. |
| `com.mikepenz:iconics-core 2.8.1`, `material-design-iconic-typeface`, `devicon-typeface`, `materialize 1.0.0`, `fastadapter 2.0.0` | 2017 pre-AndroidX AARs | Iconics 5.x / FastAdapter 5.x (breaking API) | Jetifier-dependent → hard blocker under AGP 9. Used for icons in the record tree/list. |
| `com.github.bmelnychuk:atv` (AndroidTreeView) | 1.2.9 (JitPack) | Abandoned 2019 | Works via JitPack today; risk is JitPack availability. Keep for now. |
| `io.fotoapparat.fotoapparat:library` | 1.4.1 | Abandoned 2019 | Camera1-based QR scanning for OTP enrollment. Replace with CameraX + ZXing, or ML Kit. Medium effort. |
| `com.google.zxing:core` | 3.5.1 | 3.5.4 | Fine. |
| `com.yubico.yubikit:android/yubiotp` | 2.3.0 | 3.2.1 | Major bump; API changes in `YubiKitManager`. |
| `co.nstant.in:cbor` | 0.9 | 0.9 | Fine (FIDO). |
| `rocks.xmpp:precis` | 1.1.0 | 1.1.0 | Fine. |
| `androidx.appcompat` | **1.7.0-alpha03** | 1.8.0 | An alpha in a release build. |
| `androidx.room` | 2.5.2 | 2.8.4 | Bump; annotation processor path fine. |
| `androidx.lifecycle` | 2.6.2 | 2.11.0 | Bump. |
| `androidx.biometric` | 1.1.0 | 1.1.0 | Fine. |
| `material` | 1.9.0 | 1.14.0 | Bump. |
| `androidx.legacy:*` | 1.0.0 | — | Remove (see 1a). |
| Test: espresso 3.5.1, test 1.5.x, truth 1.1.3 | old | 3.7 / 1.7 / 1.4 | Bump. |
| Missing: `com.github.topjohnwu.libsu` | — | 6.0.0 | Add for root shell (replaces `ExecuteAsRootUtil`). |

## 1(d) HID mechanism review — the key functional change

### What the code does today

Two output classes implement `OutputInterface`:

- `OutputUsbKeyboard` ("native mode", pref `usbNativeModePref`, default off): opens
  `/dev/hidg0` with `FileOutputStream` as the app UID and writes 8-byte boot-keyboard
  reports followed by an all-zero release report. This is the right shape, and it is the
  exact report format you verified by hand. It fails on stock Android because the node is
  `root:root 0600` with SELinux label `u:object_r:device:s0`, and untrusted apps have no
  `chr_file` allow rule for that type.
- `OutputUsbKeyboardAsRoot` (default): for every keystroke batch, spawns `su`, and runs
  `printf '\x00\x00\x04...' | dd bs=8 of=/dev/hidg0`. The secret is embedded in a shell
  command line. It works only where `su` exists, adds ~100 ms per call, and (security
  section) leaks the password into the root shell's argv, Magisk's su log, and
  potentially the shell history of whatever `sh` Magisk uses.
- `ExecuteAsRootUtil` is a 2011 StackOverflow snippet driving `su` via stdin; no
  timeout, no stderr capture, swallows all errors as "root denied".
- `PasswdSafe.java:1324` (quick auto-type from the notification/IME path) hard-codes
  `Language.AppleMac_de_DE` and the root path, ignoring the language preference. Bug.
- The gadget itself is assumed to exist already, created by the separate
  "USB Gadget Tool" app (pelya-style custom kernel or configfs). Error text says so.
- The BT HID path (`OutputBluetoothKeyboard`, `net.tjado.bluetooth.*`) uses the public
  `BluetoothHidDevice` API and is unaffected.

### What the reference implementation does (Arian04/android-hid-client, Aug 2026)

1. Root shell via `com.topjohnwu.libsu` (`Shell.cmd(...).exec()`), root method detected
   by probing for `magiskpolicy` / `ksud`.
2. Gadget: finds the configfs gadget dir (`/config/usb_gadget/g1`, falls back to
   whichever has a `UDC` file), creates `functions/hid.keyboard` (protocol 1, subclass 1,
   `report_length`, `report_desc`), symlinks into `configs/b.1/`, then rebinds by
   writing "" and the UDC name to `UDC`. Its keyboard descriptor uses **report IDs**
   (9-byte reports: `0x01` + 8 bytes) plus a consumer-control collection.
3. SELinux, one live policy patch, no `setenforce 0`:
   `magiskpolicy --live 'allow appdomain device chr_file { getattr open read write }'`
4. Node ownership: `chown <appuid>:<appuid> /dev/hidgN`, `chmod 600`, then
   `chcon u:object_r:device:s0:<app categories>` where the categories come from
   `stat -c %C` of the app's data dir (MLS categories must match for `s0:cN,cM`).
5. After that the app opens `/dev/hidgN` directly as its own UID; root is used only
   for setup, never per keystroke.

### What to change in Authorizer

1. **Delete `OutputUsbKeyboardAsRoot` and `ExecuteAsRootUtil`.** Root never touches
   secrets again.
2. **Keep `OutputUsbKeyboard`'s write path** (8-byte reports, release report after each
   key). Your hand-built gadget has no report ID, so reports stay 8 bytes. Make the device
   path a preference (`/dev/hidg0` default, `/dev/hidg1` selectable, auto-detect the
   first `hidg*` whose configfs function is `hid.*` with `protocol=1`).
3. **Add a `HidGadgetSetup` (root, one-shot) class using libsu** that:
   a. verifies `/config/usb_gadget/<g>/functions/hid.*` exists, else offers to create it
      (protocol 1, subclass 1, report_length 8, standard 63-byte boot-keyboard descriptor,
      identical to what you did by hand), symlink into `configs/b.1`, rebind UDC
      (`11210000.dwc3` read from `/sys/class/udc`);
   b. runs `magiskpolicy --live "allow untrusted_app device chr_file { getattr open read write ioctl }"`
      — scoped to `untrusted_app` (this app's domain), not the wider `appdomain` the
      reference uses; falls back to `ksud sepolicy patch` if KernelSU is detected;
   c. `chown`/`chmod 600`/`chcon` the node to the app's UID and MLS categories.
   Setup is idempotent and re-runnable from Settings ("Prepare USB HID device") and is
   auto-retried once when `open()` fails with `EACCES`. It does not persist across reboot;
   a small optional Magisk-module `service.sh` (shipped in `doc/`) can re-apply it at boot.
4. **Make native mode the only USB mode** (remove `usbNativeModePref`); the UI gains a
   status line: node present / permission OK / gadget bound.
5. **Fix the hard-coded `AppleMac_de_DE`** in `PasswdSafe.java` to use the preference.
6. Add a **key-repeat guard**: write the release report even if a write throws, and
   `flush()` after each report (`FileOutputStream` is unbuffered, but make it explicit).
7. Manual test plan (adb + Termux): documented in Phase 2 alongside the code.

## 1(e) Security review

### At rest — the password database

The file format is Password Safe V3 (`org.pwsafe.lib`), i.e. the same on-disk format as
desktop pwsafe. This is real symmetric encryption, not hashing:

- KDF: `stretchedKey = SHA-256^(iter+1)(passphrase || salt)`, salt 32 bytes, `iter`
  stored in the header. **Default for new files is 2048** (`PwsFileHeaderV3.java:66`).
  That is the 2005 spec minimum; desktop pwsafe now defaults to 2048 too but users are
  told to raise it, and on a Tensor G4 2048 SHA-256 rounds is ~1 ms — no meaningful
  brute-force resistance. Recommend defaulting new files to ≥ 262 144 and offering a
  "re-key with more iterations" action for existing files. The format caps `iter` at
  2^32 and is fully interoperable with desktop pwsafe at any value.
- Password check: `SHA-256(stretchedKey)` stored in header, compared with `Arrays.equals`
  (not constant-time; irrelevant for an offline file).
- Data keys K and L: two random 32-byte keys, each wrapped with Twofish-ECB under the
  stretched key. Records: Twofish-256-CBC, random IV. Integrity: HMAC-SHA-256 over all
  field data under L. This is sound and matches the reference spec in `doc/formatV3.txt`.
- Twofish and HMAC come from the in-tree Java implementations, not BouncyCastle.
- Native SHA-256 (`sha256.cpp`, JNI) exists only to speed up the stretch loop and burn the
  stack afterwards. `MessageDigest.getInstance("SHA-256")` on Android is Conscrypt/BoringSSL
  and is faster than this C++; dropping the NDK loses nothing but stack-burning, which
  Java cannot do anyway for the byte[] intermediates. Recommend removing the NDK build.
- OTP secrets and FIDO private keys live inside records in the same encrypted file
  (`Token` stores an `otpauth://` URI in the password field; `PasswdSafeCredentialBackend`
  stores `pub:priv` base64 in a record under a dedicated group). Good — nothing secret is
  stored in SharedPreferences or Room in plaintext.

### At rest — the "saved password" (biometric unlock) feature

`SavedPasswordsMgr`: master password encrypted with an AndroidKeyStore AES-256-CBC-PKCS7
key, `setUserAuthenticationRequired(true)`, IV + ciphertext in Room table
`saved_passwords`. On a Pixel 9 the key lives in the Titan M2 / StrongBox-capable
keystore, so an unlocked bootloader does not let an attacker export it; they would still
need your biometric. Weaknesses:
- No `setInvalidatedByBiometricEnrollment(true)` — a new fingerprint enrolled by an
  attacker with your PIN can unlock the key. Should be true.
- No `setIsStrongBoxBacked(true)` attempt. Pixel supports it; use with fallback.
- CBC without authentication; swap to AES-GCM (`BLOCK_MODE_GCM`, `ENCRYPTION_PADDING_NONE`).
  Existing saved passwords will need re-enrolment (one-time prompt).
- `setUserAuthenticationValidityDurationSeconds` not set → key requires auth per use
  (the strict option; keep).

### At rest — FIDO client PIN

`webauthn/util/ClientPinLocker` stores `SHA-256(PIN)` and the PIN token in the
`webauthn` SharedPreferences file. Plain unsalted SHA-256 of a 4–8 digit PIN is
brute-forceable in milliseconds by anyone who reads `/data/data/.../shared_prefs`
(trivial with an unlocked bootloader + root). This is the one place "hashing where
encryption is required" applies in the sense you meant: the PIN check should be a
Keystore-bound HMAC or the PIN token should be encrypted under a Keystore key. Low
blast radius (FIDO2 client-PIN feature only), but should be fixed.

### In memory

- `PwsPassword` holds the master password as `char[]` and zeroes it on `close()`; the
  file keeps `stretchedPassword` and `decryptedHmacKey` as `byte[]` and zeroes them on
  `dispose()`. Good.
- `InMemoryKey` is an obfuscation trick (random buffer + index table); it does not
  protect against a memory dump, only against naive string scanning. Harmless.
- **Records are decrypted to `String`** (`PasswdFileData.getPassword` returns `String`;
  `PasswdSafeRecordBasicFragment.getPassword()` likewise). Java strings are immutable and
  interned into the heap until GC; the code calls `Runtime.gc()` in several places as a
  hopeful mitigation. Realistically unfixable without a rewrite of the record layer;
  note it, do not chase it.
- **Password in root shell argv** (`OutputUsbKeyboardAsRoot`): the full password, as
  `\xNN` escapes, is passed to `printf` inside `su -c`-style stdin. Magisk logs su
  requests (`/data/adb/magisk.log` and the Magisk app's superuser log, which records
  the command for the "Log" toggle); `dd` and `printf` show it in `/proc/<pid>/cmdline`
  for the duration. This is the most concrete leak in the app and is eliminated by 1(d).
- **Debug logging**: `Utilities.dbginfo` logs every typed character and scancode
  (`'x' > 0000...`) at `Log.i` when `BuildConfig.DEBUG`. Release builds are safe;
  debug builds leak the full password to logcat. Guard with an explicit
  `LOG_KEYSTROKES` flag defaulting to false even in debug.
- `FLAG_SECURE` is applied (screenshot protection, pref-controlled). Clipboard copy uses
  `ClipData` without `EXTRA_IS_SENSITIVE` (API 33+ hides it from clipboard preview);
  one-line fix.

### Unlocked bootloader implications

With an unlocked bootloader, an attacker with the phone can boot a custom recovery and
read userdata only if they also defeat file-based encryption, which is still bound to
the lock screen credential and Titan M2. What they *can* do is replace `/system` or the
app APK, so runtime protections (memory, logs, root logging) matter less than usual and
**the psafe3 file's own KDF strength is the real last line** — hence the iteration
recommendation above being rated High. The Keystore-wrapped saved password is safe as
long as the lock-screen secret is.

### Summary table

| # | Finding | Severity | Fix |
|---|---|---|---|
| S1 | Password passed through root shell command line (`OutputUsbKeyboardAsRoot`) | High | Remove class; direct writes only (1d) |
| S2 | New-file KDF iterations default 2048 | High (given threat model) | Default ≥ 262 144; re-key action |
| S3 | FIDO client PIN stored as unsalted SHA-256 in SharedPreferences | Medium | Keystore-bound HMAC / encrypted token |
| S4 | Saved-password Keystore key not invalidated on new biometric enrolment; CBC not GCM; no StrongBox | Medium | KeyGenParameterSpec changes + re-enrol |
| S5 | Keystroke-level debug logging of secrets in debug builds | Low | Explicit off-by-default flag |
| S6 | BouncyCastle 1.71.1 with four CVEs | Low (GPG export only) | Bump to 1.85.2 |
| S7 | Clipboard copy without `EXTRA_IS_SENSITIVE` | Low | One line |
| S8 | Passwords held as `String` in the record layer | Low / accepted | Document |

---

## Prioritized, phased plan (Phase 2)

Each bullet is intended to be one reviewable commit or a small series. Order matters:
build first, then target, then feature, then hardening.

### P0 — Toolchain (must compile before anything else)
1. `local.properties` + install `cmdline-tools`, `platforms;android-37`, `build-tools;37.0.0`.
2. Gradle 9.7.1 wrapper, AGP 9.4.0, `gradle.properties` cleanup (drop Jetifier, rename
   configuration-cache key), fix `clean` task, JDK 21.
3. Remove NDK/CMake: delete `src/main/cpp`, make `SHA256Pws.digestN` always use
   `MessageDigest`, drop `externalNativeBuild` and `ndkVersion`. (Security-neutral, see 1e.)
4. Remove `androidx.legacy:*`, `logback-android`, bump every AndroidX/Material/Room/Gson/
   Guava/ZXing/BouncyCastle/test dependency to current.
5. Replace mikepenz Iconics 2.x/FastAdapter 2.x/materialize: either bump to Iconics 5 +
   FastAdapter 5 (API rewrite of the affected adapters) or replace the handful of icon
   usages with Material vector drawables. I recommend the drawable route: smaller, no
   Kotlin runtime pulled in, no Jetifier. Decision point — see question 2 below.
6. Yubikit 2.3 → 3.2 API migration.
7. Verify `assembleDebug` and `lint` are clean. Tag `build-green`.

### P1 — Target API 37
8. `minSdk 26`, `compileSdk 37`, `targetSdk 37`; delete `ApiCompat*` shims below 26.
9. Foreground service type + permission for the Bluetooth service.
10. `RECEIVER_NOT_EXPORTED` on all seven runtime receivers.
11. Remove legacy storage permissions, `file://` intent filters, external-storage default
    dir, `USE_FINGERPRINT`, multiwindow meta-data. Keep the SAF path.
12. Edge-to-edge insets on the main activity; `POST_NOTIFICATIONS` runtime request.
13. Fotoapparat → CameraX for OTP QR scanning (only remaining abandoned lib).

### P2 — HID rework (the functional goal)
14. Add libsu; new `net.tjado.authorizer.hid` package: `HidDeviceLocator` (find hidgN,
    map to configfs function), `HidGadgetSetup` (configfs create-if-missing, magiskpolicy /
    ksud, chown/chmod/chcon), `HidStatus` for the UI.
15. Delete `OutputUsbKeyboardAsRoot` + `ExecuteAsRootUtil`; single `OutputUsbKeyboard`
    with device-path preference, release-report guarantee, EACCES → one auto-setup retry.
16. Settings UI: device path, "Prepare USB HID device (root)" action, status display;
    remove native-mode toggle. Fix hard-coded `AppleMac_de_DE` in `PasswdSafe.java`.
17. `doc/HID_SETUP.md`: manual test procedure (adb/Termux), optional Magisk boot script,
    troubleshooting (`dmesg`, `audit2allow`-style denial reading).
18. Auto-type regression checks: username / password / both with Tab or Return delimiter /
    Return suffix / inline `{OTP}` substitution — unchanged code paths, retested.

### P3 — Security hardening
19. S2: new-file iteration default + "increase iterations" action.
20. S4: Keystore spec (GCM, invalidate-on-enrol, StrongBox with fallback), re-enrol flow.
21. S3: client-PIN storage.
22. S5, S7: logging flag, clipboard sensitivity.
23. Version bump to 0.6.0, CHANGELOG, README rewrite (drop custom-kernel text, describe
    configfs + magiskpolicy flow, keep GPL-3.0 and PasswdSafe attribution).

### Questions before Phase 2
1. **minSdk 26** OK? (Drops Android 5–7 support. Everything below 26 is shim code.)
2. **Icons**: replace mikepenz Iconics with Material vector drawables (recommended,
   visual change to a few icons) or migrate to Iconics 5 (keeps look, adds Kotlin runtime)?
3. **Bluetooth HID**: keep as-is (recommended, it is API-based and works) or defer/remove?
4. **FIDO/WebAuthn and GPG-backup features**: keep and modernize (default) or strip to
   reduce surface? They are the reason BouncyCastle, cbor, precis and Yubikit exist.
5. **Report-ID layout**: your gadget is 8-byte, no report ID. I will default to that and
   make report length configurable; confirm you don't want the reference app's 9-byte
   keyboard+consumer descriptor.

---

## Baseline build (empirical)

`JAVA_HOME=java-17 ./gradlew :authorizer:assembleDebug` on the untouched tree:
**BUILD SUCCESSFUL in 1m 55s**, `authorizer-debug.apk` 11.3 MB. AGP auto-installed
NDK 21.3.6528147 into the SDK. Gradle reported deprecated features that will fail in
Gradle 9 (see console). So the old toolchain still builds; every blocker in 1(a) is about
moving to AGP 9 / API 37, not about the tree being broken today. That gives a green
starting point to diff against after each P0 commit.
