# UI design (2026 redesign)

The 2026 redesign moved Authorizer from the 2015 AppCompat look to
Material 3. This note records the decisions so later screens stay
consistent, and the traps that cost time.

## Direction

The product's signature act is the hand-off: phone → keyboard → computer.
The UI is a precise instrument for that, not a notes app. So:

- One bold element per screen. On the entry screen that is the
  "Type to computer" block; its buttons are the only place the warm
  tertiary (brass) colour appears. Everything else sits on quiet tonal
  surfaces.
- Structure carries meaning. Chevrons only on groups, counts as trailing
  text, tonal icon circles only on records and files.
- Copy uses verbs and sentence case: "Open file", "Copy password",
  "Pair as keyboard", "Entries / Expiry / Policies / Settings".
- Motion answers actions only: a short fade-through between screens
  (`anim/fragment_fade_*`), nothing decorative.

## Colour

- Roles are generated, not hand-picked: `values/colors_m3.xml` and
  `values-night/colors_m3.xml` come from the same script
  (material-color-utilities, seed `#1F6F8B` teal, TonalSpot; the tertiary
  roles are the *primary* roles of a second scheme seeded with brass
  `#8A6A1E`). Regenerate both files together rather than editing values.
- `Theme.Authorizer` (values/themes.xml) maps every role. It is a
  `Theme.Material3.DayNight.NoActionBar`; the activity provides its own
  `MaterialToolbar`.
- "Use system colours" (default on, Android 12+) layers
  `DynamicColors.applyToActivityIfAvailable` on top. Screenshots therefore
  vary with the wallpaper; turn the switch off to see the brand palette.
- The theme preference only selects AppCompat's night mode
  (`PasswdSafeApp.applyNightMode`). There is one theme, not a light and a
  dark one.
- Non-role colours live in `values/colors.xml` (+ night): `status_ok`,
  `status_warning`, `password_digit/symbol`, launcher background.

## Type and spacing

- System Roboto through the M3 type scale. Passwords, one-time codes,
  device addresses and username templates are `monospace`.
- `@dimen/screen_gutter` (16 dp) is the horizontal gutter of every screen;
  lists are full-bleed with the gutter inside the row.
- Section headings use `TextAppearance.Authorizer.Section` (labelLarge,
  primary); label/value pairs use `Authorizer.DetailRow/Label/Value`.

## Icons

- Every action icon is a Material vector in `drawable/ic_*.xml`, tinted
  with `?attr/colorControlNormal` so it follows the theme. There are no
  light/dark PNG variants any more. Add new icons the same way.
- Record icons still come from the Iconics fonts because the icon *name*
  is stored in the `.psafe3` file; the theme attributes
  `drawableFolder`, `drawablePersonOutline`, ... exist for the code paths
  that resolve an icon by attribute.
- The launcher icon is adaptive (`mipmap-anydpi-v26/ic_launcher.xml`):
  keycap + key foreground, teal background, monochrome layer for themed
  icons. The notification small icon `ic_stat_app` is the same key glyph.

## Components

- Lists: `passwdsafe_list_tree_item.xml` (tree) and
  `passwdsafe_list_item.xml` (flat / two-pane). The tree row is inflated
  without a parent by AndroidTreeView, so the holder sets match_parent
  layout params itself.
- Boolean settings render as `MaterialSwitch` through the preference
  theme overlay (`preference_widget_material_switch*.xml`);
  `CheckBoxPreference` only needs the widget to be `Checkable`.
- Spinners keep the AppCompat class (the edit code depends on it) and get
  an outlined-box background (`bg_spinner_outlined`).
- Dialogs use `MaterialAlertDialogBuilder` everywhere; the shortcut and
  OTP activities use `Theme.Authorizer.Dialog`.

## Traps

- With a Toolbar-backed action bar, `invalidateOptionsMenu()` rebuilds the
  whole menu (it does not just prepare it). Never call it from
  `onCreateOptionsMenu` or from the search expand/collapse callbacks; use
  `refreshOptionsMenu()` in `PasswdSafe`, which re-runs
  `onPrepareOptionsMenu` on the current menu. The old code looped and the
  SearchView collapsed on the frame it opened.
- androidx.preference reserves icon space from sw360dp up
  (`config_materialPreferenceIconSpaceReserved`); the override must live in
  `values-sw360dp/`, and lint needs `tools:ignore="MissingDefaultResource"`.
- Every form that holds a master or entry password sets
  `importantForAutofill="noExcludeDescendants"`; without it Google Password
  Manager offers to save what is typed.
- API-gated theme attributes (`forceDarkAllowed`,
  `windowLayoutInDisplayCutoutMode`) need `tools:targetApi` or
  `lintVitalRelease` fails the release build.

## Checking a change

Build both variants and run lint (`assembleDebug`, `assembleRelease`,
`lintDebug`), then look at the screens. The emulator AVD
`Medium_Phone_API_36` is enough for layout work; `adb shell cmd uimode
night yes|no` switches the theme, and Settings → Display → "Use system
colours" toggles dynamic colour. Take screenshots with
`adb exec-out screencap -p > file.png` and read them; a picture catches
what a build cannot.
