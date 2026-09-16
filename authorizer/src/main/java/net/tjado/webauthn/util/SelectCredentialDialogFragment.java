package net.tjado.webauthn.util;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Exchanger;

import net.tjado.passwdsafe.R;
import net.tjado.webauthn.models.PublicKeyCredentialSource;

public class SelectCredentialDialogFragment extends DialogFragment implements CredentialSelector {
    private static final String TAG = "WebauthnDialogFragment";
    private WeakReference<FragmentActivity> fragmentActivity;
    private List<PublicKeyCredentialSource> credentialList;
    private Exchanger<PublicKeyCredentialSource> exchanger;

    public void populateFragmentActivity(FragmentActivity fragmentActivity) {
        this.fragmentActivity = new WeakReference<>(fragmentActivity);
    }

    public PublicKeyCredentialSource selectFrom(List<PublicKeyCredentialSource> credentialList) {
        // check to make sure fragmentActivity is populated
        if (fragmentActivity == null) {
            Log.w(TAG, "Must populate fragment activity before calling promptUser");
            return null;
        }
        // store some instance vars for the dialog prompt to use
        this.credentialList = credentialList;
        this.exchanger = new Exchanger<>();

        // show dialog prompt to user
        FragmentActivity fragmentActivityStrongRef = fragmentActivity.get();
        if (fragmentActivityStrongRef == null) {
            Log.w(TAG,"FragmentActivity reference was garbage collected. Returning first matching credential.");
            return credentialList.get(0);
        }
        show(fragmentActivityStrongRef.getSupportFragmentManager(), "credential");

        // wait to retrieve credential
        PublicKeyCredentialSource selectedCredential;
        try {
            selectedCredential = exchanger.exchange(null);
        } catch (InterruptedException exception) {
            Log.w(TAG, "exchange interrupted: " + exception.toString());
            return null;
        }
        return selectedCredential;
    }

    @NotNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        // make sure credentialList and exchanger are populated
        if (credentialList == null || exchanger == null) {
            Log.w(TAG, "credentialList and exchanger must be populated before calling show()");
        }
        // turn credentialList into CharSequence[] of user names
        ArrayList<String> usernames = new ArrayList<>();
        for (PublicKeyCredentialSource credential : credentialList) {
            usernames.add(credential.userDisplayName);
        }
        final String[] usernames_final = usernames.toArray(new String[0]);

        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(requireActivity());
        builder.setTitle(R.string.dialog_select_credential)
                .setItems(usernames_final, (dialog, which) -> {
                    // Select by the tapped position, not by matching the display
                    // name: two credentials can share a userDisplayName, and the
                    // name match would then always pick the first one (and NPE on
                    // a null name).
                    if (exchanger != null && which >= 0 && which < credentialList.size()) {
                        try {
                            exchanger.exchange(credentialList.get(which));
                        } catch (InterruptedException exception) {
                            Log.w(TAG, "exchange interrupted: " + exception.toString());
                        }
                    }
                });
        return builder.create();
    }

    @Override
    public void onCancel(@NotNull DialogInterface dialog) {
        super.onCancel(dialog);
        // The CTAP worker blocks in selectFrom() on exchange(); if the user
        // dismisses the dialog without choosing, unblock it with null so the
        // request fails cleanly instead of hanging forever. Do the exchange off
        // the main thread so a stopped worker cannot ANR the UI.
        final Exchanger<PublicKeyCredentialSource> ex = exchanger;
        if (ex != null) {
            new Thread(() -> {
                try {
                    ex.exchange(null);
                } catch (InterruptedException ignored) {
                }
            }, "CredSelectCancel").start();
        }
    }

}
