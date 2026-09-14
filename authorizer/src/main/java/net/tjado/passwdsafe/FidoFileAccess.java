/*
 * Copyright (©) 2026 Authorizer contributors
 * All rights reserved. Use of the code is allowed under the
 * GNU General Public License v3.0
 */
package net.tjado.passwdsafe;

import net.tjado.passwdsafe.file.PasswdFileDataUser;
import net.tjado.passwdsafe.view.EditRecordResult;

/**
 * What the FIDO credential backend needs from whoever holds the open
 * password file. The activity implements it today; keeping the backend
 * behind this interface lets FIDO requests be answered without a resumed
 * activity, and lets a cache stand in when no file is open at all.
 */
public interface FidoFileAccess
{
    /** Whether a file is currently open */
    boolean isFileOpen();

    /** Whether the open file can be written */
    boolean isFileWritable();

    /** Whether a record is being edited (writes must not race the editor) */
    boolean isEditMode();

    /** Use the open file data; returns null when no file is open */
    <RetT> RetT useFileData(PasswdFileDataUser<RetT> user);

    /** Persist a record added or changed by the FIDO backend */
    void finishEditFidoRecord(EditRecordResult result);
}
