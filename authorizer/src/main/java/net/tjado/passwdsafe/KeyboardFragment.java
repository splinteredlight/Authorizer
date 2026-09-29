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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keyboard screen: compose text with keys, delays and entry references in
 * one box, then send it over Bluetooth or USB or save it as a script in a
 * folder the user picked; scripts in that folder can be run or edited.
 *
 * <p>The box holds the inline format of {@link KeyScript}: what it shows is
 * what is sent. Key chips insert tokens such as {ENTER} (long-press sends
 * the key at once), and with a modifier chip on, the next key or character
 * typed becomes a combo such as {WIN+r}. Nothing is typed when anything
 * fails to parse.
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
    private static final String STATE_EDITING = "editing";

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
    private EditText itsText;
    private TextView itsEditingLabel;
    private Button itsSend;
    private ChipGroup itsModifiers;
    private ChipGroup itsKeys;
    private TextView itsScriptsFolder;
    private Button itsScriptsChoose;
    private TextView itsScriptsEmpty;
    private LinearLayout itsScriptsList;

    /** Name of the script the box was loaded from or saved as, or null */
    private String itsEditingName;
    /**
     * The box text as last loaded or saved, to tell whether loading another
     * script would throw away unsaved changes; "" when nothing was
     */
    private String itsCleanText = "";
    /** Set while the fragment itself changes the box */
    private boolean itsSelfEdit;
    /** A character typed while a modifier was on: its start, or -1 */
    private int itsComboAt = -1;

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
        itsEditingLabel = root.findViewById(R.id.editing_name);
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
        root.findViewById(R.id.save).setOnClickListener(v -> saveScript());
        for (int i = 0; i < itsKeys.getChildCount(); ++i) {
            View chip = itsKeys.getChildAt(i);
            chip.setOnClickListener(v -> insertKey((String)v.getTag()));
            chip.setOnLongClickListener(v -> {
                sendKeyNow((String)v.getTag());
                return true;
            });
        }
        root.findViewById(R.id.insert_entry)
            .setOnClickListener(v -> pickEntry());
        ChipGroup delays = root.findViewById(R.id.delays);
        for (int i = 0; i < delays.getChildCount(); ++i) {
            delays.getChildAt(i).setOnClickListener(
                    v -> insertText("{DELAY " + v.getTag() + "}"));
        }
        allowInnerScroll(itsText);
        itsText.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(CharSequence s, int start,
                                          int count, int after)
            {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before,
                                      int count)
            {
                // One character typed with a modifier on: make it a combo
                // in afterTextChanged, where the text may be changed.
                if (!itsSelfEdit && (before == 0) && (count == 1) &&
                    !activeModifiers().isEmpty()) {
                    itsComboAt = start;
                }
            }

            @Override
            public void afterTextChanged(Editable s)
            {
                if (itsComboAt >= 0) {
                    int at = itsComboAt;
                    itsComboAt = -1;
                    String token = comboToken(s.subSequence(at, at + 1)
                                               .toString());
                    replaceText(at, at + 1, token);
                } else if (!itsSelfEdit && (s.length() == 0)) {
                    // Cleared: no longer editing a saved script
                    setEditingName(null);
                }
            }
        });
        if (savedInstanceState != null) {
            itsEditingName = savedInstanceState.getString(STATE_EDITING);
        }
        setEditingName(itsEditingName);
        itsScriptsChoose.setOnClickListener(v -> chooseFolder());
        root.findViewById(R.id.scripts_refresh)
            .setOnClickListener(v -> loadScripts());
        root.findViewById(R.id.scripts_help)
            .setOnClickListener(v -> showScriptHelp());
        return root;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState)
    {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_EDITING, itsEditingName);
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
        // Key chips stay enabled: tapping only edits the box
        itsSend.setEnabled(canType);
        // Script rows stay enabled for editing; only their Type button needs
        // an output
        for (int i = 0; i < itsScriptsList.getChildCount(); ++i) {
            View run = itsScriptsList.getChildAt(i).findViewById(R.id.script_run);
            if (run != null) {
                run.setEnabled(canType);
            }
        }
    }

    /**
     * Let the box scroll its own text. It is capped at a number of lines
     * inside the page's scroll view, which otherwise takes every vertical
     * swipe, so a long script could not be scrolled inside the box.
     */
    // Returning false keeps the EditText's own touch handling, including
    // performClick, so the accessibility lint does not apply.
    @SuppressLint("ClickableViewAccessibility")
    private static void allowInnerScroll(EditText box)
    {
        box.setOnTouchListener((v, event) -> {
            boolean canScroll = v.canScrollVertically(1) ||
                                v.canScrollVertically(-1);
            int action = event.getActionMasked();
            boolean ending = (action == MotionEvent.ACTION_UP) ||
                             (action == MotionEvent.ACTION_CANCEL);
            v.getParent().requestDisallowInterceptTouchEvent(canScroll &&
                                                             !ending);
            return false;
        });
    }

    // ---------------------------------------------------------------------
    // Composing and sending the box

    private String boxText()
    {
        CharSequence typed = itsText.getText();
        return (typed != null) ? typed.toString() : "";
    }

    private void sendText()
    {
        String text = boxText();
        if (text.isEmpty()) {
            return;
        }
        type(parse(text, false), null);
    }

    /** Key chip tapped: insert its token, with any modifiers that are on */
    private void insertKey(String key)
    {
        String mods = activeModifiers();
        clearModifiers();
        insertText("{" + mods + key + "}");
    }

    /** Key chip long-pressed: send that key now, the box is untouched */
    private void sendKeyNow(String key)
    {
        String mods = activeModifiers();
        clearModifiers();
        type(parse("{" + mods + key + "}", false), null);
    }

    private void pickEntry()
    {
        Context ctx = requireContext();
        List<KeyboardEntryPicker.Entry> entries = itsListener.useFileData(
                fileData -> KeyboardEntryPicker.load(fileData, ctx));
        if (entries == null) {
            Toast.makeText(ctx, R.string.keyboard_entry_no_file,
                           Toast.LENGTH_LONG).show();
            return;
        }
        KeyboardEntryPicker.show(ctx, entries, text -> {
            if (getView() != null) {
                insertText(text);
            }
        });
    }

    /**
     * The token for a character typed with modifiers on, e.g. {WIN+r}.
     * Clears the modifiers.
     */
    private String comboToken(String ch)
    {
        String key;
        switch (ch) {
        case "\n": {
            key = "ENTER";
            break;
        }
        case " ": {
            key = "SPACE";
            break;
        }
        case "\t": {
            key = "TAB";
            break;
        }
        default: {
            key = ch;
            break;
        }
        }
        String token = "{" + activeModifiers() + key + "}";
        clearModifiers();
        return token;
    }

    /**
     * Replace the selection (or insert at the cursor, or append) with text.
     * @return where the text starts
     */
    private int insertText(String text)
    {
        Editable box = itsText.getText();
        if (box == null) {
            return 0;
        }
        int start = Math.min(itsText.getSelectionStart(),
                             itsText.getSelectionEnd());
        int end = Math.max(itsText.getSelectionStart(),
                           itsText.getSelectionEnd());
        if (start < 0) {
            start = end = box.length();
        }
        replaceText(start, end, text);
        return start;
    }

    private void replaceText(int start, int end, String text)
    {
        Editable box = itsText.getText();
        if (box == null) {
            return;
        }
        itsSelfEdit = true;
        try {
            box.replace(start, end, text);
        } finally {
            itsSelfEdit = false;
        }
        itsText.setSelection(start + text.length());
    }

    /** The checked modifiers as token prefixes, e.g. "CTRL+ALT+", or "" */
    private String activeModifiers()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < itsModifiers.getChildCount(); ++i) {
            Chip chip = (Chip)itsModifiers.getChildAt(i);
            if (chip.isChecked()) {
                sb.append(chip.getTag()).append('+');
            }
        }
        return sb.toString();
    }

    private void clearModifiers()
    {
        itsModifiers.clearCheck();
    }

    private void setEditingName(@Nullable String name)
    {
        itsEditingName = name;
        if (itsEditingLabel != null) {
            itsEditingLabel.setText((name != null) ?
                                    getString(R.string.keyboard_editing, name) :
                                    null);
        }
    }

    /** Put a script into the box to edit it */
    private void loadIntoBox(String text, String name)
    {
        itsSelfEdit = true;
        try {
            itsText.setText(text);
        } finally {
            itsSelfEdit = false;
        }
        itsText.setSelection(text.length());
        itsCleanText = text;
        setEditingName(name);
        itsText.requestFocus();
        // The script list is below the fold; show the box it went into
        View root = getView();
        if (root instanceof androidx.core.widget.NestedScrollView) {
            ((androidx.core.widget.NestedScrollView)root).smoothScrollTo(0, 0);
        }
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
        // Write too, so the box can be saved there as a script
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION |
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
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
            View edit = row.findViewById(R.id.script_edit);
            View run = row.findViewById(R.id.script_run);
            edit.setContentDescription(
                    getString(R.string.keyboard_script_edit_desc,
                              script.displayName()));
            run.setContentDescription(
                    getString(R.string.keyboard_script_run_desc,
                              script.displayName()));
            // Tapping the row edits: a stray tap must not type into
            // whatever window has focus on the computer.
            row.setOnClickListener(v -> readScript(script, ScriptAction.EDIT));
            edit.setOnClickListener(v -> readScript(script, ScriptAction.EDIT));
            run.setOnClickListener(v -> readScript(script, ScriptAction.RUN));
            row.setOnLongClickListener(v -> {
                readScript(script, ScriptAction.VIEW);
                return true;
            });
            itsScriptsList.addView(row);
        }
        updateEnabled();
    }

    /** What to do with a script once it is read */
    private enum ScriptAction { RUN, EDIT, VIEW }

    /** Read a script off the main thread, then act on it */
    private void readScript(ScriptFile script, ScriptAction action)
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
                } else {
                    switch (action) {
                    case RUN: {
                        type(parse(fText, true), script.displayName());
                        break;
                    }
                    case EDIT: {
                        editScript(script, fText);
                        break;
                    }
                    case VIEW: {
                        showScript(script, fText);
                        break;
                    }
                    }
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
                                   (d, w) -> type(parse(text, true),
                                                  script.displayName()))
                .setNeutralButton(R.string.keyboard_script_edit,
                                  (d, w) -> editScript(script, text))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    /**
     * Load a script into the box. A Ducky Script file is rewritten in the
     * box's format; it has to parse first, or the rewrite would drop lines.
     */
    private void editScript(ScriptFile script, String text)
    {
        String current = boxText();
        if (!current.isEmpty() && !current.equals(itsCleanText)) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(getString(R.string.keyboard_replace_box,
                                          script.displayName()))
                    .setPositiveButton(R.string.keyboard_save_replace,
                                       (d, w) -> doEditScript(script, text))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        doEditScript(script, text);
    }

    private void doEditScript(ScriptFile script, String text)
    {
        String boxText = text;
        if (KeyScript.isDucky(text)) {
            // Check the structure only; references need not resolve here
            KeyScript.Result check = KeyScript.parseScript(
                    text, Preferences.getAutoTypeLanguagePref(itsPrefs),
                    (path, field) -> "");
            if (!check.isOk()) {
                showProblems(check.problems);
                return;
            }
            if (check.keystrokes != null) {
                check.keystrokes.wipe();
            }
            boxText = KeyScript.duckyToInline(text);
            Toast.makeText(requireContext(), R.string.keyboard_script_converted,
                           Toast.LENGTH_LONG).show();
        }
        loadIntoBox(boxText, script.displayName());
    }

    // ---------------------------------------------------------------------
    // Saving

    private void saveScript()
    {
        Context ctx = requireContext();
        String text = boxText();
        if (text.trim().isEmpty()) {
            Toast.makeText(ctx, R.string.keyboard_save_empty,
                           Toast.LENGTH_SHORT).show();
            return;
        }
        if (Preferences.getKeyboardScriptFolder(itsPrefs) == null) {
            Toast.makeText(ctx, R.string.keyboard_save_no_folder,
                           Toast.LENGTH_LONG).show();
            chooseFolder();
            return;
        }

        View view = getLayoutInflater().inflate(R.layout.dialog_keyboard_save,
                                                null);
        EditText nameField = view.findViewById(R.id.save_name);
        if (itsEditingName != null) {
            nameField.setText(itsEditingName);
            nameField.setSelection(itsEditingName.length());
        }
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.keyboard_save_title)
                .setView(view)
                .setPositiveButton(R.string.keyboard_save, (d, w) -> {
                    CharSequence n = nameField.getText();
                    String name = (n != null) ? n.toString().trim() : "";
                    if (name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                        name = name.substring(0, name.length() - 4).trim();
                    }
                    if (name.isEmpty() || name.contains("/") ||
                        name.contains("\\")) {
                        Toast.makeText(ctx, R.string.keyboard_save_bad_name,
                                       Toast.LENGTH_LONG).show();
                        return;
                    }
                    writeScript(name, text, false);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Write the script off the main thread.
     * @param replace whether the user agreed to replace an existing file
     */
    private void writeScript(String name, String text, boolean replace)
    {
        String folder = Preferences.getKeyboardScriptFolder(itsPrefs);
        if (folder == null) {
            return;
        }
        Context appCtx = requireContext().getApplicationContext();
        String fileName = name + ".txt";
        itsIo.execute(() -> {
            boolean exists = false;
            boolean saved = false;
            try {
                DocumentFile dir = DocumentFile.fromTreeUri(appCtx,
                                                            Uri.parse(folder));
                if ((dir != null) && dir.canWrite()) {
                    DocumentFile file = findScript(dir, fileName);
                    if ((file != null) && !replace) {
                        exists = true;
                    } else {
                        if (file == null) {
                            file = dir.createFile("text/plain", fileName);
                        }
                        if (file != null) {
                            try (OutputStream out = appCtx.getContentResolver()
                                    .openOutputStream(file.getUri(), "wt")) {
                                if (out != null) {
                                    out.write(text.getBytes(
                                            StandardCharsets.UTF_8));
                                    saved = true;
                                }
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                PasswdSafeUtil.dbginfo(TAG, e, "save failed");
            }
            final boolean fExists = exists;
            final boolean fSaved = saved;
            itsMainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                Context ctx = requireContext();
                if (fExists) {
                    new MaterialAlertDialogBuilder(ctx)
                            .setMessage(getString(R.string.keyboard_save_exists,
                                                  fileName))
                            .setPositiveButton(R.string.keyboard_save_replace,
                                               (d, w) -> writeScript(name, text,
                                                                     true))
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                } else if (fSaved) {
                    itsCleanText = text;
                    setEditingName(name);
                    Toast.makeText(ctx, getString(R.string.keyboard_saved,
                                                  fileName),
                                   Toast.LENGTH_SHORT).show();
                    loadScripts();
                } else {
                    PasswdSafeUtil.showErrorMsg(
                            getString(R.string.keyboard_save_failed),
                            new ActContext(ctx));
                }
            });
        });
    }

    /** The folder's file with this name, ignoring case, or null */
    @Nullable
    private static DocumentFile findScript(DocumentFile dir, String fileName)
    {
        for (DocumentFile f : dir.listFiles()) {
            String n = f.getName();
            if (f.isFile() && (n != null) && n.equalsIgnoreCase(fileName)) {
                return f;
            }
        }
        return null;
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
    private KeyScript.Result parse(@NonNull String source, boolean file)
    {
        OutputInterface.Language lang =
                Preferences.getAutoTypeLanguagePref(itsPrefs);
        Context ctx = requireContext();
        KeyScript.Result result = itsListener.useFileData(fileData -> {
            KeyScript.CredentialResolver resolver =
                    new FileCredentialResolver(fileData, ctx);
            return file ? KeyScript.parseFile(source, lang, resolver) :
                   KeyScript.parseText(source, lang, resolver);
        });
        if (result == null) {
            // No file open; references will be reported
            result = file ? KeyScript.parseFile(source, lang, null) :
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
