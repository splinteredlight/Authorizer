# FIDO automation: what is possible and what it costs

Notes from a 2026-09-12 investigation into making the Bluetooth FIDO
authenticator answer requests with fewer taps, or with none at all.
Nothing here is implemented. This is a design assessment for later.

## What FIDO mode is

Instead of pretending to be a keyboard, the app pretends to be a hardware
security key over Bluetooth HID. A browser on the paired computer sees a
FIDO2/U2F authenticator and uses it for two-factor or passwordless login.
Credentials live as fields on records inside the `.psafe3` file, so they
travel with the database.

The Bluetooth stack registers one HID SDP record at a time, so the phone is
either a keyboard or a security key, never both at once. FIDO also needs the
service to outlive the activity, which means the foreground service and its
persistent notification. See `CLAUDE.md` for the keyboard-only path.

## Where the user is asked to approve

| Operation | Gate | Code |
|---|---|---|
| Login (`getAssertion`) | Confirm dialog naming site and account | `Authenticator.java:456` |
| Register (`makeCredential`) | Confirm dialog | `Authenticator.java:253` |
| Legacy U2F | Biometric prompt | `U2FuserPresence`, `Authenticator.java:848` |
| Client PIN | None on the phone | `PINverifyClientDataHash`, `Authenticator.java:1182` |

Helpers are `showDialog` (`:1366`) and `showPrompt` (`:1330`), both of which
block the calling thread on a semaphore until the user answers.

Two things worth knowing:

- A biometric branch exists for `makeCredential` but is commented out, so
  registration today is only ever a dialog tap.
- Login already skips the dialog when `preFlight` is true, that is when the
  request does not ask for user presence. Silent probes are already silent.
- The client PIN is verified cryptographically against a token. The PIN is
  typed in the browser on the computer, never on the phone. There is no
  prompt to remove and auto-approval would not weaken it.

## Step 1: auto-approve the taps (implemented 2026-09-14)

Two preferences on the Bluetooth settings screen, both off by default and
only enabled while FIDO mode is on:

- "Approve FIDO logins without confirmation" (`fidoAutoApproveLoginPref`):
  `getAssertion` and U2F authenticate return "allowed" without the dialog.
- "Approve FIDO registrations without confirmation"
  (`fidoAutoApproveRegisterPref`): `makeCredential` and U2F register.

They are read from `SharedPreferences` on every request in
`Authenticator.autoApproveLogin/Register`, so a change applies immediately.
A toast still names the site that was answered. The original notes follow.

Replace the gate result with an automatic yes at
the three call sites above, behind a preference defaulting to off.

The signed response carries a user-presence flag that would then be set
without a gesture. A relying party cannot detect this, so sites keep
working. Hardware keys ship the same behaviour under names like
"no touch required", so there is precedent.

Suggested shape:

- One preference, "Approve security key requests automatically", off by default.
- Keep the existing post-login toast so something is still visible.
- Optionally auto-approve logins but still confirm registrations, since
  registration is rare and creates lasting state.

This alone does **not** give hands-off operation: the app must still be in
front with the file unlocked. See below.

## Step 2: answering with the app closed (implemented 2026-09-14)

Route 3 below was built, in three commits:

1. **Decoupling.** `PasswdSafeApp` owns the `Authenticator`,
   `TransactionManager` and `PasswdSafeCredentialBackend`. The backend
   reaches the file through the `FidoFileAccess` interface (implemented by
   `PasswdSafe`) instead of the activity itself, and the resumed activity is
   tracked separately and used only to host prompts. The authenticator uses
   the application context for strings and toasts, and declines a prompt
   when no activity is in front. The service gate became "file open and not
   in edit mode", so the app answers from the background until the file
   times out.
2. **`FidoKeyCache`.** A copy of the FIDO records only (never passwords) in
   app-private storage, AES-256-GCM under an AndroidKeyStore key with no
   user authentication (StrongBox, TEE fallback). Rebuilt on every file
   open, extended on registration, deleted when the preference is turned
   off. The backend reads from the file when it is usable and from the
   cache otherwise. Sign counters advance in the cache while the file is
   closed and merge back (max wins) on the next foreground use.
3. **Gate.** `BluetoothForegroundService.onInterruptData` answers when the
   file is usable or the cache can serve. Preference: "Answer FIDO requests
   with the app closed", off by default.

What that gives: unlock the file once after a reboot (the cache file lives
in credential-encrypted storage and the keystore key needs the device to
have been unlocked once) and requests are answered afterwards with the
screen off, the app swiped away, or the process restarted by Android.

Known limits:

- A login that needs the account picker (several credentials for one site
  and no allow-list) is refused with no activity in front, rather than
  answered with the first one.
- A FIDO record deleted from the file stays in the cache until the file is
  next opened.
- Registration (a new passkey) always needs the file open and writable;
  the cache serves logins only.
- With confirmations still on, a cached request is declined and the
  "action required" notification asks you to open the app.

The original notes follow.

Before this, `BluetoothForegroundService.onInterruptData` only answered when
there was a resumed activity and an open file:

```java
if (PasswdSafe.mTransactionManager != null && activity != null
        && activity.isFileOpen() && !activity.isEditMode()) {
```

`PasswdSafeApp` nulled its activity reference in `onActivityPaused`, so
backgrounding or locking the screen was enough to disable answering. With
the app closed the service could only post the "Action Required"
notification asking you to open it.

### The hard floor: the private key is in the database

`PasswdSafeCredentialBackend.generateNewES256KeyPairLocal()` generates the
ES256 key in software. Its own comment says:

> WARNING: this generates the key locally and not in Android Keystore.

The key is then stored as a record field, `PwsRecordV3.FIDO_KEY_PAIR`, via
`fileData.setFidoKeyPair(...)`. So the private key exists in plaintext only
while the database is decrypted in memory. **No open file, no key, no
signature.** This cannot be coded around.

The resumed-activity requirement, by contrast, is incidental. It exists
because prompts need a `FragmentActivity`, which auto-approval removes, and
because the credential backend reaches the file through
`activity.useFileData(...)`, where the data lives in a retained fragment.
Both are refactors, not obstacles.

### Three routes to pocket operation

1. **Keep the file open, move ownership out of the activity.**
   Shift the decrypted `PasswdFileData` to service or application scope and
   disable the close-on-screen-off and timeout behaviour in
   `FileTimeoutReceiver`. The service then answers whenever a request
   arrives.
   *Cost:* the entire decrypted database, every password in it, sits in
   memory indefinitely. That is exactly what the timeout and screen-off lock
   exist to prevent. Moderate refactor, the file data is in a retained
   fragment today.

2. **Move FIDO keys into AndroidKeyStore.**
   Signing needs no open file at all, and keys become hardware-backed and
   non-exportable.
   *Cost:* breaks the property that credentials travel inside the psafe3
   file. Keystore keys cannot be backed up or moved to a new phone, so
   losing the phone loses every credential. Needs a migration path for
   already-registered keys.

3. **Cache only the FIDO keys, encrypted under a keystore key.** (preferred)
   Populated when the file is unlocked, readable by the service afterwards
   without the master password. The psafe3 file stays the backup and source
   of truth, and pocket operation works.
   *Cost:* similar in kind to option 1 but scoped to FIDO keys rather than
   every password in the database.

Even at best you unlock once after a reboot, then it runs unattended until
something locks it again.

## Security note

Auto-approval combined with an always-unlocked key store turns the phone
into a security key that is permanently present and permanently pressed for
any paired computer in Bluetooth range. A compromised PC could authenticate
as you everywhere with nothing visible on your end. The confirm dialog is
currently the only thing preventing that. Recorded here so the trade-off is
not rediscovered by accident; the maintainer has accepted it knowingly for
his own single-user setup.

## Step 3: staying connected (implemented 2026-09-17)

The phone is the HID *peripheral*. Windows and Linux never dial HID
peripherals (keyboards and mice dial the PC when a key is pressed), so once
the link dropped, because the PC slept or the phone left range, it stayed
down until the user opened the Bluetooth screen and tapped Connect. The
service used to dial exactly once, at profile registration, and only when a
host was flagged "default".

Now `BluetoothForegroundService` is the dialing side, like a multipoint
headset working through its paired list:

- `BluetoothDeviceListing.getFidoHostsByPreference()` returns every paired
  FIDO host, last connected first, then the default, then the rest. The
  host that connects is remembered (`LAST_FIDO_HOST` in the `hidbt`
  preferences). No default is needed.
- A DISCONNECTED callback with nothing connected schedules a retry with
  backoff, 10 s doubling to a 2 min cap; each attempt pages the next host
  on the list. The controller's 15 s connect timeout turns a host that is
  out of reach into a DISCONNECTED, which schedules the next attempt.
- `ACTION_ACL_CONNECTED` for any host on the list (some other profile just
  linked to it, typically the PC's Bluetooth coming back) triggers an
  attempt at once. Verified on kodiak: reconnected 2 s after the PC's
  Bluetooth was switched back on.
- The loop runs only in FIDO mode with FIDO enabled and no pairing or
  keyboard auto-type in flight, so the teardown before an auto-type is
  ignored; it is cancelled on CONNECTED, on user-driven connects and when
  the service stops. Keyboard mode still uses the default keyboard host.
- Preference "Reconnect to the FIDO computer automatically", on by default.

Cost: one page attempt (about 5 s of radio) every two minutes while no host
is reachable, nothing while connected.

## Order of work, if picked up

1. ~~Auto-approve preference.~~ Done, see Step 1.
2. ~~Decouple the credential backend from the activity.~~ Done.
3. ~~Option 3 key cache, then relax the answering gate in the service.~~ Done.
4. ~~Automatic reconnect to the FIDO host.~~ Done, see Step 3.
