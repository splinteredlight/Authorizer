/*
 * Copyright (©) 2016 Jeff Harris <jefftharris@gmail.com>
 * All rights reserved. Use of the code is allowed under the
 * Artistic License 2.0 terms, as specified in the LICENSE file
 * distributed with this code, or available from
 * http://www.opensource.org/licenses/artistic-license-2.0.php
 */
package net.tjado.passwdsafe;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import com.mikepenz.iconics.Iconics;
import com.mikepenz.iconics.typeface.library.devicon.DevIcon;
import com.mikepenz.iconics.typeface.library.materialdesigniconic.MaterialDesignIconic;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.tjado.passwdsafe.file.PasswdExpiryFilter;
import net.tjado.passwdsafe.file.PasswdFileUri;
import net.tjado.passwdsafe.file.PasswdPolicy;
import net.tjado.passwdsafe.file.PasswdRecordFilter;
import net.tjado.passwdsafe.lib.ApiCompat;
import net.tjado.passwdsafe.lib.PasswdSafeUtil;
import net.tjado.webauthn.Authenticator;
import net.tjado.webauthn.FidoKeyCache;
import net.tjado.webauthn.PasswdSafeCredentialBackend;
import net.tjado.webauthn.TransactionManager;
import net.tjado.webauthn.fido.hid.Framing;

import org.pwsafe.lib.file.PwsFile;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public final class PasswdSafeApp extends Application
    implements SharedPreferences.OnSharedPreferenceChangeListener
{
    public static final String DEBUG_AUTO_FILE =
            null;
            //"/document/primary:test.psafe3";

    public static final String EXPIRATION_TIMEOUT_INTENT =
        "net.tjado.passwdsafe.action.EXPIRATION_TIMEOUT";
    public static final String FILE_TIMEOUT_INTENT =
        "net.tjado.passwdsafe.action.FILE_TIMEOUT";
    public static final String CHOOSE_RECORD_INTENT =
        "net.tjado.passwdsafe.action.CHOOSE_RECORD_INTENT";

    public static final String RESULT_DATA_UUID = "uuid";

    private PasswdPolicy itsDefaultPasswdPolicy = null;
    private NotificationMgr itsNotifyMgr;
    private boolean itsIsOpenDefault = true;
    private final ExecutorService itsThreadExecutor = Executors.newSingleThreadExecutor();

    private PasswdSafe passwdSafeActivity;

    /**
     * FIDO authenticator state lives here, not in the activity, so the
     * Bluetooth service can answer requests while the activity is paused,
     * stopped, or gone. The resumed activity is handed to the transaction
     * manager for prompts, and the activity that holds the open file is
     * handed to it as FidoFileAccess; the two are tracked separately.
     */
    private TransactionManager itsTransactionManager;
    private FidoFileAccess itsFidoFileAccess;
    private FidoKeyCache itsFidoKeyCache;
    private final FidoAuthListener itsFidoAuthListener = new FidoAuthListener();

    private static final String TAG = "PasswdSafeApp";

    /* (non-Javadoc)
     * @see android.app.Application#onCreate()
     */
    @Override
    public void onCreate()
    {
        super.onCreate();
        PasswdRecordFilter.initMatches(getApplicationContext());
        SharedPreferences prefs = Preferences.getSharedPrefs(this);

        AlarmManager alarmMgr =
                (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        itsNotifyMgr = new NotificationMgr(this,
                                           alarmMgr,
                                           getPasswdExpiryNotifPref(prefs));

        prefs.registerOnSharedPreferenceChangeListener(this);

        // Move the fileDirPref from the FileList class to the preferences
        String dirPrefName = "dir";
        SharedPreferences fileListPrefs = getSharedPreferences("FileList",
                                                               MODE_PRIVATE);
        if ((fileListPrefs != null) && fileListPrefs.contains(dirPrefName)) {
            String dirPref = fileListPrefs.getString(dirPrefName, "");
            PasswdSafeUtil.dbginfo(TAG, "Moving dir pref \"%s\" to main",
                                   dirPref);

            SharedPreferences.Editor fileListEdit = fileListPrefs.edit();
            SharedPreferences.Editor prefsEdit = prefs.edit();
            fileListEdit.remove(dirPrefName);
            prefsEdit.putString(Preferences.PREF_FILE_DIR, dirPref);
            fileListEdit.apply();
            prefsEdit.apply();
        }
        Preferences.upgrade(prefs, this);

        Iconics.registerFont(MaterialDesignIconic.INSTANCE);
        Iconics.registerFont(DevIcon.INSTANCE);

        initPrefs(prefs);

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {

            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle bundle) {
                PasswdSafeUtil.dbginfo(TAG, "onActivityCreated: " + activity.getPackageName());
            }
            @Override
            public void onActivityStarted(Activity activity) {
                PasswdSafeUtil.dbginfo(TAG, "onActivityStarted: " + activity.getPackageName());
            }
            @Override
            public void onActivityResumed(Activity activity) {
                PasswdSafeUtil.dbginfo(TAG, "onActivityResumed: " + activity.getPackageName());
                if(activity instanceof PasswdSafe) {
                    passwdSafeActivity = (PasswdSafe) activity;
                    itsFidoFileAccess = (PasswdSafe) activity;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        TransactionManager tm = getTransactionManager();
                        if (tm != null) {
                            tm.updateActivity((PasswdSafe) activity);
                            tm.setFileAccess(itsFidoFileAccess);
                        }
                    }
                }
            }
            @Override
            public void onActivityPaused(Activity activity) {
                PasswdSafeUtil.dbginfo(TAG, "onActivityPaused: " + activity.getPackageName());
                if(activity instanceof PasswdSafe) {
                    passwdSafeActivity = null;
                    // Prompts need a resumed activity; the file stays
                    // reachable until the activity is destroyed.
                    if (itsTransactionManager != null
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        itsTransactionManager.updateActivity(null);
                    }
                }
            }
            @Override
            public void onActivityStopped(Activity activity) {
                PasswdSafeUtil.dbginfo(TAG, "onActivityStopped: " + activity.getPackageName());
            }
            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }
            @Override
            public void onActivityDestroyed(Activity activity) {
                PasswdSafeUtil.dbginfo(TAG, "onActivityDestroyed: " + activity.getPackageName());
                if (activity == itsFidoFileAccess) {
                    itsFidoFileAccess = null;
                    if (itsTransactionManager != null
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        itsTransactionManager.setFileAccess(null);
                    }
                }
            }
        });
    }

    public PasswdSafe getActiveActivity(){
        return passwdSafeActivity;
    }

    /**
     * The FIDO transaction manager, created on first use. Null when the
     * device has no Bluetooth HID support or the authenticator failed to
     * initialise (the next call tries again).
     */
    public synchronized TransactionManager getTransactionManager()
    {
        if (itsTransactionManager != null) {
            return itsTransactionManager;
        }
        if (!ApiCompat.supportsBluetoothHid()
            || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null;
        }
        try {
            PasswdSafeCredentialBackend backend =
                    new PasswdSafeCredentialBackend(this, false, itsFidoFileAccess,
                                                    getFidoKeyCache());
            Authenticator authenticator = new Authenticator(this, false, backend);
            TransactionManager tm = new TransactionManager(passwdSafeActivity, authenticator);
            tm.registerListener((Framing.WebAuthnListener)itsFidoAuthListener);
            tm.registerListener((Framing.U2fAuthnListener)itsFidoAuthListener);
            itsTransactionManager = tm;
        } catch (Exception e) {
            PasswdSafeUtil.info(TAG, "Error initializing authenticator", e);
        }
        return itsTransactionManager;
    }

    /** The FIDO key cache (always present; empty and inert unless enabled) */
    public synchronized FidoKeyCache getFidoKeyCache()
    {
        if (itsFidoKeyCache == null) {
            itsFidoKeyCache = new FidoKeyCache(this);
        }
        return itsFidoKeyCache;
    }

    /**
     * Rebuild the FIDO key cache from the given open file on a background
     * thread. Called when a file opens and when the feature is switched on.
     */
    public void refreshFidoKeyCache(FidoFileAccess file)
    {
        if (!getFidoKeyCache().isEnabled()) {
            return;
        }
        scheduleTask(() -> getFidoKeyCache().refreshFromFile(file), this);
    }

    /**
     * Whether a FIDO request can be answered from the open file right now:
     * a file is open and no record is being edited. The activity does not
     * have to be in front.
     */
    public boolean isFidoFileReady()
    {
        FidoFileAccess file = itsFidoFileAccess;
        return file != null && file.isFileOpen() && !file.isEditMode();
    }

    /** Log-only listener for completed FIDO operations */
    private static final class FidoAuthListener
            implements Framing.WebAuthnListener, Framing.U2fAuthnListener
    {
        @Override
        public void onCompleteMakeCredential()
        {
            PasswdSafeUtil.dbginfo(TAG, "EVENT_ACCOUNTREGISTERED");
        }

        @Override
        public void onCompleteGetAssertion()
        {
            PasswdSafeUtil.dbginfo(TAG, "EVENT_ACCOUNTLOGIN");
        }

        @Override
        public void onRegistrationResponse()
        {
            PasswdSafeUtil.dbginfo(TAG, "EVENT_U2F_REGISTRATION");
        }

        @Override
        public void onAuthenticationResponse()
        {
            PasswdSafeUtil.dbginfo(TAG, "EVENT_U2F_AUTHENTICATION");
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs,
                                          @Nullable String key)
    {
        if (key == null) {
            initPrefs(prefs);
            itsNotifyMgr.setPasswdExpiryFilter(getPasswdExpiryNotifPref(prefs));
        } else {
            PasswdSafeUtil.dbginfo(TAG, "Preference change: %s, value: %s", key,
                                   prefs.getAll().get(key));

            if (Preferences.PREF_PASSWD_ENC.equals(key)) {
                setPasswordEncodingPref(prefs);
            } else if (Preferences.PREF_PASSWD_DEFAULT_SYMS.equals(key)) {
                setPasswordDefaultSymsPref(prefs);
            } else if (Preferences.PREF_PASSWD_EXPIRY_NOTIF.equals(key)) {
                itsNotifyMgr.setPasswdExpiryFilter(getPasswdExpiryNotifPref(prefs));
            }
        }
    }

    public boolean checkOpenDefault()
    {
        if (itsIsOpenDefault) {
            itsIsOpenDefault = false;
            return true;
        } else {
            return false;
        }
    }

    /**
     * Sanitize an intent URI for a file to open. Removes fragments and query
     * params
     */
    public static Uri getOpenUriFromIntent(Intent intent)
    {
        Uri uri = intent.getData();
        if (uri == null) {
            return null;
        }
        Uri.Builder builder = uri.buildUpon();
        builder.fragment("");
        if (uri.isHierarchical()) {
            builder.query("");
        }
        return builder.build();
    }


    /** Get the default password policy */
    public synchronized PasswdPolicy getDefaultPasswdPolicy()
    {
        return itsDefaultPasswdPolicy;
    }


    /** Set the default password policy */
    public synchronized void setDefaultPasswdPolicy(PasswdPolicy policy)
    {
        itsDefaultPasswdPolicy = policy;
        SharedPreferences prefs = Preferences.getSharedPrefs(this);
        Preferences.setDefPasswdPolicyPref(policy, prefs);
    }


    /** Get the notification manager */
    public NotificationMgr getNotifyMgr()
    {
        return itsNotifyMgr;
    }


    /**
     * Setup the theme on an activity
     */
    public static void setupTheme(Activity act)
    {
        setupActTheme(act, false);
    }

    /**
     * Setup the theme on a dialog activity
     */
    public static void setupDialogTheme(Activity act)
    {
        setupActTheme(act, true);
    }

    /**
     * Get pref for display treeview
     */
    public static boolean getDisplayTreeView(Activity act)
    {
        SharedPreferences prefs = Preferences.getSharedPrefs(act);
        return Preferences.getDisplayListTreeView(prefs);
    }

    /**
     * Get a title for a URI
     */
    public static String getAppFileTitle(PasswdFileUri uri, Context ctx)
    {
        return getAppTitle((uri != null) ? uri.getIdentifier(ctx, true) : null, ctx);
    }

    /**
     * Get a title for the application
     */
    public static String getAppTitle(String title, Context ctx)
    {
        StringBuilder builder = new StringBuilder();
        if (!TextUtils.isEmpty(title)) {
            builder.append(title);
            builder.append(" - ");
        }
        builder.append(PasswdSafeUtil.getAppTitle(ctx));
        return builder.toString();
    }

    /**
     * Schedule a background task
     */
    public static void scheduleTask(Runnable run, Context ctx)
    {
        PasswdSafeApp app = (PasswdSafeApp)ctx.getApplicationContext();
        app.itsThreadExecutor.submit(run);
    }

    private static void setPasswordEncodingPref(SharedPreferences prefs)
    {
        PwsFile.setPasswordEncoding(Preferences.getPasswordEncodingPref(prefs));
    }

    /** Set the default password policy symbols from user preferences */
    private static void setPasswordDefaultSymsPref(SharedPreferences prefs)
    {
        PasswdPolicy.setPrefsDefaultSymbols(
                Preferences.getPasswdDefaultSymbolsPref(prefs));
    }

    /** Get the password expiration filter for notifications from a
     * preference */
    private static PasswdExpiryFilter
        getPasswdExpiryNotifPref(SharedPreferences prefs)
    {
        return Preferences.getPasswdExpiryNotifPref(prefs).getFilter();
    }

    /**
     * Setup the theme on a normal or dialog activity
     */
    private static void setupActTheme(Activity act, boolean isDialog)
    {
        int uimode = Configuration.UI_MODE_NIGHT_UNDEFINED;

        SharedPreferences prefs = Preferences.getSharedPrefs(act);
        switch (Preferences.getDisplayTheme(prefs)) {
            case FOLLOW_SYSTEM: {
                uimode = act.getResources().getConfiguration().uiMode &
                         Configuration.UI_MODE_NIGHT_MASK;
                break;
            }
            case LIGHT: {
                uimode = Configuration.UI_MODE_NIGHT_NO;
                break;
            }
            case DARK: {
                uimode = Configuration.UI_MODE_NIGHT_YES;
                break;
            }
        }

        switch (uimode) {
            case Configuration.UI_MODE_NIGHT_NO:
            case Configuration.UI_MODE_NIGHT_UNDEFINED: {
                act.setTheme(isDialog ? R.style.PwsAppTheme_Dialog :
                                     R.style.PwsAppTheme);
                break;
            }
            case Configuration.UI_MODE_NIGHT_YES: {
                act.setTheme(isDialog ? R.style.PwsAppThemeDark_Dialog :
                                     R.style.PwsAppThemeDark);
                break;
            }
        }
    }

    /**
     * Initialize settings from preferences
     */
    private void initPrefs(SharedPreferences prefs)
    {
        setPasswordEncodingPref(prefs);
        setPasswordDefaultSymsPref(prefs);
        itsDefaultPasswdPolicy = Preferences.getDefPasswdPolicyPref(prefs, this);
    }
}
