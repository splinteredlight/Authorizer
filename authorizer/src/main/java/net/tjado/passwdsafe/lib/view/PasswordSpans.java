/*
 * Authorizer
 *
 * Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */
package net.tjado.passwdsafe.lib.view;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import net.tjado.passwdsafe.R;

/**
 * Colours a revealed password so digits and symbols can be told apart from
 * letters at a glance (0 vs O, 1 vs l). Letters keep the text colour; the
 * colours come from the theme (values/colors.xml, password_*).
 */
public final class PasswordSpans
{
    private PasswordSpans()
    {
    }

    /**
     * Build the coloured text for a password
     * @param password the clear password (never logged)
     * @param view a view whose context supplies the theme colours
     */
    @NonNull
    public static CharSequence colorize(@NonNull String password,
                                        @NonNull View view)
    {
        int digitColor = ContextCompat.getColor(view.getContext(),
                                                R.color.password_digit);
        int symbolColor = ContextCompat.getColor(view.getContext(),
                                                 R.color.password_symbol);
        SpannableString str = new SpannableString(password);
        int runStart = -1;
        int runColor = 0;
        int len = password.length();
        for (int i = 0; i <= len; ++i) {
            int color = 0;
            if (i < len) {
                char c = password.charAt(i);
                if (Character.isDigit(c)) {
                    color = digitColor;
                } else if (!Character.isLetter(c) &&
                           !Character.isWhitespace(c)) {
                    color = symbolColor;
                }
            }
            if (color != runColor) {
                if (runColor != 0) {
                    str.setSpan(new ForegroundColorSpan(runColor), runStart, i,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                runStart = i;
                runColor = color;
            }
        }
        return str;
    }
}
