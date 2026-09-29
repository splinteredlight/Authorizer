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
 * Turns keyboard text or a script into {@link Keystrokes}.
 *
 * <p>The main format is inline ({@link #parseText}): text is typed as
 * written and keys go in braces where they happen. Line breaks are layout
 * only and type nothing; Enter is always {ENTER}:
 * <pre>
 * {WIN+r}
 * {DELAY 500}
 * ssh admin@hilux{ENTER}
 * {DELAY 1500}
 * {Hilux.password}{ENTER}
 * </pre>
 * (A first version typed Enter for every line break. Scripts written a
 * step per line then pressed Enter after every step, which on a UAC
 * prompt moved the cursor into the password box before the username.)
 * Tokens: a named key ({ENTER}, {F5}), a combo ({CTRL+ALT+DELETE},
 * {WIN+r}), a modifier alone ({WIN}), any of these with a repeat count
 * ({TAB 3}), {DELAY ms}, {REM comment}, and references (below). A brace
 * that looks like a key name but is not one ({ENTR}) is a problem rather
 * than being typed; other braces ({"a": 1}) are typed as they are.
 *
 * <p>Script files may also be a small subset of Hak5 Ducky Script
 * ({@link #parseScript}, chosen by {@link #parseFile}), one command per
 * line:
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

    /** The name the keyboard screen uses for each modifier bit */
    private static final Map<Integer, String> MODIFIER_NAMES;

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

        Map<Integer, String> modNames = new HashMap<>();
        modNames.put(0x01, "CTRL");
        modNames.put(0x02, "SHIFT");
        modNames.put(0x04, "ALT");
        modNames.put(0x08, "WIN");
        modNames.put(0x40, "ALTGR");
        MODIFIER_NAMES = Collections.unmodifiableMap(modNames);

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
    // Every brace escaped: Android's ICU regex rejects a bare "}" that
    // desktop Java accepts, so the JVM tests alone would not catch it.
    /** Ducky commands other than keys, for {@link #isDucky} */
    private static final java.util.Set<String> DUCKY_COMMANDS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "REM", "STRING", "STRINGLN", "DELAY", "DEFAULT_DELAY",
                    "DEFAULTDELAY", "REPEAT"));

    /** {DELAY 500} */
    private static final Pattern DELAY_TOKEN =
            Pattern.compile("(?i)DELAY\\s+(\\S+)");
    /** {TAB 3}: a token and a repeat count */
    private static final Pattern REPEAT_TOKEN =
            Pattern.compile("(\\S.*?)\\s+(\\d+)");
    /**
     * Braces holding what looks like a key name or combo; if it is not a
     * known one, that is a typo to report, not text to type.
     */
    private static final Pattern KEY_LIKE =
            Pattern.compile("[A-Za-z][A-Za-z0-9]*(\\s*\\+\\s*[^+\\s]+)*(\\s+\\d+)?");

    private static final Pattern REFERENCE =
            Pattern.compile("\\{([^\\{\\}\\r\\n]+)\\.([A-Za-z]+)\\}");

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
     * Parse the inline format: text is typed as it is (a tab character is
     * Tab), line breaks type nothing, and brace tokens are keys, delays,
     * comments and references (see the class comment).
     */
    @NonNull
    public static Result parseText(@NonNull String text,
                                   @NonNull OutputInterface.Language lang,
                                   @Nullable CredentialResolver resolver)
    {
        return new KeyScript(UsbHidKbd.forLanguage(lang), lang.name(), resolver)
                .doParseText(text);
    }

    /**
     * Parse a script file in whichever format it is written: Ducky Script
     * when {@link #isDucky} says so, else the inline format.
     */
    @NonNull
    public static Result parseFile(@NonNull String script,
                                   @NonNull OutputInterface.Language lang,
                                   @Nullable CredentialResolver resolver)
    {
        return isDucky(script) ? parseScript(script, lang, resolver) :
               parseText(script, lang, resolver);
    }

    /**
     * Whether a script is Ducky Script: every non-blank line starts with a
     * Ducky command, key or modifier in upper case (as Ducky Script is
     * written). Inline text almost never does; a file of plain "TAB" and
     * "ENTER" lines means the same either way.
     */
    public static boolean isDucky(@NonNull String script)
    {
        boolean any = false;
        for (String line : stripBom(script).split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int space = firstSpace(trimmed);
            String first = (space < 0) ? trimmed : trimmed.substring(0, space);
            // CTRL-ALT style joins count by their first part
            int dash = first.indexOf('-');
            if (dash > 0) {
                first = first.substring(0, dash);
            }
            if (!first.equals(first.toUpperCase(Locale.ROOT)) ||
                !(DUCKY_COMMANDS.contains(first) || KEYS.containsKey(first) ||
                  MODIFIERS.containsKey(first))) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * Rewrite a Ducky script in the inline format, for editing, one command
     * per line. The result types the same keys. Call only for a script that parses without
     * problems; lines that do not parse are dropped.
     */
    @NonNull
    public static String duckyToInline(@NonNull String script)
    {
        StringBuilder out = new StringBuilder();
        String previous = null;
        int defaultDelay = 0;
        for (String line : stripBom(script).split("\\r?\\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int space = firstSpace(trimmed);
            String cmd = ((space < 0) ? trimmed : trimmed.substring(0, space))
                    .toUpperCase(Locale.ROOT);
            String arg = null;
            if (space >= 0) {
                arg = line.substring(line.indexOf(trimmed) + space + 1);
            }
            String piece;
            switch (cmd) {
            case "REM": {
                String note = (arg == null) ? "" :
                              arg.replace("{", "").replace("}", "").trim();
                out.append("{REM ").append(note).append("}\n");
                continue;
            }
            case "DELAY": {
                piece = "{DELAY " + ((arg == null) ? "" : arg.trim()) + "}";
                out.append(piece).append('\n');
                previous = piece;
                continue;
            }
            case "DEFAULT_DELAY":
            case "DEFAULTDELAY": {
                try {
                    defaultDelay = Integer.parseInt((arg == null) ? "" : arg.trim());
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            case "REPEAT": {
                int n = 0;
                try {
                    n = Integer.parseInt((arg == null) ? "" : arg.trim());
                } catch (NumberFormatException ignored) {
                }
                for (int r = 0; (previous != null) && (r < n); ++r) {
                    out.append(previous).append('\n');
                }
                continue;
            }
            case "STRING": {
                piece = escapeInline((arg == null) ? "" : arg);
                break;
            }
            case "STRINGLN": {
                piece = escapeInline((arg == null) ? "" : arg) + "{ENTER}";
                break;
            }
            default: {
                // Modifiers as the keyboard screen writes them
                List<String> names = new ArrayList<>();
                for (String t : comboTokens(trimmed)) {
                    Integer bit = MODIFIERS.get(t.toUpperCase(Locale.ROOT));
                    names.add((bit == null) ? t : MODIFIER_NAMES.get(bit));
                }
                piece = "{" + String.join("+", names) + "}";
                break;
            }
            }
            if (defaultDelay > 0) {
                piece += "{DELAY " + defaultDelay + "}";
            }
            out.append(piece).append('\n');
            previous = piece;
        }
        // No trailing break: it would only leave an empty last line
        int len = out.length();
        if ((len > 0) && (out.charAt(len - 1) == '\n')) {
            out.setLength(len - 1);
        }
        return out.toString();
    }

    /**
     * Ducky STRING text in the inline format: references and "{{" keep
     * their meaning, every other brace is doubled so it stays literal.
     */
    private static String escapeInline(String text)
    {
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != '{') {
                sb.append(c);
                ++pos;
            } else if (text.startsWith("{{", pos)) {
                sb.append("{{");
                pos += 2;
            } else {
                Matcher m = REFERENCE.matcher(text);
                m.region(pos, text.length());
                if (m.lookingAt() &&
                    FIELDS.containsKey(m.group(2).toLowerCase(Locale.ROOT))) {
                    sb.append(m.group());
                    pos = m.end();
                } else {
                    sb.append("{{");
                    ++pos;
                }
            }
        }
        return sb.toString();
    }

    private static String stripBom(String s)
    {
        return (!s.isEmpty() && (s.charAt(0) == BOM)) ? s.substring(1) : s;
    }

    /** Whether a token names a modifier, e.g. "CTRL" or "gui" */
    public static boolean isModifier(@NonNull String token)
    {
        return MODIFIERS.containsKey(token.toUpperCase(Locale.ROOT));
    }

    private Result doParseText(String text)
    {
        Keystrokes out = new Keystrokes();
        String[] lines = stripBom(text).split("\\r?\\n", -1);
        // Lines only number the problems; the breaks between them type
        // nothing.
        for (int i = 0; i < lines.length; ++i) {
            typeText(lines[i], i + 1, out, true);
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
                typeText(arg, lineNo, cmdKeys, false);
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
        List<String> tokens = comboTokens(line);

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

        // Letters are keys, as in Ducky Script: "GUI r" and "GUI R" both
        // mean Win+R, not Win+Shift+R.
        byte[] rep = keyReport(mods, key, true);
        if (rep != null) {
            out.addReport(rep);
            return true;
        }
        if (key.codePointCount(0, key.length()) == 1) {
            problem(lineNo, "'" + key + "' can't be typed with the " +
                            itsLayoutName + " keyboard layout");
            return false;
        }

        if ((tokens.size() == 1) && (mods == 0)) {
            problem(lineNo, "Unknown command '" + key +
                            "'. To type it, write STRING " + line);
        } else {
            problem(lineNo, "Unknown key '" + key + "'");
        }
        return false;
    }

    /** Split a Ducky key line into its keys, expanding CTRL-ALT joins */
    private static List<String> comboTokens(String line)
    {
        List<String> tokens = new ArrayList<>();
        for (String tok : line.trim().split("\\s+")) {
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
        return tokens;
    }

    /**
     * Type an inline brace token.
     *
     * @param content what is between the braces
     * @return false if it is not a token and the braces are to be typed as
     *         text; true if it was typed or reported as a problem
     */
    private boolean inlineToken(String content, int lineNo, Keystrokes out)
    {
        String c = content.trim();
        String upper = c.toUpperCase(Locale.ROOT);
        if (upper.equals("REM") || upper.startsWith("REM ")) {
            return true;
        }
        Matcher delay = DELAY_TOKEN.matcher(c);
        if (delay.matches()) {
            Integer ms = number(delay.group(1), lineNo, "{DELAY}", 0,
                                MAX_DELAY_MS);
            if (ms != null) {
                out.addPause(ms);
            }
            return true;
        }

        // {TAB 3}: a key and a count. The count only counts if what is
        // before it is a key; {"a": 5000} is text.
        Matcher repeat = REPEAT_TOKEN.matcher(c);
        if (repeat.matches()) {
            byte[] rep = tokenReport(repeat.group(1));
            if (rep != null) {
                int n = -1;
                try {
                    n = Integer.parseInt(repeat.group(2));
                } catch (NumberFormatException ignored) {
                }
                if ((n < 1) || (n > MAX_REPEAT)) {
                    problem(lineNo, "{" + content + "}: the count must be " +
                                    "from 1 to " + MAX_REPEAT);
                    return true;
                }
                for (int i = 0; i < n; ++i) {
                    out.addReport(rep);
                }
                return true;
            }
        }

        byte[] rep = tokenReport(c);
        if (rep != null) {
            out.addReport(rep);
            return true;
        }
        // A single character after valid modifiers that the layout lacks
        int plus = c.lastIndexOf('+');
        String key = (plus < 0) ? c : c.substring(plus + 1);
        if ((plus > 0) && (key.codePointCount(0, key.length()) == 1) &&
            (tokenReport(c.substring(0, plus)) != null)) {
            problem(lineNo, "'" + key + "' can't be typed with the " +
                            itsLayoutName + " keyboard layout");
            return true;
        }
        if (KEY_LIKE.matcher(c).matches()) {
            problem(lineNo, "Unknown key {" + content +
                            "}. To type a brace, write {{");
            return true;
        }
        return false;
    }

    /**
     * The report for a key token without a count: "ENTER", "a",
     * "CTRL+ALT+DELETE", "WIN+r", "CTRL++", or modifiers alone ("WIN",
     * "CTRL+ALT"). Null if it is not one.
     */
    @Nullable
    private byte[] tokenReport(String base)
    {
        String modPart;
        String key;
        if ((base.length() > 2) && base.endsWith("++")) {
            modPart = base.substring(0, base.length() - 2);
            key = "+";
        } else {
            int plus = base.lastIndexOf('+');
            modPart = (plus < 0) ? "" : base.substring(0, plus);
            key = (plus < 0) ? base : base.substring(plus + 1).trim();
        }
        if (key.isEmpty()) {
            return null;
        }
        int mods = 0;
        if (!modPart.isEmpty()) {
            for (String m : modPart.split("\\+", -1)) {
                Integer bit = MODIFIERS.get(m.trim().toUpperCase(Locale.ROOT));
                if (bit == null) {
                    return null;
                }
                mods |= bit;
            }
        }
        Integer bit = MODIFIERS.get(key.toUpperCase(Locale.ROOT));
        if (bit != null) {
            // Modifiers alone, e.g. {WIN} for the Start menu
            return report(mods | bit, 0);
        }
        return keyReport(mods, key, mods != 0);
    }

    /**
     * The report for a named key or a single character with modifiers, or
     * null if there is none. With {@code lowerLetters}, A-Z are keys, so
     * WIN+R is Win+R, not Win+Shift+R.
     */
    @Nullable
    private byte[] keyReport(int mods, String key, boolean lowerLetters)
    {
        Integer usage = KEYS.get(key.toUpperCase(Locale.ROOT));
        if (usage != null) {
            return report(mods, usage);
        }
        if (key.codePointCount(0, key.length()) != 1) {
            return null;
        }
        String ch = key;
        if (lowerLetters && (ch.length() == 1) && (ch.charAt(0) >= 'A') &&
            (ch.charAt(0) <= 'Z')) {
            ch = ch.toLowerCase(Locale.ROOT);
        }
        byte[] rep = scancode(ch);
        if (rep != null) {
            rep[0] |= (byte)mods;
        }
        return rep;
    }

    /**
     * Type text, expanding references and "{{".
     * @param tokens whether brace tokens (keys, delays) are recognised, as
     *               in the inline format; Ducky STRING text types them
     */
    private void typeText(String text, int lineNo, Keystrokes out,
                          boolean tokens)
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
                int close = text.indexOf('}', pos + 1);
                if (tokens && (close > pos + 1) &&
                    (text.indexOf('{', pos + 1) < 0 ||
                     text.indexOf('{', pos + 1) > close) &&
                    inlineToken(text.substring(pos + 1, close), lineNo, out)) {
                    pos = close + 1;
                    continue;
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
