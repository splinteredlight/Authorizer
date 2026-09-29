package net.tjado.passwdsafe;

import android.annotation.SuppressLint;
import androidx.core.content.ContextCompat;
import android.content.pm.ServiceInfo;
import androidx.core.app.ServiceCompat;
import android.app.Notification;
import android.os.Build;
import android.content.pm.PackageManager;
import android.Manifest;
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
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.tjado.authorizer.Keystrokes;
import net.tjado.bluetooth.BluetoothDeviceListing;
import net.tjado.bluetooth.BluetoothUtils;
import net.tjado.bluetooth.HidDeviceController;
import net.tjado.passwdsafe.lib.ActContext;
import net.tjado.passwdsafe.lib.ApiCompat;
import net.tjado.passwdsafe.lib.PasswdSafeUtil;
import net.tjado.bluetooth.BluetoothDeviceWrapper;
import net.tjado.passwdsafe.lib.Utils;
import net.tjado.webauthn.TransactionManager;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
    /**
     * Host a user-driven pairAsKeyboard()/pairAsFido() is connecting to.
     * While set, the automatic reconnect must not dial: it would steal the
     * link. Cleared only when this host reports CONNECTED or DISCONNECTED
     * (or the service goes down), never merely because the profile
     * re-registered, since the connect is issued after that.
     */
    private BluetoothDevice pairingDevice = null;
    /**
     * True while the pairing connect is postponed behind the profile
     * re-registration; onAppStatusChanged issues it. A DISCONNECTED for the
     * pairing host in that window is the teardown of its previous link, not
     * a failed pairing.
     */
    private boolean pairingConnectPending = false;
    private BtServiceProfileListener profileListener = null;

    /** How a Bluetooth typing job ended; delivered on the main thread */
    public enum TypingResult { DONE, CANCELLED, LINK_LOST, CONNECT_FAILED }

    public interface TypingCallback {
        void onTypingFinished(@NonNull TypingResult result);
    }

    private Keystrokes keyboardOutput = null;
    /** Keyboard host that keyboardOutput is destined for; null when none is pending. */
    private BluetoothDevice keyboardOutputTarget = null;
    /** Stop flag and completion callback of the pending or running job */
    private AtomicBoolean keyboardOutputCancel = null;
    private TypingCallback keyboardOutputCallback = null;
    /** True from the moment a job starts sending until it has finished */
    private boolean typingInProgress = false;
    /**
     * Sends run here, one at a time: a script can take minutes, and two
     * jobs must never interleave their reports.
     */
    private final ExecutorService typingExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "BtAutotype"));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    final private Handler openFileResetHandler = new Handler();
    // Automatic reconnect to the paired FIDO hosts. The phone is the HID
    // peripheral and Android does not keep it page-scannable, so the PC never
    // pulls the link back up on its own: after the PC sleeps or the phone
    // leaves range, the link stays down until the phone connects again.
    // Retried with exponential backoff while disconnected in FIDO mode,
    // paging the hosts in turn (last connected first, no default needed),
    // and at once when the system reports a Classic ACL link to one of them.
    final private Handler reconnectHandler = new Handler(Looper.getMainLooper());
    final private long RECONNECT_MIN_MS = 10 * 1000;
    final private long RECONNECT_MAX_MS = 120 * 1000;
    private int reconnectAttempt = 0;
    private boolean reconnectScheduled = false;
    /** Index into the FIDO host list of the host the last attempt paged. */
    private int reconnectHostIndex = -1;
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
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                // Some profile just linked to a device (e.g. the PC came back
                // in range). If it is the host we are waiting for, do not
                // wait out the backoff.
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // EXTRA_TRANSPORT exists from API 33; before that treat the
                // link as unknown, which lets the attempt through.
                int transport = BluetoothDevice.TRANSPORT_AUTO;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    transport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT,
                                                   BluetoothDevice.TRANSPORT_AUTO);
                }
                onAclConnected(device, transport);
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
        btStatusIntentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
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

        if (!hasConnectPermission()) {
            // Nothing can be registered without BLUETOOTH_CONNECT; stay quiet
            // until the activity asks again once the permission is granted.
            PasswdSafeUtil.dbginfo(TAG, "BLUETOOTH_CONNECT not granted, not registering HID");
            stopForegroundService();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!hidRegistered && !isInitPhase) {
            registrationAttempts = 0;
            setHid();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // The activity stops the service when it leaves the screen in
        // keyboard mode; a script must not keep typing after that.
        cancelTyping();
        typingExecutor.shutdown();

        stopForegroundService();
    }

    /** Whether the runtime permission the HID profile needs is granted */
    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT) ==
               PackageManager.PERMISSION_GRANTED;
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
        cancelReconnect();
        clearPairing();
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
        //NotificationCompat.Action action = new NotificationCompat.Action(R.drawable.ic_lock, "START/STOP", pendingIntent);

        serviceNotificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_body_init))
                .setSmallIcon(R.drawable.ic_verified_user)
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
                .setSmallIcon(R.drawable.ic_verified_user)
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

        BluetoothDevice connected = hidDeviceController.getConnectedDevice();
        if (connected != null && connected.equals(device)
                && hidDeviceController.isHidKeyboardMode()) {
            // Already linked to this host as a keyboard, so there is
            // nothing to (re)connect. Going through the disconnect below
            // would race the controller: with no mode switch in between,
            // requestConnect() runs while the old link is still closing,
            // and either takes the "already connected" shortcut on a dying
            // link or has its pending request cancelled by the teardown
            // DISCONNECTED. Either way the phone ended up disconnected.
            PasswdSafeUtil.dbginfo(TAG, "Keyboard host already connected, nothing to do");
            PasswdSafeUtil.dbginfo(TAG, "pref update: " + bluetoothDeviceListing.cacheHidDeviceAsKeyboard(device));
            return;
        }

        pairingDevice = device;
        cancelReconnect();

        hidDeviceController.disconnect();
        requireKeyboardMode();

        // When the profile had to re-register, requestConnect only stores
        // the device and returns false; onAppStatusChanged issues it.
        pairingConnectPending = !hidDeviceController.requestConnect(device);

        PasswdSafeUtil.dbginfo(TAG, "pref update: " + bluetoothDeviceListing.cacheHidDeviceAsKeyboard(device));
    }

    public void pairAsFido(BluetoothDevice device) {

        if(!Preferences.getBluetoothFidoEnabled(prefs)) {
            PasswdSafeUtil.dbginfo(TAG, "FIDO-mode is deactivated... abort pairing");
            return;
        }

        PasswdSafeUtil.dbginfo(TAG, "Start FIDO pairing");
        pairingDevice = device;
        cancelReconnect();

        hidDeviceController.disconnect();
        // As the standard connect does not work reliable to switch between FIDO devices, this
        // pairing method is also used for connecting to already paired devices. Due to that
        // we enforce the reinitialization of the FIDO Bluetooth profile.
        requireFidoMode(true);

        // The profile always re-registers here, so requestConnect only
        // stores the device and returns false; onAppStatusChanged issues it.
        pairingConnectPending = !hidDeviceController.requestConnect(device);

        PasswdSafeUtil.dbginfo(TAG, "pref update: " + bluetoothDeviceListing.cacheHidDeviceAsFido(device));
    }

    /** Pairing finished (either way) or the service is going down. */
    private void clearPairing() {
        pairingDevice = null;
        pairingConnectPending = false;
    }

    public void requireKeyboardMode() {
        if(!hidDeviceController.isHidKeyboardMode()) {
            // Re-registering drops any connect in flight, so a retry queued
            // for the old registration is stale (and would find the
            // controller unregistered). The new registration dials afresh.
            cancelReconnect();
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
            // See requireKeyboardMode.
            cancelReconnect();
            hidDeviceController.unregister(profileListener);
            SystemClock.sleep(50);
            hidDeviceController.registerFido(getApplicationContext(), profileListener);
        }
    }

    public void connectAndType(BluetoothDevice device, byte[] autotypeString) {
        connectAndType(device, Keystrokes.fromReports(autotypeString),
                       new AtomicBoolean(), null);
    }

    /**
     * Connect to a keyboard host (unless already connected) and type.
     *
     * @param cancel set to stop typing; checked between keys and in pauses
     * @param callback told on the main thread how the job ended; may be null
     * @return false, and nothing is typed, if another job is typing. A job
     *         still waiting for its connection is replaced, as before.
     */
    public boolean connectAndType(@NonNull BluetoothDevice device,
                                  @NonNull Keystrokes output,
                                  @NonNull AtomicBoolean cancel,
                                  @Nullable TypingCallback callback) {
        PasswdSafeUtil.dbginfo(TAG, "Connect And Type");
        synchronized (mLock) {
            if (typingInProgress) {
                output.wipe();
                return false;
            }
            if (keyboardOutput != null) {
                notifyTyping(keyboardOutputCallback, TypingResult.CANCELLED);
                keyboardOutput.wipe();
                takeKeyboardOutput();
            }
        }
        cancelReconnect();
        requireKeyboardMode();

        synchronized (mLock) {
            keyboardOutput = output;
            keyboardOutputTarget = device;
            keyboardOutputCancel = cancel;
            keyboardOutputCallback = callback;
        }

        BluetoothDevice connected = hidDeviceController.getConnectedDevice();
        if (connected != null && connected.equals(device)
                && hidDeviceController.isHidKeyboardMode()) {
            // Already connected to this keyboard host. requestConnect would not
            // produce a fresh STATE_CONNECTED callback, so the send that the
            // callback normally performs would never happen. Send directly on a
            // worker thread instead (HID writes block and must never run on the
            // main thread).
            final BluetoothDevice target = device;
            typingExecutor.execute(() -> autotypeToConnectedHost(target));
        } else {
            // Not connected yet: connect and let the STATE_CONNECTED callback
            // perform the send once the link is up.
            hidDeviceController.requestConnect(device);
        }
        return true;
    }

    /** Stop the job that is pending or typing, if any */
    public void cancelTyping() {
        synchronized (mLock) {
            if (keyboardOutputCancel != null) {
                keyboardOutputCancel.set(true);
            }
        }
    }

    /**
     * Take the pending job's output and callback and clear them, so a stray
     * CONNECTED callback cannot send it twice. Caller holds mLock.
     */
    private void takeKeyboardOutput() {
        keyboardOutput = null;
        keyboardOutputTarget = null;
        keyboardOutputCancel = null;
        keyboardOutputCallback = null;
    }

    private void notifyTyping(@Nullable TypingCallback cb, TypingResult result) {
        if (cb != null) {
            mainHandler.post(() -> cb.onTypingFinished(result));
        }
    }

    /**
     * Send the pending keyboard output to a connected host. Runs on
     * typingExecutor, from connectAndType when the host was already
     * connected and from the STATE_CONNECTED callback otherwise.
     *
     * <p>mLock is not held while sending: a script can pause for minutes,
     * and onConnectionStateChanged takes mLock on the main thread.
     */
    // requireFidoMode is @MainThread; the switch back after typing has always
    // run on the typing worker.
    @SuppressLint("ThreadConstraint")
    private void autotypeToConnectedHost(BluetoothDevice device) {
        Keystrokes out;
        AtomicBoolean cancel;
        TypingCallback cb;
        synchronized (mLock) {
            if (keyboardOutput == null) {
                return;
            }
            cb = keyboardOutputCallback;
            if (bluetoothDeviceListing == null
                    || !bluetoothDeviceListing.isKeyboardHost(device)) {
                keyboardOutput.wipe();
                takeKeyboardOutput();
                notifyTyping(cb, TypingResult.CONNECT_FAILED);
                return;
            }
            out = keyboardOutput;
            cancel = keyboardOutputCancel;
            takeKeyboardOutput();
            typingInProgress = true;
        }

        boolean complete;
        try {
            SystemClock.sleep(100);
            complete = hidDeviceController.sendKeystrokes(out, cancel);
        } finally {
            out.wipe();
        }
        SystemClock.sleep(500);
        synchronized (mLock) {
            typingInProgress = false;
            requireFidoMode();
        }
        notifyTyping(cb, complete ? TypingResult.DONE :
                         cancel.get() ? TypingResult.CANCELLED :
                         TypingResult.LINK_LOST);
    }

    public BluetoothDevice getConnectedDevice() {
        return hidDeviceController.getConnectedDevice();
    }

    /** Whether the automatic reconnect may run at all right now. */
    private boolean reconnectAllowed() {
        if (bluetoothDeviceListing == null || hidDeviceController == null) {
            return false;
        }
        if (!hidRegistered || !hidDeviceController.isHidFidoMode()) {
            return false;
        }
        if (!Preferences.getBluetoothFidoEnabled(prefs)
                || !Preferences.getFidoAutoReconnect(prefs)) {
            return false;
        }
        if (pairingDevice != null || keyboardOutput != null || typingInProgress) {
            // A user-driven connect is in flight; it owns the link.
            return false;
        }
        return true;
    }

    /**
     * Paired FIDO hosts an automatic reconnect may dial, in preference
     * order, or an empty list when the loop must not run. Enumerates and
     * hashes every bonded device, so call it once per attempt, not per event.
     */
    private List<BluetoothDeviceWrapper> reconnectCandidates() {
        if (!reconnectAllowed()) {
            return Collections.emptyList();
        }
        return bluetoothDeviceListing.getFidoHostsByPreference();
    }

    /**
     * Queue the next reconnect attempt with backoff. Safe to call repeatedly.
     * The attempt counter advances here, so whoever dials next (the timer or
     * an ACL trigger) uses the delay that belongs to the attempt just made.
     */
    private void scheduleReconnect() {
        if (reconnectScheduled || !reconnectAllowed()
                || !bluetoothDeviceListing.hasFidoHosts()) {
            return;
        }
        long delay = Math.min(RECONNECT_MAX_MS, RECONNECT_MIN_MS << Math.min(reconnectAttempt, 8));
        PasswdSafeUtil.dbginfo(TAG, "scheduleReconnect: attempt " + reconnectAttempt + " in " + delay + " ms");
        reconnectAttempt++;
        reconnectScheduled = true;
        reconnectHandler.postDelayed(this::tryReconnect, delay);
    }

    private void cancelReconnect() {
        reconnectHandler.removeCallbacksAndMessages(null);
        reconnectScheduled = false;
        reconnectAttempt = 0;
        // Next cycle starts at the preferred host again (the one that just
        // connected is first on the list), not at whichever came after it.
        reconnectHostIndex = -1;
    }

    private void tryReconnect() {
        reconnectScheduled = false;
        List<BluetoothDeviceWrapper> hosts = reconnectCandidates();
        if (hosts.isEmpty()) {
            return;
        }
        if (hidDeviceController.getConnectedDevice() != null) {
            // Something else connected meanwhile (e.g. the host itself).
            reconnectAttempt = 0;
            return;
        }
        // Each attempt pages the next host on the list; with one host this
        // is a plain retry, with several it cycles like a multipoint headset.
        reconnectHostIndex = (reconnectHostIndex + 1) % hosts.size();
        PasswdSafeUtil.dbginfo(TAG, "tryReconnect: requestConnect to FIDO host #" + reconnectHostIndex);
        // Outcome arrives via onConnectionStateChanged: CONNECTED resets the
        // backoff, DISCONNECTED (the controller's 15 s timeout included)
        // schedules the next attempt.
        if (!hidDeviceController.requestConnect(hosts.get(reconnectHostIndex).getDevice())) {
            // The profile is between registrations (a mode switch is
            // re-registering it): the controller only stored the request
            // and no callback will follow, so nothing else would re-arm
            // the timer. The registration that completes dials the
            // preferred host itself and cancels this.
            scheduleReconnect();
        }
    }

    private void onAclConnected(BluetoothDevice device, int transport) {
        if (device == null || !reconnectScheduled) {
            return;
        }
        // Every device that links to the phone lands here (watch, earbuds),
        // so answer the common case from one preference read before
        // enumerating the bonded list.
        if (!bluetoothDeviceListing.isFidoHost(device)) {
            return;
        }
        if (transport == BluetoothDevice.TRANSPORT_LE) {
            // BluetoothHidDevice is BR/EDR only; an LE link to a dual-mode
            // PC says nothing about its Classic radio being reachable.
            PasswdSafeUtil.dbginfo(TAG, "ACL link to a FIDO host over LE, ignoring");
            return;
        }
        List<BluetoothDeviceWrapper> hosts = reconnectCandidates();
        for (int i = 0; i < hosts.size(); i++) {
            if (hosts.get(i).getDevice().equals(device)) {
                PasswdSafeUtil.dbginfo(TAG, "ACL link to a FIDO host, reconnecting now");
                reconnectHandler.removeCallbacksAndMessages(null);
                reconnectScheduled = false;
                // This attempt is a fresh start: if the page fails because
                // the host is still bringing its profiles up, the retry
                // should follow at the minimum delay, not the next step.
                reconnectAttempt = 0;
                reconnectHostIndex = i - 1;
                tryReconnect();
                return;
            }
        }
    }

    /** Called by the settings switch; starts or stops the retry loop right away. */
    public void onAutoReconnectPrefChanged(boolean enabled) {
        cancelReconnect();
        if (enabled && hidDeviceController != null
                && hidDeviceController.getConnectedDevice() == null) {
            scheduleReconnect();
        }
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

            if (registered && hidDeviceController != null && pairingDevice != null
                    && pairingConnectPending) {
                // The pairing connect was postponed behind this
                // registration. The controller normally issues it now, but
                // a DISCONNECTED for the same host (the teardown of its
                // previous link, when the user re-pairs to the host that was
                // connected) can arrive first and cancel the postponed
                // request, so issue it here as well; a duplicate connect
                // is answered "already in progress" by the stack.
                PasswdSafeUtil.dbginfo(TAG, "onAppStatusChanged - requestConnect to pairing host");
                pairingConnectPending = false;
                hidDeviceController.requestConnect(pairingDevice);
            }

            // Connect to default device in case of app was registered and no pairing is in progress
            if (registered && hidDeviceController != null && pairingDevice == null) {
                if (hidDeviceController.isHidFidoMode()) {
                    // FIDO: dial the preferred host (last connected, then
                    // default, then any paired FIDO host). A failed page
                    // lands in onConnectionStateChanged and the reconnect
                    // loop moves on to the next host.
                    //
                    // This is a fresh registration, so any retry state from
                    // before it (a queued attempt, a grown backoff) is
                    // stale: with it left in place a failed dial here could
                    // not schedule a retry, and the retry that eventually
                    // fired used the old delay.
                    cancelReconnect();
                    List<BluetoothDeviceWrapper> hosts = bluetoothDeviceListing.getFidoHostsByPreference();
                    if (!hosts.isEmpty()) {
                        reconnectHostIndex = 0;
                        PasswdSafeUtil.dbginfo(TAG, "onAppStatusChanged - requestConnect to preferred FIDO host");
                        hidDeviceController.requestConnect(hosts.get(0).getDevice());
                    }
                } else if (hidDeviceController.isHidKeyboardMode()) {
                    BluetoothDeviceWrapper defaultDevice = bluetoothDeviceListing.getHidDefaultDevice();
                    if (defaultDevice != null && bluetoothDeviceListing.isKeyboardHost(defaultDevice)) {
                        PasswdSafeUtil.dbginfo(TAG, "onAppStatusChanged - requestConnect to default keyboard host");
                        hidDeviceController.requestConnect(defaultDevice.getDevice());
                    }
                }
            }

            if (!registered) {
                hidRegistered = false;
                // The stack dropped our registration; any pairing dies with it.
                clearPairing();
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
                    cancelReconnect();
                    if (hidDeviceController.isHidFidoMode() && bluetoothDeviceListing.isFidoHost(device)) {
                        bluetoothDeviceListing.setLastFidoHost(device);
                    }

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
                        typingExecutor.execute(() -> autotypeToConnectedHost(target));

                    } else if(pairingDevice != null && pairingDevice.equals(device)) {
                        // pairing seems to be successful
                        clearPairing();
                        requireFidoMode();
                    }
                } else if (state == BluetoothProfile.STATE_DISCONNECTED
                           && hidDeviceController.getConnectedDevice() == null) {
                    // Only the host a user-driven connect targets counts as
                    // that connect failing. When the phone was on the FIDO
                    // host (the PC), the switch to keyboard mode that
                    // precedes an auto-type or keyboard pairing tears that
                    // link down first, and its DISCONNECTED arrives while
                    // the output is still pending and nothing is connected
                    // yet. Treating that as the failure discarded the text
                    // and blamed the PC; the keyboard host then connected
                    // with nothing left to type, so only the second tap
                    // worked. The callback's device is freshly unparceled,
                    // hence equals, not ==.
                    boolean autotypeFailed = keyboardOutput != null
                                             && device != null
                                             && device.equals(keyboardOutputTarget);
                    // Likewise a DISCONNECTED for the pairing host before its
                    // connect was even issued is the old link going down.
                    boolean pairingFailed = pairingDevice != null
                                            && !pairingConnectPending
                                            && pairingDevice.equals(device);
                    if (autotypeFailed) {
                        // The connect issued by connectAndType failed (host
                        // off or out of range) or the link dropped before the
                        // send ran. Drop the pending output so it cannot be
                        // typed into whatever host connects next, and tell
                        // the user; the controller has already given up
                        // retrying.
                        PasswdSafeUtil.dbginfo(TAG, "onConnectionStateChanged: DISCONNECTED with pending autotype, discarding");
                        notifyTyping(keyboardOutputCallback, TypingResult.CONNECT_FAILED);
                        keyboardOutput.wipe();
                        takeKeyboardOutput();
                        String name = BluetoothUtils.getDeviceDisplayName(device);
                        Toast.makeText(getApplicationContext(),
                                       getString(R.string.bt_autotype_connect_failed, name),
                                       Toast.LENGTH_LONG).show();
                    }
                    if (pairingFailed) {
                        PasswdSafeUtil.dbginfo(TAG, "onConnectionStateChanged: DISCONNECTED while pairing, giving up");
                        clearPairing();
                    }
                    if ((autotypeFailed || pairingFailed)
                            && hidDeviceController.isHidKeyboardMode()) {
                        // The keyboard host never came up, so the switch back
                        // to FIDO mode that follows a successful auto-type or
                        // keyboard pairing did not happen either. Left like
                        // this the phone stays in keyboard mode, where the
                        // reconnect loop is off, and silently stops being a
                        // FIDO key until the user toggles FIDO. Re-register
                        // now; onAppStatusChanged dials the preferred host.
                        // No-op with FIDO disabled.
                        requireFidoMode();
                    }
                    // Link to the FIDO host dropped or a connect attempt
                    // failed. reconnectCandidates() is empty in keyboard mode
                    // and while a user-driven connect is in flight, so the
                    // teardown that precedes an auto-type is ignored; the
                    // switch back to FIDO mode re-registers and dials, and
                    // if that fails we land here again in FIDO mode.
                    scheduleReconnect();
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

            PasswdSafeApp app = (PasswdSafeApp) getApplication();
            PasswdSafe activity = app.getActiveActivity();
            TransactionManager tm = app.getTransactionManager();
            // Answer when a file is open (activity in front or not), or when
            // the encrypted key cache can stand in for the closed file.
            // Prompts that need a resumed activity are declined by the
            // authenticator itself when none is available.
            boolean fileReady = app.isFidoFileReady();
            boolean cacheReady = !fileReady && app.getFidoKeyCache().canServe();
            if (tm != null && (fileReady || cacheReady)) {
                openFileStarted = false;

                tm.handleReport(data, (rawReports) -> {
                    for (byte[] report : rawReports) {
                        inputHost.sendReport(device, reportId, report);
                    }
                });

                if (cacheReady && !Preferences.getFidoAutoApproveLogin(prefs)) {
                    // Serving from the cache with confirmations still on: the
                    // request will be declined for lack of a prompt host, so
                    // tell the user to open the app.
                    showRequestNotification();
                }
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