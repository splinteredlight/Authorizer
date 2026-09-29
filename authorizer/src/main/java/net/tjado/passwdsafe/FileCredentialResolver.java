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
import android.text.TextUtils;

import androidx.annotation.NonNull;

import net.tjado.authorizer.KeyScript;
import net.tjado.passwdsafe.file.PasswdFileData;
import net.tjado.passwdsafe.file.PasswdNotes;
import net.tjado.passwdsafe.file.PasswdRecord;
import net.tjado.passwdsafe.otp.Token;

import org.pwsafe.lib.file.PwsRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves {@code {Group/Title.field}} references of a keyboard script
 * against the open password file.
 *
 * <p>The path is matched against each entry's "Group/Sub/Title" (nested
 * groups, stored with dots, are written with slashes), and a bare "Title"
 * matches the title alone. Matching ignores case, but must hit exactly one
 * entry: an ambiguous reference is an error, never a guess.
 *
 * <p>Call on the main thread from inside {@code useFileData}; the file data
 * is not thread safe.
 */
final class FileCredentialResolver implements KeyScript.CredentialResolver
{
    private final PasswdFileData itsFileData;
    private final Context itsContext;

    FileCredentialResolver(@NonNull PasswdFileData fileData,
                           @NonNull Context ctx)
    {
        itsFileData = fileData;
        itsContext = ctx;
    }

    @NonNull
    @Override
    public String resolve(@NonNull String path, @NonNull String field)
            throws KeyScript.ResolveException
    {
        PwsRecord rec = find(path);
        String value;
        switch (field) {
        case "user": {
            value = itsFileData.getUsername(rec);
            break;
        }
        case "password": {
            PasswdRecord passwdRec = itsFileData.getPasswdRecord(rec);
            value = (passwdRec != null) ? passwdRec.getPassword(itsFileData) :
                    itsFileData.getPassword(rec);
            break;
        }
        case "url": {
            value = itsFileData.getURL(rec, PasswdFileData.UrlStyle.URL_ONLY);
            break;
        }
        case "email": {
            value = itsFileData.getEmail(rec,
                                         PasswdFileData.EmailStyle.ADDR_ONLY);
            break;
        }
        case "notes": {
            PasswdNotes notes = itsFileData.getNotes(rec, itsContext);
            if (notes.isTruncated()) {
                throw new KeyScript.ResolveException(
                        "the notes are too long to type");
            }
            value = notes.getNotes();
            break;
        }
        case "title": {
            value = itsFileData.getTitle(rec);
            break;
        }
        case "otp": {
            value = otpCode(rec);
            break;
        }
        default: {
            throw new KeyScript.ResolveException("unknown field " + field);
        }
        }
        if (TextUtils.isEmpty(value)) {
            throw new KeyScript.ResolveException("the entry has no " + field);
        }
        return value;
    }

    /** The one entry the path names */
    @NonNull
    private PwsRecord find(String path) throws KeyScript.ResolveException
    {
        String want = normalize(path);
        boolean withGroup = want.contains("/");
        List<PwsRecord> matches = new ArrayList<>();
        ArrayList<String> groups = new ArrayList<>();
        for (PwsRecord rec : itsFileData.getRecords()) {
            String title = itsFileData.getTitle(rec);
            if (title == null) {
                continue;
            }
            String key;
            if (withGroup) {
                PasswdFileData.splitGroup(itsFileData.getGroup(rec), groups);
                StringBuilder sb = new StringBuilder();
                for (String g : groups) {
                    sb.append(g).append('/');
                }
                key = sb.append(title).toString();
            } else {
                key = title;
            }
            if (normalize(key).equals(want)) {
                matches.add(rec);
            }
        }
        if (matches.isEmpty()) {
            throw new KeyScript.ResolveException("no entry named " + path);
        }
        if (matches.size() > 1) {
            throw new KeyScript.ResolveException(
                    matches.size() + " entries are named " + path +
                    (withGroup ? "" : "; add the group, e.g. {Group/" +
                                      path + ".password}"));
        }
        return matches.get(0);
    }

    private static String normalize(String s)
    {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /** The current TOTP code; HOTP would need the counter saved */
    private String otpCode(PwsRecord rec) throws KeyScript.ResolveException
    {
        String otp = itsFileData.getOtp(rec);
        if (TextUtils.isEmpty(otp)) {
            throw new KeyScript.ResolveException("the entry has no OTP");
        }
        try {
            Token token = new Token(otp, false);
            if (token.getType() != Token.TokenType.TOTP) {
                throw new KeyScript.ResolveException(
                        "only time-based (TOTP) codes can be typed by a " +
                        "script");
            }
            return token.generateCodes().getCurrentCode();
        } catch (Token.TokenUriInvalidException e) {
            throw new KeyScript.ResolveException("the entry's OTP is invalid");
        }
    }
}
