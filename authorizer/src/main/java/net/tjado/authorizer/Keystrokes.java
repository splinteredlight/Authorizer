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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An ordered list of 8-byte keyboard reports and pauses, ready to send to
 * a host over USB or Bluetooth.
 *
 * <p>Each report is one key press; the sender follows every report with an
 * all-keys-up release, so a report never needs its own release. Pauses come
 * from script DELAY commands.
 *
 * <p>The reports may spell out a password, so the sender calls
 * {@link #wipe()} once it is done with them.
 */
public final class Keystrokes
{
    /** Length of a boot-keyboard report */
    public static final int REPORT_LEN = 8;

    /** One step: a report to press, or a pause when report is null */
    public static final class Step
    {
        public final byte[] report;
        public final int pauseMs;

        private Step(byte[] report, int pauseMs)
        {
            this.report = report;
            this.pauseMs = pauseMs;
        }

        public boolean isPause()
        {
            return report == null;
        }
    }

    private final List<Step> itsSteps = new ArrayList<>();

    /**
     * Wrap a flat run of 8-byte reports, the format the Bluetooth auto-type
     * path has always built.
     */
    @NonNull
    public static Keystrokes fromReports(@NonNull byte[] reports)
    {
        Keystrokes ks = new Keystrokes();
        for (int i = 0; i + REPORT_LEN <= reports.length; i += REPORT_LEN) {
            ks.addReport(Arrays.copyOfRange(reports, i, i + REPORT_LEN));
        }
        Arrays.fill(reports, (byte)0);
        return ks;
    }

    /** Add a key press; the array is copied */
    public void addReport(@NonNull byte[] report)
    {
        if (report.length != REPORT_LEN) {
            throw new IllegalArgumentException("report must be 8 bytes");
        }
        itsSteps.add(new Step(report.clone(), 0));
    }

    /** Add a pause; non-positive values are ignored */
    public void addPause(int ms)
    {
        if (ms > 0) {
            itsSteps.add(new Step(null, ms));
        }
    }

    /** Append all steps of another list (its reports are copied) */
    public void addAll(@NonNull Keystrokes other)
    {
        for (Step step : other.itsSteps) {
            if (step.isPause()) {
                addPause(step.pauseMs);
            } else {
                addReport(step.report);
            }
        }
    }

    @NonNull
    public List<Step> getSteps()
    {
        return itsSteps;
    }

    /** Number of key presses, not counting pauses */
    public int getKeyCount()
    {
        int n = 0;
        for (Step step : itsSteps) {
            if (!step.isPause()) {
                ++n;
            }
        }
        return n;
    }

    public boolean isEmpty()
    {
        return itsSteps.isEmpty();
    }

    /** Zero every report so typed secrets do not linger in the heap */
    public void wipe()
    {
        for (Step step : itsSteps) {
            if (step.report != null) {
                Arrays.fill(step.report, (byte)0);
            }
        }
        itsSteps.clear();
    }
}
