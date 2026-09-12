package net.tjado.passwdsafe;

import android.annotation.SuppressLint;
import androidx.core.content.ContextCompat;
import android.content.pm.ServiceInfo;
import androidx.core.app.ServiceCompat;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import net.tjado.bluetooth.BluetoothDeviceListing;
import net.tjado.bluetooth.BluetoothUtils;
import net.tjado.bluetooth.HidDeviceController;
import net.tjado.passwdsafe.lib.ActContext;
import net.tjado.passwdsafe.lib.ApiCompat;
import net.tjado.passwdsafe.lib.PasswdSafeUtil;
import net.tjado.bluetooth.BluetoothDeviceWrapper;
import net.tjado.passwdsafe.lib.Utils;

import static android.app.Notification.DEFAULT_SOUND;
import static android.app.Notification.DEFAULT_VIBRATE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;


@SuppressLint({"MissingPermission", "NewApi"})
public class BluetoothForegroundService extends Service {

    private static final String TAG = "BluetoothFgrndService";
    private static final String CHANNEL_ID = "BluetoothServiceChannelQuiet";
    private static final String OLD_CHANNEL_ID = "BluetoothServiceChannel";
    private static final int MAIN_NOTIFICATION_ID = 1;
    private static final int REQUEST_NOTIFICATION_ID = 2;
    private static boolean openFileStarted = false;
    private static final Object mLock = new Object();
    private boolean hidRegistered = false;
    private boolean oneTimeInitDone = false;
    private boolean isInitPhase = false;

    private boolean isBtProfileAlreadyRegistered = false;

    private final IBinder binder = new BluetoothForegroundBinder();

    private HidDeviceController hidDeviceController;
    private BluetoothDeviceListing bluetoothDeviceListing;
    private BluetoothDevice pairingDevice = null;
    private BtServiceProfileListener profileListener = null;

    private byte[] keyboardOutput = null;

    final private Handler openFileResetHandler = new Handler();
    final private Handler initAppRegistrationHandler = new Handler();

    final private int OPEN_FILE_TIMEOUT_MS = 20 * 1000;
    final private int INIT_APP_REGISTRATION_TIMEOUT_MS = 2 * 1000;
    // registerApp() fails with "app is not foreground" if it runs before the
    // process reaches foreground importance after startForeground. Retry a few
    // times with a short delay to win that cold-start race deterministically.
    final private int REG_RETRY_MS = 400;
    final private int MAX_REG_ATTEMPTS = 6;
    private int registrationAttempts = 0;

    private NotificationManagerCompat notificationManager = null;
    private NotificationCompat.Builder serviceNotificationBuilder = null;

    SharedPreferences prefs = null;


    private final BroadcastReceiver btStatusBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                checkBluetoothState(state);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        prefs = Preferences.getSharedPrefs(getApplicationContext());

        // A service needs to manage its own lifecycle - if Bluetooth gets deactivated the service
        // needs to terminate itself. It can't be done by the activity as it might be not running.
        IntentFilter btStatusIntentFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        // RECEIVER_EXPORTED on purpose: Bluetooth broadcasts are sent by the Bluetooth
        // process, not the system uid, so Android drops them for non-exported receivers.
        // They are protected broadcasts; no third-party app can send them.
        ContextCompat.registerReceiver(this, btStatusBroadcastReceiver, btStatusIntentFilter,
                                       ContextCompat.RECEIVER_EXPORTED);

        createNotificationChannel();
        oneTimeInitDone = true;
        PasswdSafeUtil.dbginfo(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Called every time the activity (re)starts the foreground service. Go
        // foreground first (required for registerApp) then ensure the HID app is
        // registered. Idempotent: if registration is already confirmed or in
        // progress, this only refreshes the notification. This is what lets a
        // return to the foreground recover a registration that failed earlier
        // (e.g. the first attempt ran behind the lock screen).
        PasswdSafeUtil.dbginfo(TAG,"Executing onStartCommand - " + intent);
        if (!oneTimeInitDone) {
            // Defensive: onCreate should have run first via BIND_AUTO_CREATE.
            prefs = Preferences.getSharedPrefs(getApplicationContext());
            createNotificationChannel();
            oneTimeInitDone = true;
        }

        showBroadcastNotification();

        if (!hidRegistered && !isInitPhase) {
            registrationAttempts = 0;
            setHid();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        stopForegroundService();
    }

    public void stopForegroundService() {
        PasswdSafeUtil.dbginfo(TAG, "Stop foreground service.");

        endBroadcast();

        try {
            unregisterReceiver(btStatusBroadcastReceiver);
        } catch (Exception ignored) {}

        // Cancel any queued registration retry / file-reset callbacks so a
        // stopped service cannot re-register the HID profile after stopSelf().
        initAppRegistrationHandler.removeCallbacksAndMessages(null);
        openFileResetHandler.removeCallbacksAndMessages(null);
        isInitPhase = false;
        registrationAttempts = 0;

        hidRegistered = false;
        oneTimeInitDone = false;
        // Stop foreground service and remove the notification.
        stopForeground(true);

        // Stop the foreground service.
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    final class BluetoothForegroundBinder extends Binder {
        BluetoothForegroundService getService() {
            return BluetoothForegroundService.this;
        }
    }

    private void showBroadcastNotification() {
        Intent notificationIntent = new Intent(this, PasswdSafe.class);
        notificationIntent.setAction(Intent.ACTION_MAIN);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, (int) System.currentTimeMillis(), notificationIntent, FLAG_UPDATE_CURRENT + ApiCompat.getPendingIntentImmutableFlag());
        //NotificationCompat.Action action = new NotificationCompat.Action(R.drawable.ic_action_lock, "START/STOP", pendingIntent);

        serviceNotificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_body_init))
                .setSmallIcon(R.drawable.selector_menu_policies)
                .setContentIntent(pendingIntent)
                //.addAction(action)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true);

        ServiceCompat.startForeground(
                this, MAIN_NOTIFICATION_ID, serviceNotificationBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }

    private void endBroadcast() {
        if(hidDeviceController != null && profileListener != null) {
            hidDeviceController.unregister(profileListener);
            profileListener = null;
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void setHid() {
        isInitPhase = true;

        profileListener = new BtServiceProfileListener();
        hidDeviceController = HidDeviceController.getInstance();

        if(Preferences.getBluetoothFidoEnabled(prefs)) {
            hidDeviceController.registerFido(getApplicationContext(), profileListener);
        } else {
            hidDeviceController.registerKeyboard(getApplicationContext(), profileListener);
        }

        bluetoothDeviceListing = new BluetoothDeviceListing(getApplicationContext());

        initAppRegistrationHandler.postDelayed(this::retryRegistrationIfNeeded, REG_RETRY_MS);
    }

    /**
     * Called a short time after a registration attempt. If the Bluetooth stack
     * has not confirmed registration (isInitPhase still true), tear down and
     * try again: by now the process has usually reached foreground importance,
     * so registerApp() succeeds. Give up after MAX_REG_ATTEMPTS.
     */
    private void retryRegistrationIfNeeded() {
        if (!isInitPhase) {
            // Registration confirmed via onAppStatusChanged; nothing to do.
            return;
        }

        registrationAttempts++;
        if (registrationAttempts >= MAX_REG_ATTEMPTS) {
            PasswdSafeUtil.dbginfo(TAG, "HID registration gave up after " + registrationAttempts + " attempts");
            isInitPhase = false;
            isBtProfileAlreadyRegistered = true;
            updateServiceNotificationContent(getString(R.string.notification_body_bt_unclean));
            return;
        }

        PasswdSafeUtil.dbginfo(TAG, "Retrying HID registration, attempt " + registrationAttempts);
        if (hidDeviceController != null && profileListener != null) {
            hidDeviceController.unregister(profileListener);
        }
        setHid();
    }

    private void createNotificationChannel() {
        // IMPORTANCE_MIN: the foreground-service notification Android requires
        // is still posted, but it shows no status-bar icon and no heads-up, and
        // sits collapsed at the bottom of the shade. That removes the intrusive
        // "running in the background" banner while keeping the service (and its
        // HID registration, which needs foreground importance) alive.
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Authorizer Bluetooth Service",
                NotificationManager.IMPORTANCE_MIN
        );
        serviceChannel.setShowBadge(false);
        serviceChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        notificationManager = NotificationManagerCompat.from(this);
        // Drop the old DEFAULT-importance channel so its status-bar notification
        // disappears; importance cannot be lowered on an existing channel.
        notificationManager.deleteNotificationChannel(OLD_CHANNEL_ID);
        notificationManager.createNotificationChannel(serviceChannel);
    }

    private void updateServiceNotificationContent(String text) {
        if(notificationManager != null && text != null && serviceNotificationBuilder != null) {
            serviceNotificationBuilder.setContentText(text);
            notificationManager.notify(MAIN_NOTIFICATION_ID, serviceNotificationBuilder.build());
        }
    }

    private void showRequestNotification(){
        Intent intent = new Intent(this, PasswdSafe.class);
        intent.setAction(Intent.ACTION_MAIN);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,0, intent, FLAG_UPDATE_CURRENT + ApiCompat.getPendingIntentImmutableFlag());
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.selector_menu_policies)
                .setContentTitle(getString(R.string.notification_title_actionrequired))
                .setContentText(getString(R.string.notification_body_actionrequired))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setDefaults(DEFAULT_SOUND | DEFAULT_VIBRATE)
                .setAutoCancel(true)
                .setTimeoutAfter(10000)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);

        notificationManager.notify(REQUEST_NOTIFICATION_ID, builder.build());
    }


    public void pairAsKeyboard(BluetoothDevice device) {
        PasswdSafeUtil.dbginfo(TAG, "Start Keyboard pairing");
        pairingDevice = device;

        hidDeviceController.disconnect();
        requireKeyboardMode();

        // requestConnect will only save the device for the connect after the bt app profile
        // was successfully registered.
        hidDeviceController.requestConnect(device);

        PasswdSafeUtil.dbginfo(TAG, "pref update: " + bluetoothDeviceListing.cacheHidDeviceAsKeyboard(device));
    }

    public void pairAsFido(BluetoothDevice device) {

        if(!Preferences.getBluetoothFidoEnabled(prefs)) {
            PasswdSafeUtil.dbginfo(TAG, "FIDO-mode is deactivated... abort pairing");
            return;
        }

        PasswdSafeUtil.dbginfo(TAG, "Start FIDO pairing");
        pairingDevice = device;

        hidDeviceController.disconnect();
        // As the standard connect does not work reliable to switch between FIDO devices, this
        // pairing method is also used for connecting to already paired devices. Due to that
        // we enforce the reinitialization of the FIDO Bluetooth profile.
        requireFidoMode(true);

        // requestConnect will only save the device for the connect after the bt app profile
        // was successfully registered.
        hidDeviceController.requestConnect(device);

        PasswdSafeUtil.dbginfo(TAG, "pref update: " + bluetoothDeviceListing.cacheHidDeviceAsFido(device));
    }

    public void requireKeyboardMode() {
        if(!hidDeviceController.isHidKeyboardMode()) {
            hidDeviceController.unregister(profileListener);
            SystemClock.sleep(50);
            hidDeviceController.registerKeyboard(getApplicationContext(), profileListener);
        }
    }

    public void requireFidoMode() {
        requireFidoMode(false);
    }

    public void requireFidoMode(boolean enforce) {
        if((!hidDeviceController.isHidFidoMode() || enforce) && Preferences.getBluetoothFidoEnabled(prefs)) {
            hidDeviceController.unregister(profileListener);
            SystemClock.sleep(50);
            hidDeviceController.registerFido(getApplicationContext(), profileListener);
        }
    }

    public void connectAndType(BluetoothDevice device, byte[] autotypeString) {
        PasswdSafeUtil.dbginfo(TAG, "Connect And Type");
        requireKeyboardMode();

        keyboardOutput = autotypeString;

        BluetoothDevice connected = hidDeviceController.getConnectedDevice();
        if (connected != null && connected.equals(device)
                && hidDeviceController.isHidKeyboardMode()) {
            // Already connected to this keyboard host. requestConnect would not
            // produce a fresh STATE_CONNECTED callback, so the send that the
            // callback normally performs would never happen. Send directly on a
            // worker thread instead (HID writes block and must never run on the
            // main thread).
            final BluetoothDevice target = device;
            new Thread(() -> autotypeToConnectedHost(target), "BtAutotype").start();
        } else {
            // Not connected yet: connect and let the STATE_CONNECTED callback
            // perform the send once the link is up.
            hidDeviceController.requestConnect(device);
        }
    }

    /**
     * Send the pending keyboard output to an already-connected host. Mirrors the
     * send performed from onConnectionStateChanged and clears keyboardOutput so
     * a stray CONNECTED callback cannot repeat it.
     */
    // Runs on a dedicated worker thread; sendToKeyboardHost is @WorkerThread
    // while requireFidoMode is @MainThread. This mirrors the pre-existing send
    // in onConnectionStateChanged, which carries the same suppression.
    @SuppressLint("ThreadConstraint")
    private void autotypeToConnectedHost(BluetoothDevice device) {
        synchronized (mLock) {
            if (keyboardOutput == null) {
                return;
            }
            if (bluetoothDeviceListing == null
                    || !bluetoothDeviceListing.isKeyboardHost(device)) {
                return;
            }
            byte[] out = keyboardOutput;
            keyboardOutput = null;
            SystemClock.sleep(100);
            hidDeviceController.sendToKeyboardHost(out);
            SystemClock.sleep(500);
            requireFidoMode();
        }
    }

    public BluetoothDevice getConnectedDevice() {
        return hidDeviceController.getConnectedDevice();
    }

    public boolean isAppRegistered() {
        return !isBtProfileAlreadyRegistered /*&& hidDeviceController.getRegisterAppStatus()*/;
    }

    private void checkBluetoothState(Integer state) {
        if (state == null) {
            BluetoothManager bluetoothManager = (BluetoothManager) getApplication().getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter btAdapter = bluetoothManager.getAdapter();
            if (btAdapter == null) {
                return;
            }

            state = btAdapter.getState();
        }

        if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
            PasswdSafeUtil.dbginfo(TAG, "BluetoothAdapter.STATE_OFF // STATE_TURNING_OFF");
            stopForegroundService();
        }
    }

    private final class BtServiceProfileListener implements HidDeviceController.ProfileListener {
        @Override
        public void onAppStatusChanged(boolean registered) {
            /*
             * If we are registered, we have an active controller and a default device
             * we proceed in try to connect with the default HID host. Since we do not
             * know if the device is in range and this may fail, we need to handle it.
             */
            PasswdSafeUtil.dbginfo(TAG, "onAppStatusChanged - registered: " + registered);

            if(isInitPhase && registered) {
                isInitPhase = false;
                registrationAttempts = 0;
                hidRegistered = true;
                isBtProfileAlreadyRegistered = false;
                updateServiceNotificationContent(getString(R.string.notification_body_foregroundservice));
            }

            // Connect to default device in case of app was registered and no pairing is in progress
            if (registered && hidDeviceController != null && pairingDevice == null) {
                BluetoothDeviceWrapper defaultDevice = bluetoothDeviceListing.getHidDefaultDevice();

                if (
                    defaultDevice != null &&
                    (
                            (hidDeviceController.isHidFidoMode() && bluetoothDeviceListing.isFidoHost(defaultDevice)) ||
                            (hidDeviceController.isHidKeyboardMode() && bluetoothDeviceListing.isKeyboardHost(defaultDevice))
                    )
                ){
                    PasswdSafeUtil.dbginfo(TAG, "onAppStatusChanged - requestConnect to default device");
                    hidDeviceController.requestConnect(defaultDevice.getDevice());
                }
            }

            if (registered && pairingDevice != null) {
                pairingDevice = null;
            }

            if (!registered) {
                hidRegistered = false;
            }
            if (!registered && hidDeviceController != null && profileListener != null) {
                PasswdSafeUtil.dbginfo(TAG, "onAppStatusChanged - unregister profileListener");
                hidDeviceController.unregister(profileListener);
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            synchronized (mLock) {
                if (state == BluetoothProfile.STATE_CONNECTED && device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    PasswdSafeUtil.dbginfo(TAG, "onConnectionStateChanged: CONNECTED: " + BluetoothUtils.getDeviceDisplayName(device));

                    if (
                        hidDeviceController.isHidKeyboardMode() &&
                        pairingDevice == null &&
                        bluetoothDeviceListing.isKeyboardHost(device) &&
                        keyboardOutput != null
                    ){
                        PasswdSafeUtil.dbginfo(TAG, "onConnectionStateChanged: initiate HID Keyboard Autotype");
                        // This callback is delivered on the main thread
                        // (HidDeviceApp re-posts it via a main-looper Handler),
                        // and the send holds the report for hundreds of ms, so
                        // it must run on a worker thread. autotypeToConnectedHost
                        // clears keyboardOutput so it cannot be sent twice.
                        final BluetoothDevice target = device;
                        new Thread(() -> autotypeToConnectedHost(target), "BtAutotype").start();

                    } else if(pairingDevice != null && pairingDevice.equals(device)) {
                        // pairing seems to be successful
                        pairingDevice = null;
                        requireFidoMode();
                    }
                } else if (state == BluetoothProfile.STATE_DISCONNECTED
                           && keyboardOutput != null
                           && hidDeviceController.getConnectedDevice() == null) {
                    // The connect issued by connectAndType failed (host off or
                    // out of range) or the link dropped before the send ran.
                    // Drop the pending output so it cannot be typed into
                    // whatever host connects next, and tell the user; the
                    // controller has already given up retrying.
                    PasswdSafeUtil.dbginfo(TAG, "onConnectionStateChanged: DISCONNECTED with pending autotype, discarding");
                    keyboardOutput = null;
                    String name = BluetoothUtils.getDeviceDisplayName(device);
                    Toast.makeText(getApplicationContext(),
                                   getString(R.string.bt_autotype_connect_failed, name),
                                   Toast.LENGTH_LONG).show();
                }
            }
        }

        @Override
        public void onInterruptData(BluetoothDevice device, int reportId, byte[] data, BluetoothHidDevice inputHost) {

            if(!hidDeviceController.isHidFidoMode()) {
                PasswdSafeUtil.dbginfo(TAG, "onInterruptData - received data in non-FIDO mode... aborting");
                return;
            }

            if(!Preferences.getBluetoothFidoEnabled(prefs)) {
                PasswdSafeUtil.dbginfo(TAG, "onInterruptData - received data with FIDO disabled... aborting");
                return;
            }

            PasswdSafe activity = ((PasswdSafeApp) getApplication()).getActiveActivity();
            if (PasswdSafe.mTransactionManager != null && activity != null && activity.isFileOpen() && !activity.isEditMode()) {
                openFileStarted = false;

                PasswdSafe.mTransactionManager.handleReport(data, (rawReports) -> {
                    for (byte[] report : rawReports) {
                        inputHost.sendReport(device, reportId, report);
                    }
                });
            } else {
                if (activity != null && activity.isEditMode()) {
                    PasswdSafeUtil.dbginfo(TAG, "App is open - notify user inside app");
                    PasswdSafeUtil.showErrorMsg(getString(R.string.fido_file_closed), new ActContext(activity));

                } else if(activity != null && !openFileStarted) {
                    PasswdSafeUtil.dbginfo(TAG, "App is open - notify user inside app");

                    // setting flag that file opening getting triggered on multiple interrupts of
                    // one authentication request
                    openFileStarted = true;
                    // reset the variable in case of subsequent authentication requests
                    openFileResetHandler.postDelayed(() -> openFileStarted = false, OPEN_FILE_TIMEOUT_MS);

                    if (!activity.openDefaultFile()) {
                        PasswdSafeUtil.showErrorMsg(getString(R.string.fido_file_closed), new ActContext(activity));
                    }
                }

                PasswdSafeUtil.dbginfo(TAG, "Notification sent to user!");
                showRequestNotification();
            }
        }

        @Override
        public void onServiceStateChanged(BluetoothProfile proxy) {}
    }
}