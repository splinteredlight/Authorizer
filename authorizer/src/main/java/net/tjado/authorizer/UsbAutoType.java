/*
 * Authorizer
 *
 * Copyright 2026 Authorizer contributors
 * Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */
package net.tjado.authorizer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.tjado.authorizer.hid.HidNotReadyException;
import net.tjado.passwdsafe.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Runs a USB auto-type job off the main thread.
 *
 * <p>Writes to /dev/hidgN block until the host polls, and every key is now
 * held and spaced by a few milliseconds, so a credential takes a visible
 * fraction of a second to type. Doing that on the main thread froze the UI
 * and would ANR outright when the host stopped polling (suspended or
 * unplugged). All jobs share one worker so two taps can never interleave
 * their reports on the same node.
 *
 * <p>The sequence is built on the caller's thread (it needs record fields
 * and view state), typed on the worker, and the outcome is delivered back
 * on the main thread.
 */
public final class UsbAutoType
{
    private static final ExecutorService itsExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "usb-autotype");
                t.setDaemon(true);
                return t;
            });
    private static final Handler itsMainHandler =
            new Handler(Looper.getMainLooper());

    /** Result of a job, delivered on the main thread */
    public interface Callback
    {
        /**
         * @param error null on success; a {@link HidNotReadyException} if
         *              the node could not be opened, any other
         *              {@link IOException} if a report could not be sent
         *              (usually no host on the other end of the cable)
         * @param lostChars true if at least one character had no mapping
         *                  in the selected keyboard layout and was skipped
         */
        void onFinished(@Nullable Exception error, boolean lostChars);
    }

    /** One step of a job: a run of text or a single named key */
    private static final class Step
    {
        final String text;
        final String key;

        Step(String text, String key)
        {
            this.text = text;
            this.key = key;
        }
    }

    /** Ordered keystrokes to type */
    public static final class Sequence
    {
        private final List<Step> itsSteps = new ArrayList<>();
        private final String itsSubReturn;
        private final String itsSubTab;
        private final Pattern itsSplit;

        /**
         * @param subReturn the placeholder that stands for the Return key
         *                  inside record fields, e.g. "{RET}"
         * @param subTab the placeholder for the Tab key, e.g. "{TAB}"
         */
        public Sequence(@NonNull String subReturn, @NonNull String subTab)
        {
            itsSubReturn = subReturn;
            itsSubTab = subTab;
            String qr = Pattern.quote(subReturn);
            String qt = Pattern.quote(subTab);
            itsSplit = Pattern.compile(String.format(
                    "((?<=(%1$s|%2$s))|(?=(%1$s|%2$s)))", qr, qt));
        }

        /**
         * Add a field, expanding the Return and Tab placeholders into key
         * presses and typing everything else literally.
         */
        @NonNull
        public Sequence addField(@Nullable String field)
        {
            if ((field == null) || field.isEmpty()) {
                return this;
            }
            for (String part : itsSplit.split(field)) {
                if (part.equals(itsSubReturn)) {
                    addReturn();
                } else if (part.equals(itsSubTab)) {
                    addTab();
                } else if (!part.isEmpty()) {
                    itsSteps.add(new Step(part, null));
                }
            }
            return this;
        }

        @NonNull
        public Sequence addReturn()
        {
            itsSteps.add(new Step(null, "return"));
            return this;
        }

        @NonNull
        public Sequence addTab()
        {
            itsSteps.add(new Step(null, "tab"));
            return this;
        }

        public boolean isEmpty()
        {
            return itsSteps.isEmpty();
        }
    }

    private UsbAutoType()
    {
    }

    /**
     * Turn a job outcome into a user-facing message.
     * @return the message to show, or null if the job succeeded in full
     */
    @Nullable
    public static String errorMessage(@NonNull Context ctx,
                                      @Nullable Exception error,
                                      boolean lostChars)
    {
        if (error instanceof HidNotReadyException) {
            HidNotReadyException e = (HidNotReadyException)error;
            int res = (e.getReason() == HidNotReadyException.Reason.NOT_FOUND) ?
                      R.string.autotype_usb_hidg_not_found :
                      R.string.autotype_usb_hidg_no_access;
            return ctx.getString(res, e.getDevicePath());
        }
        if (error != null) {
            String detail = error.getLocalizedMessage();
            if (detail == null) {
                detail = error.getClass().getSimpleName();
            }
            return ctx.getString(R.string.autotype_usb_write_failed, detail);
        }
        if (lostChars) {
            return ctx.getString(R.string.autotype_lost_chars);
        }
        return null;
    }

    /**
     * Type the sequence on the worker thread and report on the main thread.
     */
    @MainThread
    public static void run(@NonNull String devicePath,
                           @NonNull OutputInterface.Language lang,
                           int keyDelayMs,
                           @NonNull Sequence seq,
                           @NonNull Callback cb)
    {
        itsExecutor.execute(() -> {
            Exception error = null;
            boolean lost = false;
            OutputUsbKeyboard kbd = null;
            try {
                kbd = new OutputUsbKeyboard(devicePath, lang, keyDelayMs);
                for (Step step : seq.itsSteps) {
                    int ret;
                    if (step.key != null) {
                        ret = kbd.sendSingleKey(step.key);
                    } else {
                        ret = kbd.sendText(step.text);
                    }
                    if (ret != 0) {
                        lost = true;
                    }
                }
            } catch (Exception e) {
                error = e;
            } finally {
                if (kbd != null) {
                    kbd.destruct();
                }
            }
            final Exception fErr = error;
            final boolean fLost = lost;
            itsMainHandler.post(() -> cb.onFinished(fErr, fLost));
        });
    }
}
