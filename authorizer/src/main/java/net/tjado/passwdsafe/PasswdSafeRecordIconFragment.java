/*
 * Copyright 2014 Mike Penz
 * Modified work Copyright 2016 Tjado Mäcke <tjado@maecke.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * https://github.com/mikepenz/Android-Iconics
 */

package net.tjado.passwdsafe;


import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.SearchView;

import net.tjado.passwdsafe.lib.ObjectHolder;
import net.tjado.passwdsafe.lib.PasswdSafeUtil;
import net.tjado.passwdsafe.util.Pair;
import net.tjado.passwdsafe.view.PasswdLocation;
import net.tjado.passwdsafe.view.PasswdRecordIconAdapter;
import net.tjado.passwdsafe.view.GridAutofitLayoutManager;

import org.pwsafe.lib.file.PwsRecord;

import com.mikepenz.iconics.Iconics;
import com.mikepenz.iconics.typeface.ITypeface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fragment for showing notes of a password record
 */
public class PasswdSafeRecordIconFragment
        extends AbstractPasswdSafeRecordFragment
{

    private final ArrayList<String> icons = new ArrayList<>();
    private PasswdRecordIconAdapter mAdapter;
    private RecyclerView itsRecyclerView;

    private SearchView itsSearchView;

    private String currentIcon;
    private int selectedPos = 0;

    /**
     * Max allowed duration for a "click", in milliseconds.
     */
    private static final int MAX_CLICK_DURATION = 1000;

    /**
     * Max allowed distance to move during a "click", in DP.
     */
    private static final int MAX_CLICK_DISTANCE = 15;

    private long pressStartTime;
    private float pressedX;
    private float pressedY;
    private boolean stayedWithinClickDistance;

    private static final String TAG = "PasswdSafeRecordIconFragment";

    /**
     * Create a new instance of the fragment
     */
    public static PasswdSafeRecordIconFragment newInstance(
            PasswdLocation location)
    {
        PasswdSafeRecordIconFragment frag =
                new PasswdSafeRecordIconFragment();
        frag.setArguments(createArgs(location));
        return frag;

    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState)
    {
        View root = inflater.inflate(R.layout.fragment_passwdsafe_record_icon, null, false);

        itsSearchView = root.findViewById(R.id.search_view);
        itsSearchView.setIconifiedByDefault(false);
        itsSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                onSearch(query);
                itsSearchView.clearFocus();
                return false;
            }
            @Override
            public boolean onQueryTextChange(String query) {
                onSearch(query);
                return false;
            }
        });


        return root;
    }

    @Override
    public void onResume()
    {
        super.onResume();
        //getListener().updateViewRecord(getLocation());
        //refresh();
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState){
        super.onViewCreated(view, savedInstanceState);

        // Init and Setup RecyclerView

        // calculate the correct column width over converting the CardView dp width to pixel
        final float scale = getResources().getDisplayMetrics().density;
        final int spaceWidth = (int) (56.0f * scale + 0.5f);

        itsRecyclerView = view.findViewById(R.id.list);
        itsRecyclerView.setLayoutManager(
                new GridAutofitLayoutManager(getActivity(), spaceWidth));
        itsRecyclerView.setItemAnimator(new DefaultItemAnimator());

        // Sort the fonts so the icon sets appear in a stable order
        final List<ITypeface> fonts =
                new ArrayList<>(Iconics.getRegisteredFonts(requireContext()));
        Collections.sort(fonts, (o1, o2) -> o1.getFontName().compareTo(o2.getFontName()));
        icons.clear();
        for (ITypeface typeface : fonts) {
            PasswdSafeUtil.dbginfo(TAG, "Font: " + typeface.getFontName());
            icons.addAll(typeface.getIcons());
        }

        mAdapter = new PasswdRecordIconAdapter(icons, icon -> {
            if (itsSearchView.hasFocus()) {
                itsSearchView.clearFocus();
            }
            saveIconChange(icon);
            refreshIconHighlight(icon);
        });
        itsRecyclerView.setAdapter(mAdapter);
        scrollToIcon(currentIcon);
    }

    private void scrollToIcon(String icon)
    {
        if ((itsRecyclerView == null) || (mAdapter == null)) {
            return;
        }
        int pos = mAdapter.positionOf(icon);
        if (pos >= 0) {
            itsRecyclerView.scrollToPosition(pos);
        }
    }

    @Override
    protected void doOnCreateOptionsMenu(Menu menu, MenuInflater inflater)
    {
        inflater.inflate(R.menu.fragment_passwdsafe_record_notes, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu)
    {
        super.onPrepareOptionsMenu(menu);
    }

    @Override
    protected void doRefresh(@NonNull RecordInfo info)
    {
        PasswdSafeUtil.dbginfo(TAG, "doRefresh");
        if (currentIcon == null) {
            currentIcon = info.itsFileData.getIcon(info.itsRec);
            if (mAdapter != null) {
                mAdapter.setSelectedIcon(currentIcon);
                scrollToIcon(currentIcon);
            }
        }
    }

    private synchronized void refreshIconHighlight(String newIcon)
    {
        currentIcon = newIcon;
        if (mAdapter != null) {
            mAdapter.setSelectedIcon(newIcon);
        }
    }

    void saveIconChange(final String itemValue) {

        final ObjectHolder<Pair<Boolean, PasswdLocation>> rc = new ObjectHolder<>();
        useRecordFile((RecordFileUser)(info, fileData) -> {

            PwsRecord record;
            boolean newRecord;
            if (info != null) {
                record = info.itsRec;
                newRecord = false;
            } else {
                record = fileData.createRecord();
                record.setLoaded();
                newRecord = true;
            }

            if(fileData.isProtected(record)) {
                return null;
            }

            /*if(!fileData.canEdit()) {
                return null;
            }*/

            if (fileData.getIcon(record) != itemValue) {
                fileData.setIcon(itemValue, record);
            }

            if (newRecord) {
                fileData.addRecord(record);
            }

            rc.set(new Pair<>((newRecord || record.isModified()), new PasswdLocation(record, fileData)));

            return null;
        });

        if (rc == null || rc.get() == null) {
            return;
        }
        getListener().finishEditRecord(rc.get().first, rc.get().second, false);
    }

    void onSearch(String s)
    {
        if (mAdapter != null) {
            mAdapter.setFilter(s);
        }
    }
}
