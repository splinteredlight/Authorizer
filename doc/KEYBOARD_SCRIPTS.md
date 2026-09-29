# Keyboard screen and scripts

The **Keyboard** tab types to the connected computer without opening an
entry. It uses the same output as auto-type, over Bluetooth (through the
Pico bridge or a paired PC) or USB, and the same keyboard layout setting.

One box does everything: what it shows is what **Send** types, and
**Save** stores it as a script in your folder.

## The format

Text is typed as written and keys go in braces where they happen. Line
breaks are only layout and type nothing, so a script can put each step on
its own line; Enter is always `{ENTER}`:

```
{REM Open Local Users and Groups as administrator}
{WIN+r}
{DELAY 1000}
lusrmgr.msc
{CTRL+SHIFT+ENTER}
{DELAY 3000}
{Hilux.user}
{TAB}
{Hilux.password}
{ENTER}
```

(The first version typed Enter at every line break. A script written a
step per line then pressed Enter after every step; on a UAC prompt that
moved the cursor to the password box before the username was typed.)

| Token | Meaning |
| --- | --- |
| `{ENTER}`, `{TAB}`, `{ESC}`, `{BACKSPACE}`, `{DELETE}`, `{INSERT}`, `{SPACE}` | Press the key |
| `{UP}`, `{DOWN}`, `{LEFT}`, `{RIGHT}`, `{HOME}`, `{END}`, `{PAGEUP}`, `{PAGEDOWN}` | Press the key |
| `{F1}` … `{F12}`, `{PRINTSCREEN}`, `{CAPSLOCK}`, `{NUMLOCK}`, `{MENU}` | Press the key |
| `{WIN+r}`, `{CTRL+ALT+DELETE}`, `{SHIFT+TAB}`, `{CTRL++}` | Hold the modifiers, press the last key |
| `{WIN}` | A modifier on its own (opens the Start menu) |
| `{TAB 3}` | Press a key or combo 3 times |
| `{DELAY 500}` | Wait 500 ms (up to 600000) |
| `{REM note}` | Comment |
| `{Title.password}` | A field of the open file (below) |
| `{{` | A literal `{` |

Modifiers: `CTRL`, `ALT`, `SHIFT`, `WIN` (also `GUI`, `COMMAND`),
`ALTGR`. After a modifier, letters are keys: `{WIN+R}` is Win+R, not
Win+Shift+R. Tokens are not case-sensitive.

A brace that looks like a key but isn't one (`{ENTR}`) is reported as a
problem rather than typed. Braces that don't look like keys (`{"a": 1}`)
are typed as written.

## Building it on the phone

- **Modifier chips** (Ctrl, Alt, Shift, Win): turn one on, then type a
  character or tap a key chip; the box gets `{WIN+r}`. The chip turns off
  after one use.
- **Key chips**: tap to add the key to the box; long-press to send that
  key straight away without touching the box.
- **Entry field**: pick an entry of the open file (searchable), then a
  field: username, password, one-time code, URL, email, notes, or
  "Login", which adds `{X.user}{TAB}{X.password}{ENTER}`. Only the
  reference goes into the box, never the value.
- **Delay** chips (0.5 s, 1 s, 2 s, 5 s): add `{DELAY 500}` and so on;
  for another length, edit the number in the box.

The box turns off suggestions and keyboard learning (Gboard shows its
incognito icon) and is excluded from autofill.

## Scripts

Tap **Choose folder** and pick a folder; the app keeps read and write
access to it. **Save** writes the box as `name.txt` there (asking before
it replaces a file). Each script in the list has two buttons: the
pencil loads it into the box for editing, and ▶ types it. Tapping the
name also edits it, so a stray tap never types into the computer;
long-press previews it. If the box holds unsaved changes, loading
another script asks first. "Editing *name*" shows which script the box
holds, and Save offers that name.

Scripts are plain text, so you can also edit them in any editor; the
list reloads when you return to the app.

### Ducky Script files

A file where every line starts with an upper-case Ducky Script command
(`STRING`, `STRINGLN`, `DELAY`, `DEFAULT_DELAY`, `REPEAT`, `REM`, a key
such as `ENTER`, or a combo such as `GUI r`) runs as Ducky Script. Edit
converts it to the format above, one command per line (the result types
exactly the same keys), and Save then writes it in that format.

## Credential references

`{Title.field}` types a field of an entry in the open password file, so
scripts never contain secrets:

- Fields: `user` (or `username`), `password`, `url`, `email`, `notes`,
  `title`, `otp` (time-based codes only).
- When several entries share a title, add the group path:
  `{Work/Servers/Hilux.password}`. Matching ignores case but must find
  exactly one entry. The Entry field picker adds the group only when it
  is needed.

A reference needs the file to be open. If a reference can't be resolved,
a token is unknown, or a character can't be typed with the selected
layout, nothing is typed: the app lists the problems with their line
numbers instead. Messages never show a resolved value, and a password
with an untypable character is reported without naming it.

## Stopping

While typing, a card with **Stop** appears. Stop takes effect at the next
key or within 100 ms of a pause. Leaving the Keyboard screen, or the app
going to the background in Bluetooth keyboard mode, also stops typing.

## Code

- `net.tjado.authorizer.KeyScript`: the parser for both formats, format
  detection and Ducky conversion (plain Java, tested by `KeyScriptTest`).
- `Keystrokes`: reports plus pauses; wiped after sending.
- `UsbAutoType.run(..., Keystrokes, cancel, cb)` and
  `HidDeviceController.sendKeystrokes()`: the two senders.
- `KeyboardFragment`: the screen, saving and editing.
  `KeyboardEntryPicker`: the Entry field dialog. `FileCredentialResolver`:
  reference lookup. `KeyboardHostChooser`: Bluetooth host choice.
