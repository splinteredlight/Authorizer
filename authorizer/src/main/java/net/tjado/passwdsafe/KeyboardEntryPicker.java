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
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.tjado.passwdsafe.file.PasswdFileData;
import net.tjado.passwdsafe.file.PasswdRecord;

import org.pwsafe.lib.file.PwsRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lets the user pick an entry of the open file and one of its fields, and
 * hands back a reference such as {Server.password} to insert into the
 * keyboard text. Only which fields exist is read, never their values.
 */
final class KeyboardEntryPicker
{
    interface Listener
    {
        void onInsert(@NonNull String text);
    }

    /** An entry as the picker shows it */
    static final class Entry
    {
        final String itsTitle;
        final String itsGroup;
        /** "Title", or "Group/Title" when the title is not unique */
        String itsRef;
        final boolean itsHasUser;
        final boolean itsHasPassword;
        final boolean itsHasOtp;
        final boolean itsHasUrl;
        final boolean itsHasEmail;
        final boolean itsHasNotes;

        Entry(String title, String group, boolean user, boolean password,
              boolean otp, boolean url, boolean email, boolean notes)
        {
            itsTitle = title;
            itsGroup = group;
            itsHasUser = user;
            itsHasPassword = password;
            itsHasOtp = otp;
            itsHasUrl = url;
            itsHasEmail = email;
            itsHasNotes = notes;
        }
    }

    private KeyboardEntryPicker()
    {
    }

    /**
     * The entries of the file, sorted by title. Call on the main thread
     * from inside useFileData.
     */
    @NonNull
    static List<Entry> load(@NonNull PasswdFileData fileData,
                            @NonNull Context ctx)
    {
        List<Entry> entries = new ArrayList<>();
        Map<String, Integer> titleCounts = new HashMap<>();
        for (PwsRecord rec : fileData.getRecords()) {
            String title = fileData.getTitle(rec);
            // A reference can't hold braces or line breaks
            if (TextUtils.isEmpty(title) || title.contains("{") ||
                title.contains("}") || title.contains("\n")) {
                continue;
            }
            String group = FileCredentialResolver.groupPath(fileData, rec);
            PasswdRecord passwdRec = fileData.getPasswdRecord(rec);
            String password = (passwdRec != null) ?
                              passwdRec.getPassword(fileData) :
                              fileData.getPassword(rec);
            String notes = fileData.getNotes(rec, ctx).getNotes();
            entries.add(new Entry(
                    title, group,
                    !TextUtils.isEmpty(fileData.getUsername(rec)),
                    !TextUtils.isEmpty(password),
                    !TextUtils.isEmpty(fileData.getOtp(rec)),
                    !TextUtils.isEmpty(fileData.getURL(
                            rec, PasswdFileData.UrlStyle.URL_ONLY)),
                    !TextUtils.isEmpty(fileData.getEmail(
                            rec, PasswdFileData.EmailStyle.ADDR_ONLY)),
                    !TextUtils.isEmpty(notes)));
            titleCounts.merge(title.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        for (Entry e : entries) {
            boolean unique =
                    titleCounts.get(e.itsTitle.toLowerCase(Locale.ROOT)) == 1;
            e.itsRef = (unique || e.itsGroup.isEmpty()) ? e.itsTitle :
                       e.itsGroup + "/" + e.itsTitle;
        }
        entries.sort((a, b) -> {
            int c = a.itsTitle.compareToIgnoreCase(b.itsTitle);
            return (c != 0) ? c : a.itsGroup.compareToIgnoreCase(b.itsGroup);
        });
        return entries;
    }

    /** Show the searchable entry list, then the field choice */
    static void show(@NonNull Context ctx, @NonNull List<Entry> entries,
                     @NonNull Listener listener)
    {
        View view = LayoutInflater.from(ctx).inflate(
                R.layout.dialog_keyboard_entry, null);
        EditText search = view.findViewById(R.id.entry_search);
        ListView list = view.findViewById(R.id.entry_list);
        TextView empty = view.findViewById(R.id.entry_empty);

        EntryAdapter adapter = new EntryAdapter(entries);
        list.setAdapter(adapter);
        AlertDialog dialog = new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.keyboard_entry_title)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .create();
        Runnable updateEmpty = () -> {
            boolean none = adapter.getCount() == 0;
            empty.setVisibility(none ? View.VISIBLE : View.GONE);
            list.setVisibility(none ? View.GONE : View.VISIBLE);
        };
        updateEmpty.run();
        search.addTextChangedListener(new TextWatcher()
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
            }

            @Override
            public void afterTextChanged(Editable s)
            {
                adapter.filter(s.toString());
                updateEmpty.run();
            }
        });
        list.setOnItemClickListener((parent, v, position, id) -> {
            Entry entry = adapter.getItem(position);
            dialog.dismiss();
            chooseField(ctx, entry, listener);
        });
        dialog.show();
    }

    private static void chooseField(Context ctx, Entry entry,
                                     Listener listener)
    {
        List<String> labels = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        String ref = entry.itsRef;
        if (entry.itsHasUser && entry.itsHasPassword) {
            labels.add(ctx.getString(R.string.keyboard_field_login));
            texts.add("{" + ref + ".user}{TAB}{" + ref + ".password}{ENTER}");
        }
        if (entry.itsHasUser) {
            labels.add(ctx.getString(R.string.keyboard_field_user));
            texts.add("{" + ref + ".user}");
        }
        if (entry.itsHasPassword) {
            labels.add(ctx.getString(R.string.keyboard_field_password));
            texts.add("{" + ref + ".password}");
        }
        if (entry.itsHasOtp) {
            labels.add(ctx.getString(R.string.keyboard_field_otp));
            texts.add("{" + ref + ".otp}");
        }
        if (entry.itsHasUrl) {
            labels.add(ctx.getString(R.string.keyboard_field_url));
            texts.add("{" + ref + ".url}");
        }
        if (entry.itsHasEmail) {
            labels.add(ctx.getString(R.string.keyboard_field_email));
            texts.add("{" + ref + ".email}");
        }
        if (entry.itsHasNotes) {
            labels.add(ctx.getString(R.string.keyboard_field_notes));
            texts.add("{" + ref + ".notes}");
        }
        // Always offer the title, so an entry with no fields still works
        labels.add(ctx.getString(R.string.title));
        texts.add("{" + ref + ".title}");

        new MaterialAlertDialogBuilder(ctx)
                .setTitle(entry.itsTitle)
                .setItems(labels.toArray(new CharSequence[0]),
                          (d, which) -> listener.onInsert(texts.get(which)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Title over group, filtered by a search string */
    private static final class EntryAdapter extends BaseAdapter
    {
        private final List<Entry> itsAll;
        private final List<Entry> itsShown = new ArrayList<>();

        EntryAdapter(List<Entry> all)
        {
            itsAll = all;
            itsShown.addAll(all);
        }

        void filter(String query)
        {
            String q = query.trim().toLowerCase(Locale.ROOT);
            itsShown.clear();
            for (Entry e : itsAll) {
                if (q.isEmpty() ||
                    e.itsTitle.toLowerCase(Locale.ROOT).contains(q) ||
                    e.itsGroup.toLowerCase(Locale.ROOT).contains(q)) {
                    itsShown.add(e);
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount()
        {
            return itsShown.size();
        }

        @Override
        public Entry getItem(int position)
        {
            return itsShown.get(position);
        }

        @Override
        public long getItemId(int position)
        {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent)
        {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(parent.getContext()).inflate(
                        android.R.layout.simple_list_item_2, parent, false);
            }
            Entry e = itsShown.get(position);
            ((TextView)v.findViewById(android.R.id.text1)).setText(e.itsTitle);
            TextView sub = v.findViewById(android.R.id.text2);
            sub.setText(e.itsGroup);
            sub.setVisibility(e.itsGroup.isEmpty() ? View.GONE : View.VISIBLE);
            return v;
        }
    }
}
