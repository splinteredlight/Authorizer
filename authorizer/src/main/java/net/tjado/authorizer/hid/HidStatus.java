/*
 * Authorizer
 *
 * Copyright 2026 Authorizer contributors
 * Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */
package net.tjado.authorizer.hid;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Non-root snapshot of whether the HID gadget node is usable by this process.
 */
public final class HidStatus
{
    public final String devicePath;
    public final boolean exists;
    public final boolean writable;
    /** SELinux context of this process, e.g. u:r:untrusted_app:s0:c1,c2 */
    public final String selinuxContext;

    private HidStatus(String devicePath, boolean exists, boolean writable,
                      String selinuxContext)
    {
        this.devicePath = devicePath;
        this.exists = exists;
        this.writable = writable;
        this.selinuxContext = selinuxContext;
    }

    /** Probe the device node without root */
    @NonNull
    public static HidStatus probe(@NonNull String devicePath)
    {
        File f = new File(devicePath);
        boolean exists = f.exists();
        boolean writable = false;
        if (exists) {
            // canWrite() only checks DAC bits; SELinux is enforced at open()
            try (FileOutputStream ignored = new FileOutputStream(devicePath)) {
                writable = true;
            } catch (IOException | SecurityException e) {
                writable = false;
            }
        }
        return new HidStatus(devicePath, exists, writable,
                             HidGadgetSetup.readOwnSelinuxContext());
    }

    public boolean isReady()
    {
        return exists && writable;
    }

    @NonNull
    @Override
    public String toString()
    {
        return devicePath + (exists ? (writable ? " (ready)" : " (no access)")
                                    : " (missing)");
    }
}
