/*
 * Copyright (©) 2016, 2021 Jeff Harris <jefftharris@gmail.com>
 * All rights reserved. Use of the code is allowed under the
 * Artistic License 2.0 terms, as specified in the LICENSE file
 * distributed with this code, or available from
 * http://www.opensource.org/licenses/artistic-license-2.0.php
 */
package net.tjado.passwdsafe.lib;

import android.app.NotificationManager;
import java.util.ArrayList;
import android.provider.DocumentsContract;
import android.os.PersistableBundle;
import android.content.UriPermission;
import android.content.ClipDescription;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Vibrator;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.List;

/**
 * The ApiCompat class provides a compatibility interface for different Android
 * versions
 */
public final class ApiCompat
{
    // minSdk is 26 (Oreo); constants below that are gone with their shims.
    private static final int SDK_P = 28;
    public static final int SDK_Q = 29;
    private static final int SDK_TIRAMISU = 33;

    public static final int SDK_VERSION = Build.VERSION.SDK_INT;

    /** Set whether the window is visible in the recent apps list */
    public static void setRecentAppsVisible(
            Window w, @SuppressWarnings("SameParameterValue") boolean visible)
    {
        if (visible) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }


    /**
     * API compatible call for Context.getExternalFilesDirs
     */
    @SuppressWarnings("SameParameterValue")
    public static File[] getExternalFilesDirs(Context ctx, String type)
    {
        return ctx.getExternalFilesDirs(type);
    }


    /**
     * Are the external files directories supported
     */
    public static boolean supportsExternalFilesDirs()
    {
        return SDK_VERSION < SDK_Q;
    }




    /**
     * Is the post notifications permission supported
     */
    public static boolean supportsPostNotificationsPermission()
    {
        return SDK_VERSION >= SDK_TIRAMISU;
    }

    /**
     * Is Bluetooth HID supported
     */
    public static boolean supportsBluetoothHid()
    {
        return SDK_VERSION >= SDK_P;
    }

    /**
     * Are notifications enabled
     */
    public static boolean areNotificationsEnabled(
            @NonNull NotificationManager notifyMgr)
    {
        return notifyMgr.areNotificationsEnabled();
    }


    /** API compatible call for ContentResolver.takePersistableUriPermission */
    public static void takePersistableUriPermission(ContentResolver cr,
                                                    Uri uri,
                                                    int flags)
    {
        try {
            cr.takePersistableUriPermission(uri, flags);
        } catch (SecurityException e) {
            PasswdSafeUtil.dbginfo("ApiCompat", e, "takePersistableUriPermission");
        }
    }


    /** API compatible call for
     * ContentResolver.releasePersistableUriPermission */
    public static void releasePersistableUriPermission(ContentResolver cr,
                                                       Uri uri,
                                                       int flags)
    {
        try {
            cr.releasePersistableUriPermission(uri, flags);
        } catch (SecurityException e) {
            PasswdSafeUtil.dbginfo("ApiCompat", e, "releasePersistableUriPermission");
        }
    }


    /** API compatible call for ContentResolver.getPersistedUriPermissions */
    public static List<Uri> getPersistedUriPermissions(ContentResolver cr)
    {
        List<UriPermission> perms = cr.getPersistedUriPermissions();
        List<Uri> uris = new ArrayList<>(perms.size());
        for (UriPermission perm : perms) {
            uris.add(perm.getUri());
        }
        return uris;
    }




    /** API compatible call for DocumentsContract.deleteDocument */
    public static boolean documentsContractDeleteDocument(ContentResolver cr,
                                                          Uri uri)
    {
        try {
            return DocumentsContract.deleteDocument(cr, uri);
        } catch (Exception e) {
            PasswdSafeUtil.dbginfo("ApiCompat", e, "deleteDocument");
            return false;
        }
    }

    /**
     * API compatible call to get the root URI for the primary storage volume
     */
    public static @Nullable Uri getPrimaryStorageRootUri(@NonNull Context ctx)
    {
        if (SDK_VERSION >= SDK_Q) {
            return ApiCompatQ.getPrimaryStorageRootUri(ctx);
        }
        return null;
    }

    /**
     * Copy text to the clipboard
     */
    public static void copyToClipboard(String str,
                                       boolean sensitive,
                                       Context ctx)
    {
        setClipboardText(str, sensitive, ctx);
    }

    /**
     * Clear the clipboard
     */
    public static void clearClipboard(Context ctx)
    {
        ClipboardManager clipMgr = setClipboardText("", true, ctx);
        if ((clipMgr != null) && (SDK_VERSION >= SDK_P)) {
            ApiCompatP.clearClipboard(clipMgr);
        }
    }

    /**
     * Does the clipboard have text
     */
    public static boolean clipboardHasText(Context ctx)
    {
        ClipboardManager clipMgr = (ClipboardManager)
                ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        return (clipMgr != null) && clipMgr.hasPrimaryClip();
    }

    /**
     * API compatible call for
     * InputMethodManager.shouldOfferSwitchingToNextInputMethod
     */
    public static boolean shouldOfferSwitchingToNextInputMethod(
            InputMethodManager imm,
            IBinder imeToken)
    {
        return imm.shouldOfferSwitchingToNextInputMethod(imeToken);
    }

    /**
     * API compatible call for
     * InputMethodManager.switchToNextInputMethod
     */
    @SuppressWarnings("SameParameterValue")
    public static boolean switchToNextInputMethod(InputMethodManager imm,
                                                  IBinder imeToken,
                                                  boolean onlyCurrentIme)
    {
        return imm.switchToNextInputMethod(imeToken, onlyCurrentIme);
    }

    /**
     * Does the device have a system vibrator
     */
    public static boolean hasVibrator(Context ctx)
    {
        Vibrator vib = (Vibrator)ctx.getSystemService(Context.VIBRATOR_SERVICE);
        return (vib != null) && vib.hasVibrator();
    }

    /**
     * Get the immutable flag for a pending intent
     */
    public static int getPendingIntentImmutableFlag()
    {
        return PendingIntent.FLAG_IMMUTABLE;
    }

    /**
     * Set the text in the clipboard
     * @return The clipboard manager
     */
    private static ClipboardManager setClipboardText(String str,
                                                     boolean sensitive,
                                                     Context ctx)
    {
        ClipboardManager clipMgr = (ClipboardManager)
                ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipMgr != null) {
            ClipData clip = ClipData.newPlainText(null, str);
            if (sensitive) {
                PersistableBundle extras = new PersistableBundle();
                extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }
            clipMgr.setPrimaryClip(clip);
        }
        return clipMgr;
    }
}
