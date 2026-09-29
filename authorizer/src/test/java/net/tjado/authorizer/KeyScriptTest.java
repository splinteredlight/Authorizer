/*
 * Authorizer
 *
 * Copyright 2026 Authorizer contributors
 * Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */
package net.tjado.authorizer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class KeyScriptTest
{
    private static final OutputInterface.Language US =
            OutputInterface.Language.en_US;

    /** Resolves "Server" and "Work/Server" only */
    private static final KeyScript.CredentialResolver RESOLVER =
            (path, field) -> {
                if (path.equals("Server") && field.equals("password")) {
                    return "pw1";
                }
                if (path.equals("Work/Server") && field.equals("user")) {
                    return "admin";
                }
                if (path.equals("Umlaut")) {
                    return "é";
                }
                throw new KeyScript.ResolveException("no entry named " + path);
            };

    private static byte[] key(int mods, int usage)
    {
        return new byte[]{(byte)mods, 0, (byte)usage, 0, 0, 0, 0, 0};
    }

    /** Reports of a result, with pauses as a single-element marker */
    private static List<byte[]> steps(KeyScript.Result r)
    {
        assertTrue(r.problems.toString(), r.isOk());
        List<byte[]> out = new ArrayList<>();
        for (Keystrokes.Step s : r.keystrokes.getSteps()) {
            out.add(s.isPause() ? new byte[]{(byte)(s.pauseMs / 100)} :
                    s.report);
        }
        return out;
    }

    private static void assertSteps(KeyScript.Result r, byte[]... expected)
    {
        List<byte[]> actual = steps(r);
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; ++i) {
            assertArrayEquals("step " + i, expected[i], actual.get(i));
        }
    }

    private static byte[] pause(int ms)
    {
        return new byte[]{(byte)(ms / 100)};
    }

    @Test
    public void stringTypesCharacters()
    {
        assertSteps(KeyScript.parseScript("STRING aB", US, null),
                    key(0, 0x04), key(0x02, 0x05));
    }

    @Test
    public void stringKeepsSpacesAfterTheSeparator()
    {
        assertSteps(KeyScript.parseScript("STRING  a ", US, null),
                    key(0, 0x2c), key(0, 0x04), key(0, 0x2c));
    }

    @Test
    public void leadingByteOrderMarkIsIgnored()
    {
        assertSteps(KeyScript.parseScript((char)0xFEFF + "ENTER", US, null),
                    key(0, 0x28));
    }

    @Test
    public void emptyFirstLineIsFine()
    {
        assertSteps(KeyScript.parseScript("\nENTER", US, null),
                    key(0, 0x28));
    }

    @Test
    public void stringlnAddsEnter()
    {
        assertSteps(KeyScript.parseScript("STRINGLN a", US, null),
                    key(0, 0x04), key(0, 0x28));
    }

    @Test
    public void commentsAndBlankLinesAreIgnored()
    {
        assertSteps(KeyScript.parseScript("REM hi\n\n  \r\nENTER\r\n", US, null),
                    key(0, 0x28));
    }

    @Test
    public void comboWithLetterIgnoresCase()
    {
        assertSteps(KeyScript.parseScript("GUI r\ngui R", US, null),
                    key(0x08, 0x15), key(0x08, 0x15));
    }

    @Test
    public void ctrlAltDelete()
    {
        assertSteps(KeyScript.parseScript("CTRL ALT DELETE\nCTRL-ALT DEL",
                                          US, null),
                    key(0x05, 0x4c), key(0x05, 0x4c));
    }

    @Test
    public void modifierAlone()
    {
        assertSteps(KeyScript.parseScript("GUI", US, null), key(0x08, 0));
    }

    @Test
    public void delayAndDefaultDelay()
    {
        assertSteps(KeyScript.parseScript(
                            "DEFAULT_DELAY 100\nENTER\nDELAY 500\nTAB",
                            US, null),
                    key(0, 0x28), pause(100), pause(500), key(0, 0x2b),
                    pause(100));
    }

    @Test
    public void repeatRepeatsPreviousCommand()
    {
        assertSteps(KeyScript.parseScript("TAB\nREPEAT 2", US, null),
                    key(0, 0x2b), key(0, 0x2b), key(0, 0x2b));
    }

    @Test
    public void referenceIsResolved()
    {
        assertSteps(KeyScript.parseScript("STRING {Server.password}!", US,
                                          RESOLVER),
                    key(0, 0x13), key(0, 0x1a), key(0, 0x1e),
                    key(0x02, 0x1e));
    }

    @Test
    public void referenceWithGroupAndFieldAlias()
    {
        assertSteps(KeyScript.parseScript("STRING {Work/Server.USERNAME}", US,
                                          RESOLVER),
                    key(0, 0x04), key(0, 0x07), key(0, 0x10), key(0, 0x0c),
                    key(0, 0x11));
    }

    @Test
    public void unknownFieldIsTypedLiterally()
    {
        assertEquals(5, KeyScript.parseScript("STRING {a.b}", US, RESOLVER)
                                 .keystrokes.getKeyCount());
    }

    @Test
    public void doubleBraceTypesOneBrace()
    {
        assertSteps(KeyScript.parseScript("STRING {{", US, null),
                    key(0x02, 0x2f));
    }

    @Test
    public void unresolvedReferenceFailsTheWholeScript()
    {
        KeyScript.Result r = KeyScript.parseScript(
                "STRING ok\nSTRING {Nope.password}", US, RESOLVER);
        assertFalse(r.isOk());
        assertNull(r.keystrokes);
        assertEquals(2, r.problems.get(0).line);
    }

    @Test
    public void referenceWithoutFileIsAProblem()
    {
        assertFalse(KeyScript.parseScript("STRING {Server.password}", US, null)
                             .isOk());
    }

    @Test
    public void untypableSecretIsNotNamed()
    {
        KeyScript.Result r = KeyScript.parseScript("STRING {Umlaut.password}",
                                                   US, RESOLVER);
        assertFalse(r.isOk());
        assertFalse(r.problems.get(0).message.contains("é"));
    }

    @Test
    public void untypableLiteralIsNamed()
    {
        KeyScript.Result r = KeyScript.parseScript("STRING é", US, null);
        assertFalse(r.isOk());
        assertTrue(r.problems.get(0).message.contains("é"));
    }

    @Test
    public void unknownCommandAndBadNumbersAreReported()
    {
        KeyScript.Result r = KeyScript.parseScript(
                "hello world\nDELAY x\nREPEAT 1\nCTRL a b", US, null);
        assertEquals(4, r.problems.size());
        assertEquals(1, r.problems.get(0).line);
        assertEquals(2, r.problems.get(1).line);
        assertEquals(3, r.problems.get(2).line);
        assertEquals(4, r.problems.get(3).line);
    }

    @Test
    public void textLineBreaksTypeNothingButTabsDo()
    {
        assertSteps(KeyScript.parseText("a\tb\r\nc\n", US, null),
                    key(0, 0x04), key(0, 0x2b), key(0, 0x05), key(0, 0x06));
    }

    @Test
    public void referencedNotesStillTypeTheirLineBreaks()
    {
        KeyScript.CredentialResolver notes = (path, field) -> "a\r\nb";
        assertSteps(KeyScript.parseText("{X.notes}", US, notes),
                    key(0, 0x04), key(0, 0x28), key(0, 0x05));
    }

    /** The script that exposed Enter-per-line: one step per line */
    @Test
    public void stepPerLineScriptPressesOnlyItsOwnKeys()
    {
        KeyScript.CredentialResolver r = (path, field) ->
                field.equals("user") ? "u" : "p";
        assertSteps(KeyScript.parseText(
                            "{delay 1000}\n{WIN+r}\n{delay 1000}\nab\n" +
                            "{CTRL+SHIFT+ENTER}\n{server.user}\n{TAB}\n" +
                            "{server.password}\n{ENTER}", US, r),
                    pause(1000), key(0x08, 0x15), pause(1000), key(0, 0x04),
                    key(0, 0x05), key(0x03, 0x28), key(0, 0x18), key(0, 0x2b),
                    key(0, 0x13), key(0, 0x28));
    }

    @Test
    public void textExpandsReferences()
    {
        assertEquals(3, KeyScript.parseText("{Server.password}", US, RESOLVER)
                                 .keystrokes.getKeyCount());
    }

    @Test
    public void wipeZeroesReports()
    {
        Keystrokes ks = KeyScript.parseText("a", US, null).keystrokes;
        byte[] rep = ks.getSteps().get(0).report;
        ks.wipe();
        assertArrayEquals(new byte[8], rep);
        assertTrue(ks.isEmpty());
    }

    // ------------------------------------------------------------------
    // Inline format

    @Test
    public void inlineKeysAndCombos()
    {
        assertSteps(KeyScript.parseText("{WIN+r}a{ENTER}{ctrl+alt+del}", US,
                                        null),
                    key(0x08, 0x15), key(0, 0x04), key(0, 0x28),
                    key(0x05, 0x4c));
    }

    @Test
    public void inlineComboLetterIgnoresCaseButLoneLetterDoesNot()
    {
        assertSteps(KeyScript.parseText("{WIN+R}{A}", US, null),
                    key(0x08, 0x15), key(0x02, 0x04));
    }

    @Test
    public void inlineModifierAloneAndPlusKey()
    {
        assertSteps(KeyScript.parseText("{WIN}{CTRL++}", US, null),
                    key(0x08, 0), key(0x03, 0x2e));
    }

    @Test
    public void inlineRepeatDelayAndComment()
    {
        assertSteps(KeyScript.parseText("{TAB 2}{DELAY 500}{REM hi}", US, null),
                    key(0, 0x2b), key(0, 0x2b), pause(500));
    }

    @Test
    public void inlineReferenceAndEscapedBrace()
    {
        assertSteps(KeyScript.parseText("{{{Server.password}", US, RESOLVER),
                    key(0x02, 0x2f), key(0, 0x13), key(0, 0x1a), key(0, 0x1e));
    }

    @Test
    public void inlineCommentLinesTypeNothing()
    {
        assertSteps(KeyScript.parseText("{REM top}\na\n{REM mid}\nb\n{rem}", US,
                                        null),
                    key(0, 0x04), key(0, 0x05));
    }

    @Test
    public void inlineUnknownKeyIsAProblem()
    {
        KeyScript.Result r = KeyScript.parseText("ok\n{ENTR}", US, null);
        assertFalse(r.isOk());
        assertEquals(2, r.problems.get(0).line);
    }

    @Test
    public void inlineNonKeyBracesAreText()
    {
        // {"a": 5000} is not a key with a count, and {a.b} not a reference
        KeyScript.Result r = KeyScript.parseText("{\"a\": 5000}{a.b}", US, null);
        assertEquals(16, r.keystrokes.getKeyCount());
    }

    @Test
    public void inlineUntypableComboKeyIsNamed()
    {
        KeyScript.Result r = KeyScript.parseText("{CTRL+\u00e9}", US, null);
        assertFalse(r.isOk());
        assertTrue(r.problems.get(0).message.contains("layout"));
    }

    @Test
    public void inlineBadCount()
    {
        assertFalse(KeyScript.parseText("{TAB 0}", US, null).isOk());
    }

    // ------------------------------------------------------------------
    // Format detection and conversion

    @Test
    public void duckyIsDetected()
    {
        assertTrue(KeyScript.isDucky("REM x\nGUI r\n\nSTRINGLN hi\nCTRL-ALT DEL"));
        assertFalse(KeyScript.isDucky("{WIN+r}ssh admin@server"));
        assertFalse(KeyScript.isDucky("Enter the code\n"));
        assertFalse(KeyScript.isDucky(""));
    }

    @Test
    public void parseFilePicksTheFormat()
    {
        assertSteps(KeyScript.parseFile("TAB", US, null), key(0, 0x2b));
        assertSteps(KeyScript.parseFile("{TAB}", US, null), key(0, 0x2b));
    }

    private static void assertSameKeys(String ducky)
    {
        String inline = KeyScript.duckyToInline(ducky);
        List<byte[]> a = steps(KeyScript.parseScript(ducky, US, RESOLVER));
        List<byte[]> b = steps(KeyScript.parseText(inline, US, RESOLVER));
        assertEquals(inline, a.size(), b.size());
        for (int i = 0; i < a.size(); ++i) {
            assertArrayEquals(inline + " step " + i, a.get(i), b.get(i));
        }
    }

    @Test
    public void convertedDuckyUsesScreenModifierNames()
    {
        assertEquals("{WIN+r}\n{CTRL+ALT+DEL}\nhi{ENTER}\n{ENTER}",
                     KeyScript.duckyToInline(
                             "GUI r\nCONTROL-ALT DEL\nSTRINGLN hi\nENTER"));
    }

    @Test
    public void convertedDuckyTypesTheSame()
    {
        assertSameKeys("REM Log in\nGUI r\nDELAY 500\nSTRINGLN ssh admin@server\n" +
                       "DELAY 1500\nSTRINGLN {Server.password}\nENTER");
        assertSameKeys("DEFAULT_DELAY 100\nTAB\nREPEAT 2\nSTRING a{b}{{c\n" +
                       "CTRL-ALT DELETE\nSHIFT TAB\nGUI");
        assertSameKeys("STRING {ENTER} is text in Ducky\nCTRL +");
    }
}
