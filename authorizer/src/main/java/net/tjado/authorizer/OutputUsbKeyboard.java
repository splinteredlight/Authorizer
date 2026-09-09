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
import java.util.NoSuchElementException;

/**
 * Types text as a USB HID boot keyboard by writing 8-byte reports straight
 * to the gadget character device (/dev/hidgN) as the app's own UID.
 * No root is involved on this path; see {@link HidGadgetSetup} for the
 * one-time preparation of the node.
 */
public class OutputUsbKeyboard implements OutputInterface
{
    private static final String TAG = "OutputUsbKeyboard";

    private final String devicePath;
    private FileOutputStream device;
    private UsbHidKbd kbdKeyInterpreter;

    public OutputUsbKeyboard(OutputInterface.Language lang)
            throws HidNotReadyException
    {
        this(HidGadgetSetup.DEFAULT_DEVICE, lang);
    }

    public OutputUsbKeyboard(@NonNull String devicePath,
                             OutputInterface.Language lang)
            throws HidNotReadyException
    {
        this.devicePath = devicePath;
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
     * Write one report followed by the all-zero release report. The release
     * is sent even if the press fails, so a key can never stay held on the
     * host.
     */
    private void writeReport(byte[] report) throws IOException
    {
        try {
            device.write(report);
        } finally {
            device.write(kbdKeyInterpreter.getScancode(null));
        }
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
}
