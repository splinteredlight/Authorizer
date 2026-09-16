/**
 * Authorizer
 *
 *  Copyright 2016 by Tjado Mäcke <tjado@maecke.de>
 *  Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */

package net.tjado.passwdsafe.view;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;
import com.mikepenz.iconics.view.IconicsTextView;
import com.unnamed.b.atv.model.TreeNode;

import net.tjado.passwdsafe.Preferences;
import net.tjado.passwdsafe.R;

/**
 * View holder for one row of the entry tree. Groups show a chevron, a folder
 * and the number of entries beneath them; records show their icon in a tonal
 * circle and, when USB auto-type is enabled, a keyboard button that types
 * the credentials on a long press.
 */
public class PasswdRecordListItemHolder
        extends TreeNode.BaseNodeViewHolder<PasswdRecordListItemHolder.IconTreeItem> {

    private IconicsTextView arrowView;
    private View.OnLongClickListener iconOnClickListener;

    public static class IconTreeItem {
        public int level;
        public int group_count;
        public String icon;
        public String text;
        public String uuid;
        public PasswdLocation location;

        public IconTreeItem(int level, String icon, String text, String uuid, PasswdLocation location) {
            this.level = level;
            this.icon = icon;
            this.text = text;
            this.uuid = uuid;
            this.location = location;
        }

        public void setGroupCount(int group_count) {
            this.group_count = group_count;
        }
    }

    public PasswdRecordListItemHolder(Context context) {
        super(context);
    }

    @Override
    public View createNodeView(final TreeNode node, IconTreeItem itemValues) {
        final LayoutInflater inflater = LayoutInflater.from(context);
        final View view = inflater.inflate(R.layout.passwdsafe_list_tree_item, null, false);
        // Inflated without a parent, so the row would otherwise be added to
        // the tree's node container as wrap_content and hug its text.
        view.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // indent the entries in the layout in regard to their level
        final float scale = context.getResources().getDisplayMetrics().density;
        final int spaceWidth = (int) (itemValues.level * 24.0f * scale + 0.5f);
        final View spacer = view.findViewById(R.id.spacer);
        ViewGroup.LayoutParams params = spacer.getLayoutParams();
        params.width = spaceWidth;
        spacer.setLayoutParams(params);

        // set the item text
        final TextView tvItemName = view.findViewById(R.id.item_name);
        tvItemName.setText(itemValues.text);

        final View iconContainer = view.findViewById(R.id.icon_container);
        final IconicsTextView iconItem = view.findViewById(R.id.icon);
        final View iconUsbkbdOutput = view.findViewById(R.id.icon_usbkbd_output);
        final TextView tvGroupCount = view.findViewById(R.id.group_count);

        arrowView = view.findViewById(R.id.arrow_icon);

        if (node.isLeaf()) {
            // a record: its own icon (or a key) in the tonal circle
            if (itemValues.icon != null) {
                iconItem.setText("{" + itemValues.icon + "}");
            } else {
                iconItem.setText(context.getString(R.string.ic_key));
            }
        } else {
            // a group: chevron, plain folder and the count of entries below
            iconContainer.setBackground(null);
            iconItem.setText(context.getString(R.string.ic_folder));
            iconItem.setTextColor(MaterialColors.getColor(
                    iconItem, com.google.android.material.R.attr.colorOnSurfaceVariant));
            iconItem.setTextSize(22);

            arrowView.setVisibility(View.VISIBLE);
            iconUsbkbdOutput.setVisibility(View.GONE);

            tvGroupCount.setVisibility(View.VISIBLE);
            tvGroupCount.setText(String.valueOf(itemValues.group_count));
            tvGroupCount.setContentDescription(
                    context.getResources().getQuantityString(
                            R.plurals.group_entries, itemValues.group_count,
                            itemValues.group_count));
        }

        // hide the USB Keyboard Output button if the functionality is disabled
        SharedPreferences prefs = Preferences.getSharedPrefs(context);
        if (!Preferences.getAutoTypeUsbEnabled(prefs)) {
            iconUsbkbdOutput.setVisibility(View.GONE);
        }

        iconUsbkbdOutput.setOnLongClickListener(iconOnClickListener);
        iconUsbkbdOutput.setTooltipText(
                context.getString(R.string.autotype_usb_hold_hint));

        return view;
    }

    @Override
    public void toggle(boolean active) {
        arrowView.setText(context.getString(active ? R.string.ic_arrow_down : R.string.ic_arrow_right));
    }

    public void setIcon2ViewOnClickListener(View.OnLongClickListener onClickListener) {
        iconOnClickListener = onClickListener;
    }

}
