/*
 * Authorizer
 *
 * Copyright 2026 Authorizer contributors
 * Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */
package net.tjado.authorizer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a keyboard script, or free text, into {@link Keystrokes}.
 *
 * <p>The script language is a small subset of Hak5 Ducky Script, one
 * command per line:
 * <pre>
 * REM comment                  ignored
 * STRING text                  type the text
 * STRINGLN text                type the text, then Enter
 * DELAY 500                    wait 500 ms
 * DEFAULT_DELAY 100            wait 100 ms after every later command
 * REPEAT 3                     run the previous command 3 more times
 * ENTER, TAB, ESC, F5, ...     press a named key
 * CTRL ALT DELETE, GUI r       press a key with modifiers held
 * </pre>
 *
 * <p>Text may reference an entry of the open password file as
 * {@code {Group/Title.field}} (field: user, password, url, email, notes,
 * title, otp). The value is fetched through a {@link CredentialResolver}
 * and typed; it never becomes part of the script. {@code {{} types a
 * literal brace; any other braces that are not a reference are typed as
 * they are.
 *
 * <p>Parsing is all-or-nothing: every problem is collected with its line
 * number, and a result with errors carries no keystrokes, so a script
 * never types half a login and then fails. Error messages never contain
 * a resolved value.
 *
 * <p>Plain Java on purpose (no Android classes) so it runs in JVM tests.
 */
public final class KeyScript
{
    /** Longest single DELAY, and longest DEFAULT_DELAY */
    public static final int MAX_DELAY_MS = 600_000;
    /** Byte-order mark some Windows editors put at the start of a file */
    private static final char BOM = 0xFEFF;
    /** Most repetitions a REPEAT may ask for */
    public static final int MAX_REPEAT = 1000;

    /** Looks up a field of a password-file entry */
    public interface CredentialResolver
    {
        /**
         * @param path  the entry, "Title" or "Group/Sub/Title"
         * @param field one of {@link #FIELDS}, lower case
         * @return the field value, never null (empty if the entry has none)
         * @throws ResolveException with a message for the user when the
         *         reference cannot be resolved
         */
        @NonNull
        String resolve(@NonNull String path, @NonNull String field)
                throws ResolveException;
    }

    /** A reference that could not be resolved; the message is shown */
    public static final class ResolveException extends Exception
    {
        public ResolveException(@NonNull String message)
        {
            super(message);
        }
    }

    /** One problem found while parsing */
    public static final class Problem
    {
        /** 1-based line number */
        public final int line;
        @NonNull
        public final String message;

        Problem(int line, @NonNull String message)
        {
            this.line = line;
            this.message = message;
        }

        @NonNull
        @Override
        public String toString()
        {
            return "Line " + line + ": " + message;
        }
    }

    /** Outcome of a parse */
    public static final class Result
    {
        @Nullable
        public final Keystrokes keystrokes;
        @NonNull
        public final List<Problem> problems;

        Result(@Nullable Keystrokes keystrokes, @NonNull List<Problem> problems)
        {
            this.keystrokes = keystrokes;
            this.problems = Collections.unmodifiableList(problems);
        }

        public boolean isOk()
        {
            return problems.isEmpty();
        }
    }

    /** Field names accepted in a reference, with their canonical form */
    public static final Map<String, String> FIELDS;

    /** Modifier bits of the first report byte */
    private static final Map<String, Integer> MODIFIERS;

    /** Layout-independent named keys, as HID usage codes */
    private static final Map<String, Integer> KEYS;

    static {
        Map<String, String> fields = new HashMap<>();
        fields.put("user", "user");
        fields.put("username", "user");
        fields.put("password", "password");
        fields.put("pass", "password");
        fields.put("url", "url");
        fields.put("email", "email");
        fields.put("notes", "notes");
        fields.put("title", "title");
        fields.put("otp", "otp");
        FIELDS = Collections.unmodifiableMap(fields);

        Map<String, Integer> mods = new HashMap<>();
        mods.put("CTRL", 0x01);
        mods.put("CONTROL", 0x01);
        mods.put("SHIFT", 0x02);
        mods.put("ALT", 0x04);
        mods.put("OPTION", 0x04);
        mods.put("GUI", 0x08);
        mods.put("WINDOWS", 0x08);
        mods.put("WIN", 0x08);
        mods.put("COMMAND", 0x08);
        mods.put("CMD", 0x08);
        mods.put("META", 0x08);
        mods.put("ALTGR", 0x40);
        MODIFIERS = Collections.unmodifiableMap(mods);

        Map<String, Integer> keys = new HashMap<>();
        keys.put("ENTER", 0x28);
        keys.put("RETURN", 0x28);
        keys.put("ESC", 0x29);
        keys.put("ESCAPE", 0x29);
        keys.put("BACKSPACE", 0x2a);
        keys.put("TAB", 0x2b);
        keys.put("SPACE", 0x2c);
        keys.put("CAPSLOCK", 0x39);
        for (int i = 1; i <= 12; ++i) {
            keys.put("F" + i, 0x3a + i - 1);
        }
        keys.put("PRINTSCREEN", 0x46);
        keys.put("SCROLLLOCK", 0x47);
        keys.put("PAUSE", 0x48);
        keys.put("BREAK", 0x48);
        keys.put("INSERT", 0x49);
        keys.put("HOME", 0x4a);
        keys.put("PAGEUP", 0x4b);
        keys.put("DELETE", 0x4c);
        keys.put("DEL", 0x4c);
        keys.put("END", 0x4d);
        keys.put("PAGEDOWN", 0x4e);
        keys.put("RIGHT", 0x4f);
        keys.put("RIGHTARROW", 0x4f);
        keys.put("LEFT", 0x50);
        keys.put("LEFTARROW", 0x50);
        keys.put("DOWN", 0x51);
        keys.put("DOWNARROW", 0x51);
        keys.put("UP", 0x52);
        keys.put("UPARROW", 0x52);
        keys.put("NUMLOCK", 0x53);
        keys.put("MENU", 0x65);
        keys.put("APP", 0x65);
        KEYS = Collections.unmodifiableMap(keys);
    }

    /**
     * {prefix.field}: the part before the last dot is the entry path, the
     * part after it must be a known field.
     */
    private static final Pattern REFERENCE =
            Pattern.compile("\\{([^{}\\r\\n]+)\\.([A-Za-z]+)}");

    private final UsbHidKbd itsLayout;
    private final String itsLayoutName;
    private final CredentialResolver itsResolver;
    private final List<Problem> itsProblems = new ArrayList<>();

    private KeyScript(@NonNull UsbHidKbd layout, @NonNull String layoutName,
                      @Nullable CredentialResolver resolver)
    {
        itsLayout = layout;
        itsLayoutName = layoutName;
        itsResolver = resolver;
    }

    /**
     * Parse a script.
     *
     * @param resolver null when no password file is open; references are
     *                 then reported as problems
     */
    @NonNull
    public static Result parseScript(@NonNull String script,
                                     @NonNull OutputInterface.Language lang,
                                     @Nullable CredentialResolver resolver)
    {
        return new KeyScript(UsbHidKbd.forLanguage(lang), lang.name(), resolver)
                .doParseScript(script);
    }

    /**
     * Parse free text: typed as it is, line breaks become Enter and tabs
     * become Tab. References are expanded as in a script's STRING.
     */
    @NonNull
    public static Result parseText(@NonNull String text,
                                   @NonNull OutputInterface.Language lang,
                                   @Nullable CredentialResolver resolver)
    {
        return new KeyScript(UsbHidKbd.forLanguage(lang), lang.name(), resolver)
                .doParseText(text);
    }

    /** Whether a token names a modifier, e.g. "CTRL" or "gui" */
    public static boolean isModifier(@NonNull String token)
    {
        return MODIFIERS.containsKey(token.toUpperCase(Locale.ROOT));
    }

    private Result doParseText(String text)
    {
        Keystrokes out = new Keystrokes();
        String[] lines = text.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; ++i) {
            if (i > 0) {
                out.addReport(report(0, KEYS.get("ENTER")));
            }
            typeText(lines[i], i + 1, out);
        }
        return finish(out);
    }

    private Result doParseScript(String script)
    {
        Keystrokes out = new Keystrokes();
        Keystrokes previous = null;
        int defaultDelay = 0;

        String[] lines = script.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; ++i) {
            int lineNo = i + 1;
            String line = lines[i];
            // A BOM from a Windows editor would otherwise make the first
            // command unknown.
            if ((i == 0) && !line.isEmpty() && (line.charAt(0) == BOM)) {
                line = line.substring(1);
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            int space = firstSpace(trimmed);
            String cmd = ((space < 0) ? trimmed : trimmed.substring(0, space))
                    .toUpperCase(Locale.ROOT);
            // Everything after the one separating space, spaces kept
            String arg = null;
            if (space >= 0) {
                int argStart = line.indexOf(trimmed) + space + 1;
                arg = line.substring(argStart);
            }

            Keystrokes cmdKeys = new Keystrokes();
            switch (cmd) {
            case "REM": {
                continue;
            }
            case "STRING":
            case "STRINGLN": {
                if (arg == null) {
                    if (cmd.equals("STRING")) {
                        problem(lineNo, "STRING needs text to type");
                        continue;
                    }
                    arg = "";
                }
                typeText(arg, lineNo, cmdKeys);
                if (cmd.equals("STRINGLN")) {
                    cmdKeys.addReport(report(0, KEYS.get("ENTER")));
                }
                break;
            }
            case "DELAY": {
                Integer ms = number(arg, lineNo, "DELAY", 0, MAX_DELAY_MS);
                if (ms == null) {
                    continue;
                }
                cmdKeys.addPause(ms);
                // A DELAY is its own pause; no default delay on top
                out.addAll(cmdKeys);
                previous = cmdKeys;
                continue;
            }
            case "DEFAULT_DELAY":
            case "DEFAULTDELAY": {
                Integer ms = number(arg, lineNo, cmd, 0, MAX_DELAY_MS);
                if (ms != null) {
                    defaultDelay = ms;
                }
                continue;
            }
            case "REPEAT": {
                Integer n = number(arg, lineNo, "REPEAT", 1, MAX_REPEAT);
                if (n == null) {
                    continue;
                }
                if (previous == null) {
                    problem(lineNo, "REPEAT has no command before it to repeat");
                    continue;
                }
                for (int r = 0; r < n; ++r) {
                    out.addAll(previous);
                }
                continue;
            }
            default: {
                if (!keyCombo(trimmed, lineNo, cmdKeys)) {
                    continue;
                }
                break;
            }
            }

            cmdKeys.addPause(defaultDelay);
            out.addAll(cmdKeys);
            previous = cmdKeys;
        }
        return finish(out);
    }

    private Result finish(Keystrokes out)
    {
        if (!itsProblems.isEmpty()) {
            out.wipe();
            return new Result(null, itsProblems);
        }
        return new Result(out, itsProblems);
    }

    /**
     * A line of modifiers ending in at most one key: "CTRL ALT DELETE",
     * "GUI r", "SHIFT TAB", "ENTER", or a lone "GUI". Tokens are separated
     * by spaces; "CTRL-ALT" style joins of modifiers are accepted too.
     */
    private boolean keyCombo(String line, int lineNo, Keystrokes out)
    {
        List<String> tokens = new ArrayList<>();
        for (String tok : line.split("\\s+")) {
            if ((tok.length() > 1) && tok.contains("-")) {
                String[] parts = tok.split("-");
                boolean allMods = parts.length > 1;
                for (String p : parts) {
                    allMods &= isModifier(p);
                }
                if (allMods) {
                    Collections.addAll(tokens, parts);
                    continue;
                }
            }
            tokens.add(tok);
        }

        int mods = 0;
        String key = null;
        for (int t = 0; t < tokens.size(); ++t) {
            String tok = tokens.get(t);
            Integer mod = MODIFIERS.get(tok.toUpperCase(Locale.ROOT));
            if ((mod != null) && (key == null)) {
                mods |= mod;
                continue;
            }
            if (key != null) {
                problem(lineNo, "Only one key can be pressed per line; '" +
                                key + "' and '" + tok + "' are both keys");
                return false;
            }
            key = tok;
        }

        if (key == null) {
            // Modifiers alone, e.g. GUI to open the Start menu
            out.addReport(report(mods, 0));
            return true;
        }

        Integer usage = KEYS.get(key.toUpperCase(Locale.ROOT));
        if (usage != null) {
            out.addReport(report(mods, usage));
            return true;
        }

        if (key.codePointCount(0, key.length()) == 1) {
            // Letters are keys, as in Ducky Script: "GUI r" and "GUI R" both
            // mean Win+R, not Win+Shift+R.
            String ch = key;
            if ((ch.length() == 1) && (ch.charAt(0) >= 'A') &&
                (ch.charAt(0) <= 'Z')) {
                ch = ch.toLowerCase(Locale.ROOT);
            }
            byte[] rep = scancode(ch);
            if (rep == null) {
                problem(lineNo, "'" + key + "' can't be typed with the " +
                                itsLayoutName + " keyboard layout");
                return false;
            }
            rep[0] |= (byte)mods;
            out.addReport(rep);
            return true;
        }

        if ((tokens.size() == 1) && (mods == 0)) {
            problem(lineNo, "Unknown command '" + key +
                            "'. To type it, write STRING " + line);
        } else {
            problem(lineNo, "Unknown key '" + key + "'");
        }
        return false;
    }

    /** Type text, expanding references */
    private void typeText(String text, int lineNo, Keystrokes out)
    {
        int pos = 0;
        while (pos < text.length()) {
            if (text.startsWith("{{", pos)) {
                typeLiteral("{", lineNo, out);
                pos += 2;
                continue;
            }
            if (text.charAt(pos) == '{') {
                Matcher m = REFERENCE.matcher(text);
                m.region(pos, text.length());
                if (m.lookingAt()) {
                    String field = FIELDS.get(m.group(2).toLowerCase(Locale.ROOT));
                    if (field != null) {
                        typeReference(m.group(1).trim(), field, m.group(0),
                                      lineNo, out);
                        pos = m.end();
                        continue;
                    }
                }
            }
            int next = text.indexOf('{', pos + 1);
            if (next < 0) {
                next = text.length();
            }
            typeLiteral(text.substring(pos, next), lineNo, out);
            pos = next;
        }
    }

    private void typeReference(String path, String field, String ref,
                               int lineNo, Keystrokes out)
    {
        if (itsResolver == null) {
            problem(lineNo, ref + " needs a password file; open one first");
            return;
        }
        String value;
        try {
            value = itsResolver.resolve(path, field);
        } catch (ResolveException e) {
            problem(lineNo, ref + ": " + e.getMessage());
            return;
        }
        // Never name the character: it is part of a secret
        if (!typeChars(value, out)) {
            problem(lineNo, ref + " contains a character the " +
                            itsLayoutName + " keyboard layout can't type");
        }
    }

    private void typeLiteral(String text, int lineNo, Keystrokes out)
    {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            i += ch.length();
            if (!typeChars(ch, out)) {
                problem(lineNo, "'" + ch + "' can't be typed with the " +
                                itsLayoutName + " keyboard layout");
            }
        }
    }

    /**
     * Append the reports for the characters.
     * @return false if any character has no mapping (the rest are added)
     */
    private boolean typeChars(String text, Keystrokes out)
    {
        boolean ok = true;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\r') {
                // CRLF (notes are stored that way) is one Enter
                if ((i < text.length()) && (text.charAt(i) == '\n')) {
                    continue;
                }
                cp = '\n';
            }
            if (cp == '\n') {
                out.addReport(report(0, KEYS.get("ENTER")));
                continue;
            }
            if (cp == '\t') {
                out.addReport(report(0, KEYS.get("TAB")));
                continue;
            }
            byte[] rep = scancode(new String(Character.toChars(cp)));
            if (rep == null) {
                ok = false;
                continue;
            }
            out.addReport(rep);
        }
        return ok;
    }

    /** @return a copy of the layout's report for the character, or null */
    @Nullable
    private byte[] scancode(String ch)
    {
        try {
            return itsLayout.getScancode(ch).clone();
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    @Nullable
    private Integer number(@Nullable String arg, int lineNo, String cmd,
                           int min, int max)
    {
        if (arg != null) {
            try {
                int n = Integer.parseInt(arg.trim());
                if ((n >= min) && (n <= max)) {
                    return n;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        problem(lineNo, cmd + " needs a whole number from " + min +
                        " to " + max);
        return null;
    }

    private void problem(int lineNo, String message)
    {
        itsProblems.add(new Problem(lineNo, message));
    }

    private static int firstSpace(String s)
    {
        for (int i = 0; i < s.length(); ++i) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] report(int mods, int usage)
    {
        return new byte[]{(byte)mods, 0, (byte)usage, 0, 0, 0, 0, 0};
    }
}
