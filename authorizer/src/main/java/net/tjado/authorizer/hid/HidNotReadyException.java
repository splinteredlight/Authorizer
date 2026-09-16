/*
 * Authorizer
 *
 * Copyright 2026 Authorizer contributors
 * Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */
package net.tjado.authorizer.hid;

import java.io.IOException;

/**
 * The USB HID gadget character device cannot be used from this process.
 * Carries a reason so callers can decide whether a root setup pass may fix it.
 */
public class HidNotReadyException extends IOException
{
    public enum Reason
    {
        /** The device node does not exist (no HID function in the gadget) */
        NOT_FOUND,
        /** The node exists but this UID/SELinux domain may not open it */
        NO_PERMISSION
    }

    private final Reason itsReason;
    private final String itsDevicePath;

    public HidNotReadyException(Reason reason, String devicePath, String msg)
    {
        super(msg);
        itsReason = reason;
        itsDevicePath = devicePath;
    }

    public Reason getReason()
    {
        return itsReason;
    }

    public String getDevicePath()
    {
        return itsDevicePath;
    }
}
