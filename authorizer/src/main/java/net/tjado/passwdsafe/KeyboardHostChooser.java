/*
 * Authorizer
 *
 * Copyright 2026 Authorizer contributors
 * Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */
package net.tjado.passwdsafe;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.tjado.bluetooth.BluetoothDeviceListing;
import net.tjado.bluetooth.BluetoothDeviceWrapper;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Picks the Bluetooth keyboard host to type to: the only paired one, the
 * one marked as default, or the user's choice from a list.
 */
final class KeyboardHostChooser
{
    interface Listener
    {
        /** @param device the host, or null if there is none or the user
         *                dismissed the list */
        void onChosen(@Nullable BluetoothDeviceWrapper device);
    }

    private KeyboardHostChooser()
    {
    }

    static void choose(@NonNull Context ctx, @NonNull Listener listener)
    {
        BluetoothDeviceListing listing = new BluetoothDeviceListing(ctx);
        List<BluetoothDeviceWrapper> hosts =
                listing.getAvailableKeyboardHostDevices();
        if (hosts.isEmpty()) {
            Toast.makeText(ctx, R.string.bt_autotype_no_devices,
                           Toast.LENGTH_LONG).show();
            listener.onChosen(null);
            return;
        }
        if (hosts.size() == 1) {
            listener.onChosen(hosts.get(0));
            return;
        }
        for (BluetoothDeviceWrapper device : hosts) {
            if (listing.isHidDefaultDevice(device)) {
                listener.onChosen(device);
                return;
            }
        }

        SortedMap<String, BluetoothDeviceWrapper> byName = new TreeMap<>();
        hosts.forEach(device -> byName.put(device.getName(), device));
        CharSequence[] names = byName.keySet().toArray(new CharSequence[0]);
        // Exactly one callback however the dialog ends
        boolean[] chosen = {false};
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.autotype_bluetooth_devices)
                .setItems(names, (dialog, which) -> {
                    chosen[0] = true;
                    listener.onChosen(byName.get(names[which].toString()));
                })
                .setOnDismissListener(dialog -> {
                    if (!chosen[0]) {
                        listener.onChosen(null);
                    }
                })
                .show();
    }
}
