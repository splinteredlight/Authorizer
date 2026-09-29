# Keyboard screen and scripts

The **Keyboard** tab types to the connected computer without opening an
entry: free text, single keys with modifiers, and scripts saved as text
files on the phone. It uses the same output as auto-type, over Bluetooth
(through the Pico bridge or a paired PC) or USB, and the same keyboard
layout setting.

## Free typing

Type into the box and tap **Send**. Line breaks become Enter and tabs
become Tab; **Press Enter after** adds one more Enter at the end.

For shortcuts, turn on one or more modifier chips (Ctrl, Alt, Shift, Win)
and tap a key chip, or send a single character: Ctrl + Alt + `Delete`, or
Win with `r` in the box for the Run dialog. The modifiers clear after each
use.

The text box turns off suggestions and keyboard learning (Gboard shows
its incognito icon) and is excluded from autofill.

## Scripts

Tap **Choose folder** and pick a folder of `.txt` files. The app keeps
read access to that folder only. Tap a script to type it, long-press to
read it first. Edit scripts in any text editor; the list reloads when you
return to the app (or with the reload button).

The language is a subset of Hak5 Ducky Script, one command per line:

| Command | Meaning |
| --- | --- |
| `STRING text` | Type the text (spaces after the first one are kept) |
| `STRINGLN text` | Type the text, then Enter |
| `ENTER`, `TAB`, `ESC`, `BACKSPACE`, `DELETE`, `INSERT`, `SPACE` | Press the key |
| `UP`, `DOWN`, `LEFT`, `RIGHT`, `HOME`, `END`, `PAGEUP`, `PAGEDOWN` | Press the key |
| `F1` … `F12`, `PRINTSCREEN`, `CAPSLOCK`, `NUMLOCK`, `MENU` | Press the key |
| `CTRL ALT DELETE`, `GUI r`, `SHIFT TAB` | Hold the modifiers, press the last key |
| `GUI` | Press a modifier on its own (opens the Start menu) |
| `DELAY 500` | Wait 500 ms (up to 600000) |
| `DEFAULT_DELAY 100` | Wait 100 ms after every later command |
| `REPEAT 3` | Run the previous command 3 more times |
| `REM text` | Comment |

Modifiers: `CTRL`, `ALT`, `SHIFT`, `GUI` (also `WIN`, `WINDOWS`,
`COMMAND`), `ALTGR`. Letters after modifiers are keys, so `GUI r` and
`GUI R` are both Win+R. Commands are not case-sensitive.

Example:

```
REM Log in to the Hilux box
GUI r
DELAY 500
STRINGLN ssh admin@hilux
DELAY 1500
STRINGLN {Hilux.password}
```

## Credential references

In free text and in `STRING`/`STRINGLN`, `{Title.field}` types a field
of an entry in the open password file, so scripts never contain secrets:

- Fields: `user` (or `username`), `password`, `url`, `email`, `notes`,
  `title`, `otp` (time-based codes only).
- When several entries share a title, add the group path:
  `{Work/Servers/Hilux.password}`. Matching ignores case but must find
  exactly one entry.
- `{{` types a single `{`. Braces that are not a reference (such as
  `{a.b}`, where `b` is not a field) are typed as written.

A reference needs the file to be open. If a reference can't be resolved,
a line has an unknown command, or a character can't be typed with the
selected layout, nothing is typed: the app lists the problems with their
line numbers instead. Messages never show a resolved value, and a
password with an untypable character is reported without naming it.

## Stopping

While typing, a card with **Stop** appears. Stop takes effect at the next
key or within 100 ms of a pause. Leaving the Keyboard screen, or the app
going to the background in Bluetooth keyboard mode, also stops typing.

## Code

- `net.tjado.authorizer.KeyScript`: the parser (plain Java, tested by
  `KeyScriptTest`).
- `Keystrokes`: reports plus pauses; wiped after sending.
- `UsbAutoType.run(..., Keystrokes, cancel, cb)` and
  `HidDeviceController.sendKeystrokes()`: the two senders.
- `KeyboardFragment`, `FileCredentialResolver`, `KeyboardHostChooser`:
  the screen, reference lookup, and Bluetooth host choice.
