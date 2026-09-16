/*
 * Copyright (©) 2026 Authorizer contributors
 * Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */
package net.tjado.passwdsafe.view;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.mikepenz.iconics.IconicsDrawable;
import com.mikepenz.iconics.utils.IconicsDrawableExtensionsKt;
import com.mikepenz.iconics.view.IconicsImageView;

import net.tjado.passwdsafe.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Grid adapter for the record icon picker. Replaces the former FastAdapter
 * based implementation with a plain RecyclerView adapter.
 */
public class PasswdRecordIconAdapter
        extends RecyclerView.Adapter<PasswdRecordIconAdapter.ViewHolder>
{
    /** Callback when an icon is tapped */
    public interface Listener
    {
        void onIconClicked(@NonNull String icon);
    }

    private final List<String> itsAllIcons;
    private final List<String> itsShownIcons = new ArrayList<>();
    private final Listener itsListener;
    private String itsSelectedIcon;

    public PasswdRecordIconAdapter(@NonNull List<String> icons,
                                   @NonNull Listener listener)
    {
        itsAllIcons = icons;
        itsShownIcons.addAll(icons);
        itsListener = listener;
    }

    /** Filter the shown icons by a case-insensitive substring */
    public void setFilter(String query)
    {
        itsShownIcons.clear();
        if ((query == null) || query.isEmpty()) {
            itsShownIcons.addAll(itsAllIcons);
        } else {
            String q = query.toLowerCase(Locale.ROOT);
            for (String icon : itsAllIcons) {
                if (icon.toLowerCase(Locale.ROOT).contains(q)) {
                    itsShownIcons.add(icon);
                }
            }
        }
        notifyDataSetChanged();
    }

    /** Set the highlighted icon */
    public void setSelectedIcon(String icon)
    {
        int oldPos = positionOf(itsSelectedIcon);
        itsSelectedIcon = icon;
        int newPos = positionOf(icon);
        if (oldPos >= 0) {
            notifyItemChanged(oldPos);
        }
        if (newPos >= 0) {
            notifyItemChanged(newPos);
        }
    }

    /** Get the position of an icon in the shown list, or -1 */
    public int positionOf(String icon)
    {
        return (icon == null) ? -1 : itsShownIcons.indexOf(icon);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
    {
        View v = LayoutInflater.from(parent.getContext())
                               .inflate(R.layout.row_icon, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position)
    {
        String icon = itsShownIcons.get(position);
        Context ctx = holder.itemView.getContext();
        int color = MaterialColors.getColor(
                holder.itemView, icon.equals(itsSelectedIcon) ?
                        com.google.android.material.R.attr.colorOnPrimary :
                        com.google.android.material.R.attr.colorOnSurfaceVariant);

        IconicsDrawable drawable = new IconicsDrawable(ctx, icon);
        IconicsDrawableExtensionsKt.setColorInt(drawable, color);
        drawable.setRespectFontBounds(true);
        holder.image.setIcon(drawable);
        holder.itemView.setSelected(icon.equals(itsSelectedIcon));
        holder.itemView.setOnClickListener(v -> itsListener.onIconClicked(icon));
    }

    @Override
    public int getItemCount()
    {
        return itsShownIcons.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder
    {
        final IconicsImageView image;

        ViewHolder(@NonNull View itemView)
        {
            super(itemView);
            image = itemView.findViewById(R.id.icon);
        }
    }
}
