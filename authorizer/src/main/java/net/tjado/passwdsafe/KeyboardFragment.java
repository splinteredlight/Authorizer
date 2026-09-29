/*
 * Authorizer
 *
 * Copyright 2026 Authorizer contributors
 * Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */
package net.tjado.passwdsafe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.tjado.authorizer.KeyScript;
import net.tjado.authorizer.Keystrokes;
import net.tjado.authorizer.OutputInterface;
import net.tjado.authorizer.UsbAutoType;
import net.tjado.authorizer.hid.HidGadgetSetup;
import net.tjado.authorizer.hid.HidStatus;
import net.tjado.bluetooth.BluetoothUtils;
import net.tjado.passwdsafe.lib.ActContext;
import net.tjado.passwdsafe.lib.ApiCompat;
import net.tjado.passwdsafe.lib.PasswdSafeUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keyboard screen: type free text, single keys with modifiers, and scripts
 * from a folder the user picked, over Bluetooth or USB.
 *
 * <p>Everything typed goes through {@link KeyScript}, so free text and
 * scripts share one set of rules: {Group/Title.field} references into the
 * open file, and nothing is typed when anything fails to parse.
 */
public class KeyboardFragment extends Fragment
{
    /** Listener interface for the owning activity */
    public interface Listener
            extends AbstractPasswdSafeFileDataFragment.Listener
    {
        /** Update the view for the keyboard screen */
        void updateViewKeyboard();
    }

    private static final String TAG = "KeyboardFragment";

    /** Largest script read; anything bigger is surely the wrong file */
    private static final int MAX_SCRIPT_BYTES = 256 * 1024;
    private static final int MAX_PROBLEMS_SHOWN = 10;

    /** A script in the folder */
    private static final class ScriptFile
    {
        final String itsName;
        final Uri itsUri;

        ScriptFile(String name, Uri uri)
        {
            itsName = name;
            itsUri = uri;
        }

        /** The name without its .txt */
        String displayName()
        {
            int dot = itsName.lastIndexOf('.');
            return (dot > 0) ? itsName.substring(0, dot) : itsName;
        }
    }

    private Listener itsListener;
    private SharedPreferences itsPrefs;

    private TextView itsNoOutput;
    private View itsOutputRow;
    private MaterialButtonToggleGroup itsOutputToggle;
    private MaterialCardView itsTypingCard;
    private TextView itsTypingLabel;
    private TextView itsText;
    private CheckBox itsEnterAfter;
    private Button itsSend;
    private ChipGroup itsModifiers;
    private ChipGroup itsKeys;
    private TextView itsScriptsFolder;
    private Button itsScriptsChoose;
    private TextView itsScriptsEmpty;
    private LinearLayout itsScriptsList;

    /** Stop flag of the job that is typing; null when idle */
    private AtomicBoolean itsTyping;

    private final ExecutorService itsIo = Executors.newSingleThreadExecutor();
    private final Handler itsMainHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<Uri> itsFolderPicker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocumentTree(),
                    this::onFolderPicked);

    public static KeyboardFragment newInstance()
    {
        return new KeyboardFragment();
    }

    @Override
    public void onAttach(@NonNull Context ctx)
    {
        super.onAttach(ctx);
        itsListener = (Listener)ctx;
        itsPrefs = Preferences.getSharedPrefs(ctx);
    }

    @Override
    public void onDetach()
    {
        super.onDetach();
        itsListener = null;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState)
    {
        View root = inflater.inflate(R.layout.fragment_keyboard, container,
                                     false);
        itsNoOutput = root.findViewById(R.id.no_output);
        itsOutputRow = root.findViewById(R.id.output_row);
        itsOutputToggle = root.findViewById(R.id.output_toggle);
        itsTypingCard = root.findViewById(R.id.typing_card);
        itsTypingLabel = root.findViewById(R.id.typing_label);
        itsText = root.findViewById(R.id.text);
        itsEnterAfter = root.findViewById(R.id.enter_after);
        itsSend = root.findViewById(R.id.send);
        itsModifiers = root.findViewById(R.id.modifiers);
        itsKeys = root.findViewById(R.id.keys);
        itsScriptsFolder = root.findViewById(R.id.scripts_folder);
        itsScriptsChoose = root.findViewById(R.id.scripts_choose);
        itsScriptsEmpty = root.findViewById(R.id.scripts_empty);
        itsScriptsList = root.findViewById(R.id.scripts_list);

        itsOutputToggle.addOnButtonCheckedListener(
                (group, checkedId, isChecked) -> {
                    if (isChecked) {
                        Preferences.setKeyboardUseUsb(
                                itsPrefs, checkedId == R.id.output_usb);
                    }
                });
        root.findViewById(R.id.typing_stop).setOnClickListener(v -> stop());
        itsSend.setOnClickListener(v -> sendText());
        for (int i = 0; i < itsKeys.getChildCount(); ++i) {
            View chip = itsKeys.getChildAt(i);
            chip.setOnClickListener(v -> sendKey((String)v.getTag()));
        }
        itsScriptsChoose.setOnClickListener(v -> chooseFolder());
        root.findViewById(R.id.scripts_refresh)
            .setOnClickListener(v -> loadScripts());
        root.findViewById(R.id.scripts_help)
            .setOnClickListener(v -> showScriptHelp());
        return root;
    }

    @Override
    public void onResume()
    {
        super.onResume();
        itsListener.updateViewKeyboard();
        updateOutputs();
        // Scripts may have been edited in another app meanwhile
        loadScripts();
    }

    @Override
    public void onDestroyView()
    {
        // Leaving the screen stops a running job: nothing should keep
        // typing into the computer once its Stop button is out of reach.
        stop();
        super.onDestroyView();
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        itsIo.shutdown();
    }

    /** Show the output choice, or why there is none */
    private void updateOutputs()
    {
        boolean bt = isBluetoothAvailable();
        boolean usb = Preferences.getAutoTypeUsbEnabled(itsPrefs);
        itsNoOutput.setVisibility((bt || usb) ? View.GONE : View.VISIBLE);
        itsOutputRow.setVisibility((bt && usb) ? View.VISIBLE : View.GONE);
        itsOutputToggle.check(useUsb() ? R.id.output_usb :
                              R.id.output_bluetooth);
        updateEnabled();
    }

    private boolean isBluetoothAvailable()
    {
        return ApiCompat.supportsBluetoothHid() &&
               Preferences.getBluetoothEnabled(itsPrefs) &&
               Preferences.getAutoTypeBluetoothEnabled(itsPrefs);
    }

    /** Whether to type over USB: the choice when both exist, else the one */
    private boolean useUsb()
    {
        boolean bt = isBluetoothAvailable();
        boolean usb = Preferences.getAutoTypeUsbEnabled(itsPrefs);
        if (bt && usb) {
            return Preferences.getKeyboardUseUsb(itsPrefs);
        }
        return usb;
    }

    private void updateEnabled()
    {
        boolean canType = (itsTyping == null) &&
                          (isBluetoothAvailable() ||
                           Preferences.getAutoTypeUsbEnabled(itsPrefs));
        itsSend.setEnabled(canType);
        for (int i = 0; i < itsKeys.getChildCount(); ++i) {
            itsKeys.getChildAt(i).setEnabled(canType);
        }
        for (int i = 0; i < itsScriptsList.getChildCount(); ++i) {
            itsScriptsList.getChildAt(i).setEnabled(canType);
        }
    }

    // ---------------------------------------------------------------------
    // Free typing and keys

    private void sendText()
    {
        CharSequence typed = itsText.getText();
        String text = (typed != null) ? typed.toString() : "";
        String mods = activeModifiers();
        if (!mods.isEmpty()) {
            if (text.codePointCount(0, text.length()) != 1) {
                Toast.makeText(requireContext(),
                               R.string.keyboard_mods_need_one_key,
                               Toast.LENGTH_LONG).show();
                return;
            }
            String key = text.equals(" ") ? "SPACE" : text;
            clearModifiers();
            type(parse(true, mods + key), null);
            return;
        }
        if (itsEnterAfter.isChecked()) {
            // parseText turns the trailing line break into Enter
            text += "\n";
        }
        if (text.isEmpty()) {
            return;
        }
        type(parse(false, text), null);
    }

    private void sendKey(String key)
    {
        String mods = activeModifiers();
        clearModifiers();
        type(parse(true, mods + key), null);
    }

    /** The checked modifiers as script tokens, e.g. "CTRL ALT ", or "" */
    private String activeModifiers()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < itsModifiers.getChildCount(); ++i) {
            Chip chip = (Chip)itsModifiers.getChildAt(i);
            if (chip.isChecked()) {
                sb.append(chip.getTag()).append(' ');
            }
        }
        return sb.toString();
    }

    private void clearModifiers()
    {
        itsModifiers.clearCheck();
    }

    // ---------------------------------------------------------------------
    // Scripts

    private void chooseFolder()
    {
        String current = Preferences.getKeyboardScriptFolder(itsPrefs);
        itsFolderPicker.launch((current != null) ? Uri.parse(current) : null);
    }

    private void onFolderPicked(@Nullable Uri tree)
    {
        if (tree == null) {
            return;
        }
        Context ctx = requireContext();
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        String old = Preferences.getKeyboardScriptFolder(itsPrefs);
        if ((old != null) && !old.equals(tree.toString())) {
            try {
                ctx.getContentResolver().releasePersistableUriPermission(
                        Uri.parse(old), flags);
            } catch (SecurityException ignored) {
                // Already gone
            }
        }
        try {
            ctx.getContentResolver().takePersistableUriPermission(tree, flags);
        } catch (SecurityException e) {
            PasswdSafeUtil.showErrorMsg(
                    getString(R.string.keyboard_scripts_folder_gone),
                    new ActContext(ctx));
            return;
        }
        Preferences.setKeyboardScriptFolder(itsPrefs, tree.toString());
        loadScripts();
    }

    /** List the folder's scripts off the main thread */
    private void loadScripts()
    {
        String folder = Preferences.getKeyboardScriptFolder(itsPrefs);
        if (folder == null) {
            showScripts(null, null, false);
            return;
        }
        Context appCtx = requireContext().getApplicationContext();
        Uri tree = Uri.parse(folder);
        itsIo.execute(() -> {
            String name = null;
            List<ScriptFile> scripts = null;
            try {
                DocumentFile dir = DocumentFile.fromTreeUri(appCtx, tree);
                if ((dir != null) && dir.canRead()) {
                    name = dir.getName();
                    scripts = new ArrayList<>();
                    for (DocumentFile f : dir.listFiles()) {
                        String fname = f.getName();
                        if (f.isFile() && (fname != null) &&
                            fname.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                            scripts.add(new ScriptFile(fname, f.getUri()));
                        }
                    }
                    scripts.sort((a, b) -> a.itsName.compareToIgnoreCase(
                            b.itsName));
                }
            } catch (RuntimeException e) {
                // SecurityException or a provider failure: permission lost
                scripts = null;
            }
            final String fName = name;
            final List<ScriptFile> fScripts = scripts;
            itsMainHandler.post(() -> showScripts(fName, fScripts, true));
        });
    }

    /**
     * @param hasFolder whether a folder is configured; with scripts null it
     *                  could not be read
     */
    private void showScripts(@Nullable String folderName,
                             @Nullable List<ScriptFile> scripts,
                             boolean hasFolder)
    {
        if (getView() == null) {
            return;
        }
        itsScriptsList.removeAllViews();
        itsScriptsFolder.setVisibility(hasFolder ? View.VISIBLE : View.GONE);
        itsScriptsFolder.setText(folderName);
        itsScriptsChoose.setText(hasFolder ? R.string.keyboard_scripts_change :
                                 R.string.keyboard_scripts_choose);
        if (!hasFolder) {
            itsScriptsEmpty.setText(R.string.keyboard_scripts_no_folder);
            itsScriptsEmpty.setVisibility(View.VISIBLE);
            return;
        }
        if (scripts == null) {
            itsScriptsEmpty.setText(R.string.keyboard_scripts_folder_gone);
            itsScriptsEmpty.setVisibility(View.VISIBLE);
            return;
        }
        itsScriptsEmpty.setText(R.string.keyboard_scripts_empty);
        itsScriptsEmpty.setVisibility(scripts.isEmpty() ? View.VISIBLE :
                                      View.GONE);
        LayoutInflater inflater = getLayoutInflater();
        for (ScriptFile script : scripts) {
            View row = inflater.inflate(R.layout.item_keyboard_script,
                                        itsScriptsList, false);
            TextView name = row.findViewById(R.id.script_name);
            name.setText(script.displayName());
            row.setOnClickListener(v -> readScript(script, false));
            row.setOnLongClickListener(v -> {
                readScript(script, true);
                return true;
            });
            itsScriptsList.addView(row);
        }
        updateEnabled();
    }

    /** Read a script off the main thread, then run or show it */
    private void readScript(ScriptFile script, boolean view)
    {
        Context appCtx = requireContext().getApplicationContext();
        itsIo.execute(() -> {
            String text = null;
            int error = 0;
            try (InputStream in = appCtx.getContentResolver()
                                        .openInputStream(script.itsUri)) {
                if (in == null) {
                    throw new IOException("no stream");
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > MAX_SCRIPT_BYTES) {
                        error = R.string.keyboard_script_too_large;
                        break;
                    }
                }
                if (error == 0) {
                    text = buf.toString(StandardCharsets.UTF_8.name());
                }
            } catch (IOException | RuntimeException e) {
                error = R.string.keyboard_script_read_failed;
            }
            final String fText = text;
            final int fError = error;
            itsMainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (fText == null) {
                    PasswdSafeUtil.showErrorMsg(
                            getString(fError, script.itsName),
                            new ActContext(requireContext()));
                } else if (view) {
                    showScript(script, fText);
                } else {
                    type(parse(true, fText), script.displayName());
                }
            });
        });
    }

    private void showScript(ScriptFile script, String text)
    {
        TextView content = new TextView(requireContext());
        content.setText(text);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextIsSelectable(true);
        int pad = getResources().getDimensionPixelSize(R.dimen.screen_gutter);
        content.setPadding(pad + pad / 2, pad, pad + pad / 2, 0);
        android.widget.ScrollView scroll =
                new android.widget.ScrollView(requireContext());
        scroll.addView(content);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(script.displayName())
                .setView(scroll)
                .setPositiveButton(R.string.keyboard_script_run,
                                   (d, w) -> type(parse(true, text),
                                                  script.displayName()))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void showScriptHelp()
    {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.keyboard_script_help)
                .setMessage(R.string.keyboard_script_help_text)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    // ---------------------------------------------------------------------
    // Typing

    /**
     * Parse on the main thread: references read the open file, which is
     * only safe here.
     */
    @NonNull
    private KeyScript.Result parse(boolean script, @NonNull String source)
    {
        OutputInterface.Language lang =
                Preferences.getAutoTypeLanguagePref(itsPrefs);
        Context ctx = requireContext();
        KeyScript.Result result = itsListener.useFileData(fileData -> {
            KeyScript.CredentialResolver resolver =
                    new FileCredentialResolver(fileData, ctx);
            return script ? KeyScript.parseScript(source, lang, resolver) :
                   KeyScript.parseText(source, lang, resolver);
        });
        if (result == null) {
            // No file open; references will be reported
            result = script ? KeyScript.parseScript(source, lang, null) :
                     KeyScript.parseText(source, lang, null);
        }
        return result;
    }

    /**
     * Type a parse result, or explain why it can't be typed.
     * @param label what is typing, for the status card; null for free text
     */
    private void type(@NonNull KeyScript.Result result, @Nullable String label)
    {
        if (!result.isOk()) {
            showProblems(result.problems);
            return;
        }
        Keystrokes keys = result.keystrokes;
        if ((keys == null) || keys.isEmpty()) {
            return;
        }
        if (itsTyping != null) {
            keys.wipe();
            Toast.makeText(requireContext(), R.string.keyboard_busy,
                           Toast.LENGTH_SHORT).show();
            return;
        }
        if (useUsb()) {
            typeUsb(keys, label);
        } else if (isBluetoothAvailable()) {
            typeBluetooth(keys, label);
        } else {
            keys.wipe();
        }
    }

    private void showProblems(List<KeyScript.Problem> problems)
    {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(problems.size(), MAX_PROBLEMS_SHOWN);
        for (int i = 0; i < shown; ++i) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(problems.get(i));
        }
        if (problems.size() > shown) {
            sb.append('\n').append(getString(R.string.keyboard_problems_more,
                                             problems.size() - shown));
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.keyboard_problems_title)
                .setMessage(sb)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void typeUsb(Keystrokes keys, @Nullable String label)
    {
        Context ctx = requireContext();
        String devicePath = Preferences.getUsbHidDevicePath(itsPrefs);
        if (!HidStatus.probe(devicePath).isReady() &&
            Preferences.getUsbHidAutoSetup(itsPrefs)) {
            // One-time root setup, as for auto-type; then type
            Toast.makeText(ctx, R.string.autotype_usb_preparing,
                           Toast.LENGTH_SHORT).show();
            HidGadgetSetup.ensureReadyAsync(devicePath, new HidGadgetSetup.ReadyCallback()
            {
                @Override
                public void onReady(@NonNull String readyPath)
                {
                    if (!readyPath.equals(devicePath)) {
                        Preferences.setUsbHidDevicePath(itsPrefs, readyPath);
                    }
                    if (isAdded() && (itsTyping == null)) {
                        typeUsbNow(readyPath, keys, label);
                    } else {
                        keys.wipe();
                    }
                }

                @Override
                public void onFailed(@NonNull String message)
                {
                    keys.wipe();
                    if (isAdded()) {
                        PasswdSafeUtil.showErrorMsg(
                                getString(R.string.autotype_usb_prepare_failed,
                                          message),
                                new ActContext(requireContext()));
                    }
                }
            });
            return;
        }
        typeUsbNow(devicePath, keys, label);
    }

    private void typeUsbNow(String devicePath, Keystrokes keys,
                            @Nullable String label)
    {
        AtomicBoolean cancel = new AtomicBoolean();
        setTyping(cancel, label);
        UsbAutoType.run(devicePath, Preferences.getAutoTypeLanguagePref(itsPrefs),
                        Preferences.getUsbHidKeyDelayMs(itsPrefs), keys,
                        cancel, (error, lostChars) -> {
            boolean stopped = cancel.get();
            clearTyping(cancel);
            if (!isAdded()) {
                return;
            }
            Context ctx = requireContext();
            String msg = UsbAutoType.errorMessage(ctx, error, false);
            if (msg != null) {
                PasswdSafeUtil.showErrorMsg(msg, new ActContext(ctx));
            } else if (stopped) {
                Toast.makeText(ctx, R.string.keyboard_stopped,
                               Toast.LENGTH_SHORT).show();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void typeBluetooth(Keystrokes keys, @Nullable String label)
    {
        PasswdSafe act = (PasswdSafe)requireActivity();
        if (!BluetoothUtils.isBluetoothEnabled(act.getApplication())) {
            keys.wipe();
            Toast.makeText(act, R.string.bt_is_disabled, Toast.LENGTH_LONG)
                 .show();
            return;
        }
        if (act.btService == null) {
            keys.wipe();
            Toast.makeText(act, R.string.bt_autotype_no_service,
                           Toast.LENGTH_LONG).show();
            return;
        }
        KeyboardHostChooser.choose(act, device -> {
            BluetoothForegroundService svc = act.btService;
            if ((device == null) || (svc == null) || !isAdded() ||
                (itsTyping != null)) {
                keys.wipe();
                return;
            }
            AtomicBoolean cancel = new AtomicBoolean();
            boolean started = svc.connectAndType(
                    device.getDevice(), keys, cancel,
                    result -> onBluetoothFinished(cancel, result));
            if (started) {
                setTyping(cancel, label);
            } else {
                Toast.makeText(act, R.string.keyboard_busy,
                               Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void onBluetoothFinished(
            AtomicBoolean cancel,
            BluetoothForegroundService.TypingResult result)
    {
        PasswdSafeUtil.dbginfo(TAG, "Bluetooth typing finished: %s", result);
        clearTyping(cancel);
        if (!isAdded()) {
            return;
        }
        switch (result) {
        case LINK_LOST: {
            Toast.makeText(requireContext(), R.string.keyboard_link_lost,
                           Toast.LENGTH_LONG).show();
            break;
        }
        case CANCELLED: {
            if (cancel.get()) {
                Toast.makeText(requireContext(), R.string.keyboard_stopped,
                               Toast.LENGTH_SHORT).show();
            }
            break;
        }
        case DONE:
        case CONNECT_FAILED: {
            // The service already reports a failed connect
            break;
        }
        }
    }

    private void setTyping(AtomicBoolean cancel, @Nullable String label)
    {
        itsTyping = cancel;
        if (getView() != null) {
            itsTypingLabel.setText(
                    (label != null) ? label : getString(R.string.keyboard_typing));
            itsTypingCard.setVisibility(View.VISIBLE);
            updateEnabled();
        }
    }

    /** The job with this stop flag has ended */
    private void clearTyping(AtomicBoolean cancel)
    {
        if (itsTyping != cancel) {
            return;
        }
        itsTyping = null;
        if (getView() != null) {
            itsTypingCard.setVisibility(View.GONE);
            updateEnabled();
        }
    }

    /** Stop the running job, if any */
    private void stop()
    {
        AtomicBoolean cancel = itsTyping;
        if (cancel != null) {
            cancel.set(true);
        }
    }
}
