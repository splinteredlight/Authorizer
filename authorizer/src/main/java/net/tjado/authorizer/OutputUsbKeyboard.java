/**
 * Authorizer
 *
 *  Copyright 2016 by Tjado Mäcke <tjado@maecke.de>
 *  Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */
package net.tjado.authorizer;

import androidx.annotation.NonNull;

import net.tjado.authorizer.hid.HidGadgetSetup;
import net.tjado.authorizer.hid.HidNotReadyException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.NoSuchElementException;

/**
 * Types text as a USB HID boot keyboard by writing 8-byte reports straight
 * to the gadget character device (/dev/hidgN) as the app's own UID.
 * No root is involved on this path; see {@link HidGadgetSetup} for the
 * one-time preparation of the node.
 *
 * <p>Timing: a write to /dev/hidgN only returns once the host has fetched
 * the report, so back-to-back writes deliver one report per USB poll
 * (1 ms). Real keyboards never do that, and Windows, KVM switches and
 * remote-desktop stacks drop or merge keys at that rate. Every press is
 * therefore held for {@code keyDelayMs} and followed by an equally long
 * pause after the release. Callers must not use this class on the main
 * thread: a write blocks for as long as the host is not polling.
 */
public class OutputUsbKeyboard implements OutputInterface
{
    private static final String TAG = "OutputUsbKeyboard";

    /** Default hold time of a key and pause after it, in milliseconds */
    public static final int DEFAULT_KEY_DELAY_MS = 10;

    /**
     * Pause after the initial all-keys-up report. Gives a host that was in
     * USB selective suspend time to resume before the first real key.
     */
    private static final int SYNC_SETTLE_MS = 50;

    private final String devicePath;
    private final int keyDelayMs;
    private FileOutputStream device;
    private UsbHidKbd kbdKeyInterpreter;

    public OutputUsbKeyboard(OutputInterface.Language lang)
            throws IOException
    {
        this(HidGadgetSetup.DEFAULT_DEVICE, lang, DEFAULT_KEY_DELAY_MS);
    }

    public OutputUsbKeyboard(@NonNull String devicePath,
                             OutputInterface.Language lang)
            throws IOException
    {
        this(devicePath, lang, DEFAULT_KEY_DELAY_MS);
    }

    /**
     * Open the gadget node and send one all-keys-up report.
     *
     * @param keyDelayMs how long each key is held and how long to pause
     *                   after releasing it; 0 disables all pacing
     * @throws HidNotReadyException if the node is missing or not writable
     * @throws IOException if the first report cannot be sent, typically
     *                     because the phone is not connected to a host
     *                     (ESHUTDOWN)
     */
    public OutputUsbKeyboard(@NonNull String devicePath,
                             OutputInterface.Language lang,
                             int keyDelayMs)
            throws IOException
    {
        this.devicePath = devicePath;
        this.keyDelayMs = Math.max(0, keyDelayMs);
        if (!new File(devicePath).exists()) {
            throw new HidNotReadyException(
                    HidNotReadyException.Reason.NOT_FOUND, devicePath,
                    "USB HID device " + devicePath + " not found");
        }
        setLanguage(lang);
        try {
            device = new FileOutputStream(devicePath);
        } catch (FileNotFoundException | SecurityException e) {
            // EACCES (DAC or SELinux) surfaces here as FileNotFoundException
            throw new HidNotReadyException(
                    HidNotReadyException.Reason.NO_PERMISSION, devicePath,
                    "No permission to write " + devicePath + ": " +
                    e.getMessage());
        }
        try {
            sync();
        } catch (IOException e) {
            destruct();
            throw e;
        }
    }

    /**
     * Send an all-keys-up report and let the host settle. This clears any
     * key the host still considers held from an earlier, interrupted run
     * (a stuck Shift types everything in upper case) and, if the USB link
     * was suspended, wakes it so the first real key is not the one that
     * gets lost during resume.
     */
    private void sync() throws IOException
    {
        device.write(kbdKeyInterpreter.getScancode(null));
        pause(Math.max(SYNC_SETTLE_MS, keyDelayMs));
    }

    private static void pause(int ms) throws IOException
    {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("auto-type interrupted");
        }
    }

    public void destruct()
    {
        try {
            if (device != null) {
                device.close();
            }
        } catch (IOException ignored) {
        }
        device = null;
    }

    public boolean setLanguage(OutputInterface.Language lang)
    {
        String className = "net.tjado.authorizer.UsbHidKbd_" + lang;
        try {
            kbdKeyInterpreter = (UsbHidKbd)Class.forName(className)
                                                .getDeclaredConstructor()
                                                .newInstance();
            Utilities.dbginfo(TAG, "Set language " + lang);
            return true;
        } catch (Exception e) {
            Utilities.dbginfo(TAG, "Language " + lang + " not found");
            kbdKeyInterpreter = new UsbHidKbd_en_US();
            return false;
        }
    }

    /**
     * Write one report, hold it, then write the all-zero release report and
     * pause again. The release is sent even if the press or the hold fails,
     * so a key can never stay held on the host.
     */
    private void writeReport(byte[] report) throws IOException
    {
        try {
            device.write(report);
            pause(keyDelayMs);
        } finally {
            device.write(kbdKeyInterpreter.getScancode(null));
        }
        pause(keyDelayMs);
    }

    public int sendText(String output) throws IOException
    {
        int ret = 0;
        for (int i = 0; i < output.length(); i++) {
            String ch = String.valueOf(output.charAt(i));
            try {
                writeReport(kbdKeyInterpreter.getScancode(ch));
            } catch (NoSuchElementException e) {
                Utilities.dbginfo(TAG, "character mapping not found");
                ret = 1;
            }
        }
        return ret;
    }

    public int sendSingleKey(String keyName) throws IOException
    {
        try {
            writeReport(kbdKeyInterpreter.getScancode(keyName));
            return 0;
        } catch (NoSuchElementException e) {
            Utilities.dbginfo(TAG, "'" + keyName + "' mapping not found");
            return 1;
        }
    }

    public int sendReturn() throws IOException
    {
        return sendSingleKey("return");
    }

    public int sendTabulator() throws IOException
    {
        return sendSingleKey("tab");
    }

    public void sendScancode(byte[] output) throws IOException
    {
        if (output.length == 8) {
            writeReport(output);
        } else if (output.length == 1) {
            writeReport(new byte[]{0x00, 0x00, output[0], 0x00, 0x00, 0x00,
                                   0x00, 0x00});
        }
    }

    @NonNull
    public String getDevicePath()
    {
        return devicePath;
    }

    public int getKeyDelayMs()
    {
        return keyDelayMs;
    }
}
