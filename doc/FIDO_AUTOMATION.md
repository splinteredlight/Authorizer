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

## Step 1: auto-approve the taps

Small and self-contained. Replace the gate result with an automatic yes at
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

This alone does **not** give hands-off operation. See below.

## Step 2: answering with the app closed

Today `BluetoothForegroundService.onInterruptData` only answers when there is
a resumed activity and an open file:

```java
if (PasswdSafe.mTransactionManager != null && activity != null
        && activity.isFileOpen() && !activity.isEditMode()) {
```

`PasswdSafeApp` nulls its activity reference in `onActivityPaused`, so
backgrounding or locking the screen is enough to disable answering. With the
app closed the service can only post the "Action Required" notification
asking you to open it.

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

## Order of work, if picked up

1. Auto-approve preference. Small, independent, reversible.
2. Decouple the credential backend from the activity.
3. Option 3 key cache, then relax the answering gate in the service.
