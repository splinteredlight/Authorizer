package net.tjado.passwdsafe;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.Manifest;
import androidx.core.content.ContextCompat;
import android.annotation.SuppressLint;
import androidx.appcompat.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.tjado.bluetooth.BluetoothDeviceListing;
import net.tjado.bluetooth.BluetoothUtils;
import net.tjado.bluetooth.HidDeviceProfile;
import net.tjado.passwdsafe.lib.ActContext;
import net.tjado.passwdsafe.lib.ApiCompat;
import net.tjado.passwdsafe.lib.DynamicPermissionMgr;
import net.tjado.passwdsafe.lib.PasswdSafeUtil;
import net.tjado.bluetooth.BluetoothDeviceWrapper;
import net.tjado.passwdsafe.util.AboutUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


/**
 * Fragment for Bluetooth Device Listing & Keyboard/FIDO pairing
 */

@SuppressLint("MissingPermission")
public class BluetoothFragment extends Fragment
{
    /**
     * Listener interface for owning activity
     */
    public interface Listener
            extends AbstractPasswdSafeFileDataFragment.Listener
    {
        /** Update the view */
        void updateViewPrefBluetooth();

        void checkBluetoothState();
    }

    private Listener itsListener;

    private SharedPreferences prefs;

    private ViewFlipper flipperSub;
    private ProgressBar btScanProgress;
    private Button btScanToggle;
    private TextView tvNoPairedDevices;
    private TextView tvScanDescription;
    private Button btRequestProgress;
    private Button btAppSettings;

    private BluetoothAdapter bluetoothAdapter;
    BluetoothDeviceListing bluetoothDeviceListing = null;

    private RecyclerView rvPairedDevices = null;
    private RvPairedDevicesAdapter rvPairedDevicesAdapter;
    private List<BluetoothDeviceWrapper> pairedDevices;

    RecyclerView rvDiscoveredDevices = null;
    private RvDiscoveredDevicesAdapter rvDiscoveredDevicesAdapter;
    private final ArrayList<BluetoothDeviceWrapper> discoveredDevices = new ArrayList<>();

    private final Handler checkBtProfileStateHandler = new Handler();

    private final int CHECK_APP_REGISTRATION_TIMEOUT_MS = 200;

    private static final String TAG = "BluetoothFragment";

    DynamicPermissionMgr itsPermissionMgr = null;

    /**
     * Create a new instance
     */
    public static BluetoothFragment newInstance()
    {
        return new BluetoothFragment();
    }

    @Override
    public void onAttach(@NonNull Context ctx)
    {
        super.onAttach(ctx);
        itsListener = (Listener)ctx;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState)
    {
        setHasOptionsMenu(true);
        View rootView = inflater.inflate(R.layout.fragment_bluetooth, container, false);

        ViewFlipper flipperOverall = rootView.findViewById(R.id.flipperOverall);
        flipperSub = rootView.findViewById(R.id.flipperSub);
        tvScanDescription = rootView.findViewById(R.id.scan_description);
        tvNoPairedDevices = rootView.findViewById(R.id.tv_no_paired_devices);
        btRequestProgress = rootView.findViewById(R.id.request_permissions);
        btAppSettings = rootView.findViewById(R.id.app_settings);

        btScanProgress = rootView.findViewById(R.id.scan_progress);
        btScanToggle = rootView.findViewById(R.id.btn_scan);

        rvPairedDevices = rootView.findViewById(R.id.rv_bluetooth_devices_paired);
        rvDiscoveredDevices = rootView.findViewById(R.id.rv_bluetooth_devices_scan);

        TextView tvBluetoothFeature = rootView.findViewById(R.id.tv_pref_bt);
        TextView tvBluetoothFido = rootView.findViewById(R.id.tv_pref_bt_fido);
        CompoundButton cbBluetoothFeature = rootView.findViewById(R.id.cb_pref_bt);
        CompoundButton cbBluetoothFido = rootView.findViewById(R.id.cb_pref_bt_fido);

        TextView tvFidoAutoLogin = rootView.findViewById(R.id.tv_pref_bt_fido_auto_login);
        CompoundButton cbFidoAutoLogin = rootView.findViewById(R.id.cb_pref_bt_fido_auto_login);
        TextView tvFidoAutoRegister = rootView.findViewById(R.id.tv_pref_bt_fido_auto_register);
        CompoundButton cbFidoAutoRegister = rootView.findViewById(R.id.cb_pref_bt_fido_auto_register);
        TextView tvFidoBackground = rootView.findViewById(R.id.tv_pref_bt_fido_background);
        CompoundButton cbFidoBackground = rootView.findViewById(R.id.cb_pref_bt_fido_background);

        prefs = Preferences.getSharedPrefs(getContext());

        // The auto-approve rows only make sense while FIDO mode is on. They
        // keep their stored values while disabled so turning FIDO off and on
        // again does not silently re-enable them.
        Runnable syncFidoAutoApprove = () -> {
            boolean fidoOn = Preferences.getBluetoothEnabled(prefs)
                    && Preferences.getBluetoothFidoEnabled(prefs);
            cbFidoAutoLogin.setEnabled(fidoOn);
            cbFidoAutoRegister.setEnabled(fidoOn);
            tvFidoAutoLogin.setEnabled(fidoOn);
            tvFidoAutoRegister.setEnabled(fidoOn);
            cbFidoAutoLogin.setChecked(fidoOn && Preferences.getFidoAutoApproveLogin(prefs));
            cbFidoAutoRegister.setChecked(fidoOn && Preferences.getFidoAutoApproveRegister(prefs));
            cbFidoBackground.setEnabled(fidoOn);
            tvFidoBackground.setEnabled(fidoOn);
            cbFidoBackground.setChecked(fidoOn && Preferences.getFidoBackgroundAnswer(prefs));
        };

        cbBluetoothFeature.setChecked(Preferences.getBluetoothEnabled(prefs));
        if(Preferences.getBluetoothEnabled(prefs)) {
            cbBluetoothFido.setEnabled(true);
            cbBluetoothFido.setChecked(Preferences.getBluetoothFidoEnabled(prefs));
        } else {
            cbBluetoothFido.setChecked(false);
            cbBluetoothFido.setEnabled(false);
        }
        syncFidoAutoApprove.run();

        tvBluetoothFeature.setOnClickListener(item -> cbBluetoothFeature.performClick());
        tvBluetoothFido.setOnClickListener(item -> cbBluetoothFido.performClick());
        tvFidoAutoLogin.setOnClickListener(item -> cbFidoAutoLogin.performClick());
        tvFidoAutoRegister.setOnClickListener(item -> cbFidoAutoRegister.performClick());

        cbFidoAutoLogin.setOnClickListener(item ->
            Preferences.setFidoAutoApproveLoginPref(cbFidoAutoLogin.isChecked(), prefs));
        cbFidoAutoRegister.setOnClickListener(item ->
            Preferences.setFidoAutoApproveRegisterPref(cbFidoAutoRegister.isChecked(), prefs));
        tvFidoBackground.setOnClickListener(item -> cbFidoBackground.performClick());
        cbFidoBackground.setOnClickListener(item -> {
            boolean on = cbFidoBackground.isChecked();
            Preferences.setFidoBackgroundAnswerPref(on, prefs);
            PasswdSafeApp app = (PasswdSafeApp) requireActivity().getApplication();
            if (on) {
                // Fill the cache from the file that is open right now, if any.
                app.refreshFidoKeyCache((PasswdSafe) requireActivity());
            } else {
                app.getFidoKeyCache().clear();
            }
        });

        cbBluetoothFeature.setOnClickListener(item -> {
            Preferences.setBluetoothEnabledPref(cbBluetoothFeature.isChecked(), prefs);

            if(Preferences.getBluetoothEnabled(prefs)) {
                cbBluetoothFido.setEnabled(true);
                cbBluetoothFido.setChecked(Preferences.getBluetoothFidoEnabled(prefs));

                itsListener.checkBluetoothState();
            } else {
                cbBluetoothFido.setChecked(false);
                cbBluetoothFido.setEnabled(false);

                BluetoothForegroundService btService = ((PasswdSafe) requireActivity()).btService;
                if(btService != null) {
                    btService.stopForegroundService();
                }
            }
            syncFidoAutoApprove.run();

            checkBluetoothState(null);
        });

        cbBluetoothFido.setOnClickListener(item -> {
            Preferences.setBluetoothFidoEnabledPref(cbBluetoothFido.isChecked(), prefs);
            syncFidoAutoApprove.run();

            if(cbBluetoothFido.isChecked()) {
                rvDiscoveredDevicesAdapter.notifyDataSetChanged();
            }

            BluetoothForegroundService btService = ((PasswdSafe) requireActivity()).btService;
            if (btService != null) {
                if(cbBluetoothFido.isChecked()) {
                    btService.requireFidoMode();
                } else {
                    btService.requireKeyboardMode();
                }
            }
            // Make sure the service is running and reflects the new mode.
            itsListener.checkBluetoothState();
        });


        btScanToggle.setOnClickListener(item -> bluetoothScanToggle());

        if (!ApiCompat.supportsBluetoothHid()) {
            PasswdSafeUtil.dbginfo(TAG, "Android API version too low - aborting");
            flipperOverall.setDisplayedChild(1);
            return rootView;
        }

        BluetoothManager bluetoothManager = (BluetoothManager) requireContext().getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();


        if (bluetoothAdapter == null) {
            PasswdSafeUtil.dbginfo(TAG, "BluetoothAdapter is null - aborting");
            flipperOverall.setDisplayedChild(2);
            return rootView;
        }

        itsPermissionMgr = new DynamicPermissionMgr(
                requireActivity(), rootView, 3, 4,
                BuildConfig.APPLICATION_ID, R.id.request_permissions, R.id.app_settings, requestPermissionLauncher);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            itsPermissionMgr.addPerm(Manifest.permission.BLUETOOTH_SCAN, true);
            itsPermissionMgr.addPerm(Manifest.permission.BLUETOOTH_CONNECT, true);
        } else {
            itsPermissionMgr.addPerm(Manifest.permission.BLUETOOTH, true);
            itsPermissionMgr.addPerm(Manifest.permission.BLUETOOTH_ADMIN, true);
            itsPermissionMgr.addPerm(Manifest.permission.ACCESS_COARSE_LOCATION, true);
            itsPermissionMgr.addPerm(Manifest.permission.ACCESS_FINE_LOCATION, true);
        }

        if (!itsPermissionMgr.checkPerms()) {
            PasswdSafeUtil.dbginfo(TAG, "Missing specific permissions - scan disabled");
            btScanToggle.setEnabled(false);

            tvScanDescription.setText(R.string.bt_no_scan_permission_desc);
            btRequestProgress.setVisibility(View.VISIBLE);
            btAppSettings.setVisibility(View.VISIBLE);
        } else {
            initRecyclerViews();
        }

        if (AboutUtils.checkShowBluetoothHelp(requireContext())) {
            showBluetoothHelp();
        }

        return rootView;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu,
                                    @NonNull MenuInflater inflater)
    {
        inflater.inflate(R.menu.fragment_bluetooth, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_bluetooth_help) {
            showBluetoothHelp();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showBluetoothHelp() {
        PasswdSafeUtil.showInfoMsg(getString(R.string.bt_help), requireContext());
    }

    private void initRecyclerViews() {
        if (bluetoothDeviceListing != null) {
            return;
        }

        bluetoothDeviceListing = new BluetoothDeviceListing(requireContext());

        // paired devices
        pairedDevices = bluetoothDeviceListing.getAvailableDevices();
        rvPairedDevicesAdapter = new RvPairedDevicesAdapter(pairedDevices);
        rvPairedDevices.setAdapter(rvPairedDevicesAdapter);
        LinearLayoutManager llmPairedDevices = new LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false);
        rvPairedDevices.setLayoutManager(llmPairedDevices);

        tvNoPairedDevices.setVisibility(pairedDevices.isEmpty() ? View.VISIBLE : View.GONE);

        // discovered devices during Bluetooth scan
        rvDiscoveredDevicesAdapter = new RvDiscoveredDevicesAdapter(discoveredDevices);
        rvDiscoveredDevices.setAdapter(rvDiscoveredDevicesAdapter);
        LinearLayoutManager llmDiscoveredDevices = new LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false);
        rvDiscoveredDevices.setLayoutManager(llmDiscoveredDevices);
    }

    @Override
    public void onResume()
    {
        super.onResume();
        itsListener.updateViewPrefBluetooth();

        if(!ApiCompat.supportsBluetoothHid() || !itsPermissionMgr.hasRequiredPerms()) {
            return;
        }

        final IntentFilter btStatusIntentFilter = new IntentFilter();
        btStatusIntentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        btStatusIntentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        ContextCompat.registerReceiver(requireContext(), btStateReceiver, btStatusIntentFilter, ContextCompat.RECEIVER_EXPORTED);

        registerScanReceiver();

        checkBluetoothState(null);
    }

    @Override
    public void onPause()
    {
        super.onPause();

        if (itsPermissionMgr != null && itsPermissionMgr.hasRequiredPerms()) {
            stopBluetoothDiscovery();

            try {
                unregisterScanReceiver();
            } catch (Exception e) {
                PasswdSafeUtil.dbginfo(TAG, e, "stopBluetoothDiscovery");
            }
        }
    }


    @Override
    public void onDetach()
    {
        super.onDetach();
        itsListener = null;
    }

    private void bluetoothScanToggle() {
        if (bluetoothAdapter.isDiscovering()) {
            stopBluetoothDiscovery();
        } else {
            clearAvailableDevices();
            startBluetoothDiscovery();
        }
    }

    private void startBluetoothDiscovery() {
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        bluetoothAdapter.startDiscovery();
    }

    private void stopBluetoothDiscovery() {
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }
        discoveryStopped();
    }

    private void registerScanReceiver() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        intentFilter.addAction(BluetoothDevice.ACTION_FOUND);
        intentFilter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        // RECEIVER_EXPORTED on purpose: Bluetooth broadcasts are sent by the Bluetooth
        // process, not the system uid, so Android drops them for non-exported receivers
        // (with RECEIVER_NOT_EXPORTED no scan result ever arrived here). They are
        // protected broadcasts; no third-party app can send them.
        ContextCompat.registerReceiver(requireContext(), btScanReceiver, intentFilter, ContextCompat.RECEIVER_EXPORTED);
    }

    private void unregisterScanReceiver() {
        requireContext().unregisterReceiver(btScanReceiver);
    }

    private void discoveryStarted() {
        btScanToggle.setText(R.string.bt_stop_scan);
        btScanProgress.setVisibility(View.VISIBLE);
    }

    private void discoveryStopped() {
        btScanToggle.setText(R.string.bt_start_scan);
        btScanProgress.setVisibility(View.GONE);
    }

    private void checkBluetoothState(Integer state) {

        if (state == null) {
            state = bluetoothAdapter.getState();
        }

        if (state == BluetoothAdapter.STATE_OFF) {
            PasswdSafeUtil.dbginfo(TAG, "BluetoothAdapter.STATE_OFF");
            if(flipperSub != null) {
                flipperSub.setDisplayedChild(2);
            }

        } else if (state == BluetoothAdapter.STATE_TURNING_OFF) {
            PasswdSafeUtil.dbginfo(TAG, "BluetoothAdapter.STATE_TURNING_OFF");
            if(flipperSub != null) {
                flipperSub.setDisplayedChild(2);
            }

        } else if (state == BluetoothAdapter.STATE_ON) {
            PasswdSafeUtil.dbginfo(TAG, "BluetoothAdapter.STATE_ON");
            if(flipperSub != null) {
                if(Preferences.getBluetoothEnabled(prefs)) {
                    flipperSub.setDisplayedChild(0);
                } else {
                    flipperSub.setDisplayedChild(1);
                }

                if (pairedDevices != null && rvPairedDevicesAdapter != null) {
                    pairedDevices.clear();
                    pairedDevices.addAll(bluetoothDeviceListing.getAvailableDevices());

                    tvNoPairedDevices.setVisibility(pairedDevices.isEmpty() ? View.VISIBLE : View.GONE);

                    rvPairedDevicesAdapter.notifyDataSetChanged();
                }
            }

        } else if (state == BluetoothAdapter.STATE_TURNING_ON) {
            PasswdSafeUtil.dbginfo(TAG, "BluetoothAdapter.STATE_TURNING_ON");
        }
    }

    private void checkDeviceState(Integer state) {
        PasswdSafeUtil.dbginfo(TAG, "checkDeviceState: " + state);
        if (state == BluetoothAdapter.STATE_CONNECTED) {
            PasswdSafeUtil.dbginfo(TAG, "BluetoothAdapter.STATE_CONNECTED");
            rvPairedDevicesAdapter.notifyDataSetChanged();
        }
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    protected void addAvailableDevice(BluetoothDevice device) {
        BluetoothDeviceWrapper wrappedDevice = new BluetoothDeviceWrapper(device);
        if(!discoveredDevices.contains(wrappedDevice) && device.getName() != null) {
            discoveredDevices.add(wrappedDevice);
            rvDiscoveredDevicesAdapter.notifyDataSetChanged();
        }
    }

    @SuppressLint("NewApi")
    protected void updateAvailableDevice(BluetoothDevice device) {
        Optional<BluetoothDeviceWrapper> optDevice = discoveredDevices.stream().filter(d -> d.getDevice().getAddress().equals(device.getAddress())).findFirst();
        if(optDevice.isPresent() && device.getName() != null) {
            discoveredDevices.remove(optDevice.get());
            discoveredDevices.add(new BluetoothDeviceWrapper(device));
            rvDiscoveredDevicesAdapter.notifyDataSetChanged();
        }
    }

    protected void clearAvailableDevices() {
        discoveredDevices.clear();

        if (rvDiscoveredDevicesAdapter != null) {
            rvDiscoveredDevicesAdapter.notifyDataSetChanged();
        }
    }

    public class RvPairedDevicesAdapter extends RecyclerView.Adapter<ViewHolderPairedDevice> {
        private final List<BluetoothDeviceWrapper> devices;

        public RvPairedDevicesAdapter(List<BluetoothDeviceWrapper> devices) {
            this.devices = devices;
        }

        @NonNull
        @Override
        public ViewHolderPairedDevice onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.cardview_bluetooth_devices, parent, false);
            return new ViewHolderPairedDevice(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolderPairedDevice holder, int position) {
            BluetoothDeviceWrapper device = devices.get(position);

            String displayType;
            String type = device.getType();
            holder.btnReconnect.setVisibility(View.GONE);
            String okColor = String.format("#%06X", 0xFFFFFF & ContextCompat.getColor(
                    requireContext(), R.color.status_ok));
            String errColor = String.format("#%06X", 0xFFFFFF & MaterialColors.getColor(
                    holder.itemView, R.attr.colorError));
            if (type.equals(BluetoothDeviceListing.HID_KEYBOARD_HOST)) {
                displayType = "<font color=" + okColor + "><b>Keyboard</b></font>";
            } else if (type.equals(BluetoothDeviceListing.HID_FIDO_HOST)) {
                displayType = "<font color=" + okColor + "><b>FIDO (U2F/WebAuthn)</b></font>";
                holder.btnReconnect.setVisibility(View.VISIBLE);
            } else {
                displayType = "<font color=" + errColor + "><b>Unknown</b></font>";
            }

            holder.name.setText(device.getName());
            holder.address.setText(device.getAddress());
            if (device.isDefault()) {
                displayType += " (" + getString(R.string.bt_default) + ")";
            }
            holder.state.setText(Html.fromHtml(String.format(getString(R.string.bt_paired_as), displayType)));

            holder.btnDeviceMenu.setOnClickListener(view -> showDeviceMenu(view, device));

            holder.btnReconnect.setEnabled(true);
            holder.btnReconnect.setText(R.string.bt_reconnect);

            BluetoothForegroundService btService = ((PasswdSafe) requireActivity()).btService;
            if(btService != null && btService.getConnectedDevice() != null)  {
                BluetoothDeviceWrapper connectedDevice = new BluetoothDeviceWrapper(btService.getConnectedDevice());
                if(device.equals(connectedDevice)) {
                    holder.btnReconnect.setEnabled(false);
                    holder.btnReconnect.setText(R.string.bt_connected);
                }
            }

            // Reconnect in case of several paired FIDO devices
            holder.btnReconnect.setOnClickListener(item -> {
                BluetoothForegroundService btServiceInner = ((PasswdSafe) requireActivity()).btService;
                if(btServiceInner != null) {
                    if(btServiceInner.getConnectedDevice() != null)  {
                        BluetoothDeviceWrapper connectedDevice = new BluetoothDeviceWrapper(btServiceInner.getConnectedDevice());
                        if(device.equals(connectedDevice)) {
                            holder.btnReconnect.setEnabled(false);
                            holder.btnReconnect.setText(R.string.bt_connected);
        
                            Toast.makeText(getActivity(), "Already connected!", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }

                    stopBluetoothDiscovery();
                    SystemClock.sleep(50);

                    // As the standard connect does not work to switch between FIDO devices,
                    // the Bluetooth HID profile gets reinitialized. As device is already paired
                    // the pairing dialog is not shown and the device gets connected.
                    btServiceInner.pairAsFido(device.getDevice());

                    checkBtProfileStateHandler.postDelayed(() -> {
                        if(btServiceInner.isAppRegistered()) {
                            PasswdSafeUtil.dbginfo(TAG, "btService.isAppRegistered is TRUE");
                        } else {
                            PasswdSafeUtil.dbginfo(TAG, "btService.isAppRegistered is FALSE");
                            PasswdSafeUtil.showErrorMsg(getString(R.string.bt_unclean_state_error), new ActContext(requireContext()));
                        }
                    }, CHECK_APP_REGISTRATION_TIMEOUT_MS);
                } else {
                    PasswdSafeUtil.showErrorMsg(getString(R.string.bt_pairing_no_service), new ActContext(requireActivity()));
                }
            });
        }

        @Override
        public int getItemCount() {
            return devices.size();
        }

        private void showDeviceMenu(View view, BluetoothDeviceWrapper device) {
            String type = device.getType();

            PopupMenu popup = new PopupMenu(requireContext(), view);
            popup.getMenuInflater().inflate(R.menu.cardview_bluetooth_device, popup.getMenu());

            MenuItem defaultMenu = popup.getMenu().findItem(R.id.menu_default);
            // Keyboard hosts can be default too: auto-type then sends to
            // this device without asking which one to use.
            if(bluetoothDeviceListing.isHidDefaultDevice(device)) {
                defaultMenu.setEnabled(false);
                defaultMenu.setTitle(R.string.bt_is_default);
            } else {
                defaultMenu.setEnabled(true);
            }

            popup.setOnMenuItemClickListener(item -> {
                popupClick(item, device);
                return true;
            });

            popup.show();
        }


        private void popupClick(MenuItem item, BluetoothDeviceWrapper device) {

            PasswdSafeUtil.dbginfo(TAG, "Clicked: " + device.getName());

            int itemId = item.getItemId();
            if (itemId == R.id.menu_default) {
                PasswdSafeUtil.dbginfo(TAG, "Paired device menu: clicked set default");
                if(!rvPairedDevices.isComputingLayout()) {
                    bluetoothDeviceListing.cacheHidDefaultDevice(device.getDevice());
                    checkBluetoothState(null);
                }

            } else if (itemId == R.id.menu_unpair) {
                PasswdSafeUtil.dbginfo(TAG, "Paired device menu: clicked unpair");
                AlertDialog.Builder alert = new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.confirm))
                    .setMessage(getString(R.string.bt_unpair_info))
                    .setPositiveButton(R.string.confirm,
                        (dialog, whichButton) -> {
                            try {
                                BluetoothUtils.removeBond(device.getDevice());
                            } catch (NoSuchMethodException e) {
                                PasswdSafeUtil.showErrorMsg(getString(R.string.bt_no_unpair), new ActContext(requireActivity()));
                            }
                        })
                    .setNegativeButton(R.string.cancel, null);
                alert.show();
            }
        }
    }

    public static class ViewHolderPairedDevice extends RecyclerView.ViewHolder {
        private final TextView name;
        private final TextView address;
        private final TextView state;
        private final Button btnReconnect;
        private final ImageButton btnDeviceMenu;

        public ViewHolderPairedDevice(@NonNull View itemView) {
            super(itemView);

            name = itemView.findViewById(R.id.name);
            address = itemView.findViewById(R.id.address);
            state = itemView.findViewById(R.id.state);
            btnReconnect = itemView.findViewById(R.id.btn_reconnect);
            btnDeviceMenu = itemView.findViewById(R.id.device_menu);
        }
    }


    public class RvDiscoveredDevicesAdapter extends RecyclerView.Adapter<ViewHolderDiscoveredDevice> {

        final private ArrayList<BluetoothDeviceWrapper> devices;

        public RvDiscoveredDevicesAdapter(ArrayList<BluetoothDeviceWrapper> devices) {
            this.devices = devices;
        }

        @NonNull
        @Override
        public ViewHolderDiscoveredDevice onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.cardview_bluetooth_devices_scan, parent, false);
            return new ViewHolderDiscoveredDevice(view);
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onBindViewHolder(ViewHolderDiscoveredDevice holder, int position)
        {
            BluetoothDeviceWrapper device = devices.get(position);
            int state = device.getDevice().getBondState();

            holder.name.setText(device.getName());
            holder.address.setText(device.getAddress());
            holder.state.setText(BluetoothUtils.parseBondState(state));

            holder.btnKeyboard.setOnClickListener(item -> {
                BluetoothForegroundService btService = ((PasswdSafe) requireActivity()).btService;
                if(btService != null) {
                    stopBluetoothDiscovery();
                    SystemClock.sleep(50);
                    btService.pairAsKeyboard(device.getDevice());

                    checkBtProfileStateHandler.postDelayed(() -> {
                        if(btService.isAppRegistered()) {
                            PasswdSafeUtil.dbginfo(TAG, "btService.isAppRegistered is TRUE");
                        } else {
                            PasswdSafeUtil.dbginfo(TAG, "btService.isAppRegistered is FALSE");
                            PasswdSafeUtil.showErrorMsg(getString(R.string.bt_unclean_state_error), new ActContext(requireContext()));
                        }
                    }, CHECK_APP_REGISTRATION_TIMEOUT_MS);
                } else {
                    PasswdSafeUtil.showErrorMsg(getString(R.string.bt_pairing_no_service), new ActContext(requireActivity()));
                }
            });

            holder.btnFido.setEnabled(Preferences.getBluetoothFidoEnabled(prefs));

            holder.btnFido.setOnClickListener(item -> {
                BluetoothForegroundService btService = ((PasswdSafe) requireActivity()).btService;
                if(btService != null) {
                    stopBluetoothDiscovery();
                    SystemClock.sleep(50);
                    btService.pairAsFido(device.getDevice());

                    checkBtProfileStateHandler.postDelayed(() -> {
                        if(btService.isAppRegistered()) {
                            PasswdSafeUtil.dbginfo(TAG, "btService.isAppRegistered is TRUE");
                        } else {
                            PasswdSafeUtil.dbginfo(TAG, "btService.isAppRegistered is FALSE");
                            PasswdSafeUtil.showErrorMsg(getString(R.string.bt_unclean_state_error), new ActContext(requireContext()));
                        }
                    }, CHECK_APP_REGISTRATION_TIMEOUT_MS);
                } else {
                    PasswdSafeUtil.showErrorMsg(getString(R.string.bt_pairing_no_service), new ActContext(requireActivity()));
                }
            });
        }

        @Override
        public int getItemCount(){
            return devices.size();
        }

    }

    public static class ViewHolderDiscoveredDevice extends RecyclerView.ViewHolder {
        private final TextView name;
        private final TextView address;
        private final TextView state;
        private final Button btnKeyboard;
        private final Button btnFido;

        public ViewHolderDiscoveredDevice(@NonNull View itemView) {
            super(itemView);

            name = itemView.findViewById(R.id.name);
            address = itemView.findViewById(R.id.address);
            state = itemView.findViewById(R.id.state);

            btnKeyboard = itemView.findViewById(R.id.btn_keyboard);
            btnFido = itemView.findViewById(R.id.btn_fido);
        }
    }


    protected final BroadcastReceiver btStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                checkBluetoothState(state);
                clearAvailableDevices();
            } else if(action.equals(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, BluetoothAdapter.ERROR);
                checkDeviceState(state);
            }
        }
    };


    /** Handles bluetooth scan responses and other indicators. */
    protected final BroadcastReceiver btScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getContext() == null) {
                PasswdSafeUtil.dbginfo("BluetoothScanReceiver", "BluetoothScanReceiver context disappeared");
                return;
            }

            final String action = intent.getAction();
            final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            switch (action == null ? "" : action) {
                case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
                    PasswdSafeUtil.dbginfo("BluetoothScanReceiver", "BluetoothAdapter.ACTION_DISCOVERY_STARTED");
                    discoveryStarted();
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    PasswdSafeUtil.dbginfo("BluetoothScanReceiver", "BluetoothAdapter.ACTION_DISCOVERY_FINISHED");
                    discoveryStopped();
                    break;
                case BluetoothDevice.ACTION_FOUND:
                    PasswdSafeUtil.dbginfo("BluetoothScanReceiver", "BluetoothDevice.ACTION_FOUND: " + device.getName());
                    if (HidDeviceProfile.isProfileSupported(device)) {
                        addAvailableDevice(device);
                    }
                    break;
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                    PasswdSafeUtil.dbginfo("BluetoothScanReceiver", "BluetoothDevice.ACTION_BOND_STATE_CHANGED");
                    updateAvailableDevice(device);

                    pairedDevices.clear();
                    pairedDevices.addAll(bluetoothDeviceListing.getAvailableDevices());

                    tvNoPairedDevices.setVisibility(pairedDevices.isEmpty() ? View.VISIBLE : View.GONE);

                    rvPairedDevicesAdapter.notifyDataSetChanged();

                    break;
                case BluetoothDevice.ACTION_NAME_CHANGED:
                    PasswdSafeUtil.dbginfo("BluetoothScanReceiver", "BluetoothDevice.ACTION_NAME_CHANGED");
                    break;
                default: // fall out
            }
        }
    };


    final private ActivityResultLauncher<String[]> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (!result.containsValue(false)) {
                    PasswdSafeUtil.dbginfo(TAG, "RequestMultiplePermissions: granted");
                    btScanToggle.setEnabled(true);

                    tvScanDescription.setText(getString(R.string.bt_scan_description));
                    btRequestProgress.setVisibility(View.GONE);
                    btAppSettings.setVisibility(View.GONE);

                    IntentFilter btStatusIntentFilter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
                    // RECEIVER_EXPORTED on purpose: Bluetooth broadcasts do not reach
                    // non-exported receivers (they come from the Bluetooth uid, not system).
                    ContextCompat.registerReceiver(requireContext(), btStateReceiver, btStatusIntentFilter, ContextCompat.RECEIVER_EXPORTED);
                    registerScanReceiver();
                    checkBluetoothState(null);

                    initRecyclerViews();
                } else {
                    PasswdSafeUtil.dbginfo(TAG, "RequestMultiplePermissions: missing permissions - scan disabled");
                    btScanToggle.setEnabled(false);

                    tvScanDescription.setText(R.string.bt_no_scan_permission_desc);
                    btRequestProgress.setVisibility(View.VISIBLE);
                    btAppSettings.setVisibility(View.VISIBLE);
                }
            }
    );

}
