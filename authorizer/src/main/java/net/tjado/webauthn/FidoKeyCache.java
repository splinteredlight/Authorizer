/*
 * Copyright (©) 2026 Authorizer contributors
 * All rights reserved. Use of the code is allowed under the
 * GNU General Public License v3.0
 */
package net.tjado.webauthn;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.tjado.authorizer.Utilities;
import net.tjado.passwdsafe.FidoFileAccess;
import net.tjado.passwdsafe.Preferences;
import net.tjado.passwdsafe.file.PasswdFileData;
import net.tjado.webauthn.models.PublicKeyCredentialSource;

import org.pwsafe.lib.file.PwsRecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cache of the FIDO credentials from the last opened psafe3 file, kept so
 * the Bluetooth service can sign assertions after the file has been closed
 * (screen off, timeout, app swiped away, process restarted).
 *
 * <p>Scope: only the FIDO records, never passwords. The psafe3 file stays
 * the source of truth and the only backup; the cache is rebuilt from it on
 * every open and can be dropped at any time.</p>
 *
 * <p>At rest the cache is one file in app-private storage, AES-256-GCM
 * under an AndroidKeyStore key (StrongBox when available, TEE otherwise)
 * that needs no user authentication, so it is usable after one device
 * unlock per boot. In memory the private keys are plaintext for as long
 * as the process lives, which is the trade-off this feature exists for.</p>
 *
 * <p>Sign counters advance here while the file is closed. The backend
 * takes the larger of the file's and the cache's counter on the next
 * foreground use, so the value the relying party sees never goes down.</p>
 */
public final class FidoKeyCache
{
    private static final String TAG = "FidoKeyCache";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "fidoKeyCache";
    private static final String FILE_NAME = "fido_key_cache.bin";
    private static final int FORMAT_VERSION = 1;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    /** One cached credential in its storable form */
    private static final class Entry
    {
        final String id;
        final String rpId;
        final String rpName;
        final String u2fRpId;
        final String userHandleHex;
        final String userName;
        final String userDisplayName;
        final String keyPair;      // "base64(X509 public):base64(PKCS8 private)"
        final String hmacSecret;   // base64 or null
        int counter;

        Entry(String id, String rpId, String rpName, String u2fRpId,
              String userHandleHex, String userName, String userDisplayName,
              String keyPair, String hmacSecret, int counter)
        {
            this.id = id;
            this.rpId = rpId;
            this.rpName = rpName;
            this.u2fRpId = u2fRpId;
            this.userHandleHex = userHandleHex;
            this.userName = userName;
            this.userDisplayName = userDisplayName;
            this.keyPair = keyPair;
            this.hmacSecret = hmacSecret;
            this.counter = counter;
        }
    }

    private final Context itsContext;
    private final File itsFile;
    /** Keyed by record UUID; null until loaded from disk or refreshed */
    private Map<String, Entry> itsEntries = null;

    public FidoKeyCache(@NonNull Context ctx)
    {
        itsContext = ctx.getApplicationContext();
        itsFile = new File(itsContext.getFilesDir(), FILE_NAME);
    }

    /** Whether the user turned background answering on */
    public boolean isEnabled()
    {
        return Preferences.getFidoBackgroundAnswer(
                Preferences.getSharedPrefs(itsContext));
    }

    /** Whether the cache can answer a request right now */
    public synchronized boolean canServe()
    {
        if (!isEnabled()) {
            return false;
        }
        ensureLoaded();
        return itsEntries != null && !itsEntries.isEmpty();
    }

    /**
     * Rebuild the cache from the open file. Safe to call from any thread;
     * a no-op when the feature is off or no file is open.
     */
    public void refreshFromFile(@Nullable FidoFileAccess file)
    {
        if (!isEnabled() || file == null || !file.isFileOpen()) {
            return;
        }
        Map<String, Entry> fresh = new LinkedHashMap<>();
        Boolean ok = file.useFileData(fileData -> {
            for (PwsRecord rec : fileData.getRecords()) {
                Entry e = fromRecord(fileData, rec);
                if (e != null) {
                    fresh.put(e.id, e);
                }
            }
            return Boolean.TRUE;
        });
        if (ok == null) {
            return;
        }
        synchronized (this) {
            ensureLoaded();
            // Keep a counter that advanced while the file was closed, so
            // the next signature still goes up.
            if (itsEntries != null) {
                for (Entry e : fresh.values()) {
                    Entry old = itsEntries.get(e.id);
                    if (old != null && old.counter > e.counter) {
                        e.counter = old.counter;
                    }
                }
            }
            itsEntries = fresh;
            persist();
        }
        Log.i(TAG, "refreshed " + fresh.size() + " credential(s)");
    }

    /** Add or replace one credential (after a registration) */
    public synchronized void put(@NonNull PublicKeyCredentialSource cred)
    {
        if (!isEnabled()) {
            return;
        }
        ensureLoaded();
        if (itsEntries == null) {
            itsEntries = new LinkedHashMap<>();
        }
        Entry e = fromCredential(cred);
        itsEntries.put(e.id, e);
        persist();
    }

    public synchronized List<PublicKeyCredentialSource> getAll()
    {
        ensureLoaded();
        List<PublicKeyCredentialSource> out = new ArrayList<>();
        if (itsEntries != null) {
            for (Entry e : itsEntries.values()) {
                PublicKeyCredentialSource c = toCredential(e);
                if (c != null) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    public synchronized List<PublicKeyCredentialSource> getForRp(@NonNull String rpId)
    {
        ensureLoaded();
        List<PublicKeyCredentialSource> out = new ArrayList<>();
        if (itsEntries != null) {
            for (Entry e : itsEntries.values()) {
                if (rpId.equals(e.rpId)) {
                    PublicKeyCredentialSource c = toCredential(e);
                    if (c != null) {
                        out.add(c);
                    }
                }
            }
        }
        return out;
    }

    public synchronized @Nullable PublicKeyCredentialSource getById(@NonNull byte[] id)
    {
        ensureLoaded();
        if (itsEntries == null) {
            return null;
        }
        Entry e = itsEntries.get(new String(id, StandardCharsets.UTF_8));
        return (e != null) ? toCredential(e) : null;
    }

    /** Cached sign counter, or -1 when the credential is not cached */
    public synchronized int getCounter(@NonNull byte[] id)
    {
        ensureLoaded();
        if (itsEntries == null) {
            return -1;
        }
        Entry e = itsEntries.get(new String(id, StandardCharsets.UTF_8));
        return (e != null) ? e.counter : -1;
    }

    /** Store a sign counter (only ever raises it) */
    public synchronized void setCounter(@NonNull byte[] id, int counter)
    {
        ensureLoaded();
        if (itsEntries == null) {
            return;
        }
        Entry e = itsEntries.get(new String(id, StandardCharsets.UTF_8));
        if (e != null && counter > e.counter) {
            e.counter = counter;
            persist();
        }
    }

    /** Forget everything, in memory and on disk */
    public synchronized void clear()
    {
        itsEntries = new LinkedHashMap<>();
        if (itsFile.exists() && !itsFile.delete()) {
            Log.w(TAG, "could not delete cache file");
        }
    }

    // ---- record / credential conversion -------------------------------

    private static @Nullable Entry fromRecord(PasswdFileData fileData, PwsRecord rec)
    {
        String rpId = fileData.getFidoRpId(rec);
        String keyPair = fileData.getFidoKeyPair(rec);
        if (rpId == null || keyPair == null) {
            return null;
        }
        Integer counter = fileData.getFidoKeyUseCounter(rec);
        return new Entry(fileData.getUUID(rec),
                         rpId,
                         fileData.getFidoRpName(rec),
                         fileData.getFidoU2fRpId(rec),
                         fileData.getFidoUserHandle(rec),
                         fileData.getFidoUserName(rec),
                         fileData.getFidoUserDisplayName(rec),
                         keyPair,
                         fileData.getFidoHmacSecret(rec),
                         (counter != null) ? counter : 0);
    }

    private static Entry fromCredential(PublicKeyCredentialSource c)
    {
        String pub = Base64.encodeToString(c.keyPair.getPublic().getEncoded(), Base64.DEFAULT);
        String priv = Base64.encodeToString(c.keyPair.getPrivate().getEncoded(), Base64.DEFAULT);
        String hmac = (c.hmacSecret != null) ?
                Base64.encodeToString(c.hmacSecret.getEncoded(), Base64.DEFAULT) : null;
        return new Entry(new String(c.id, StandardCharsets.UTF_8),
                         c.rpId, c.rpName, c.u2fRpId,
                         (c.userHandle != null) ? Utilities.bytesToHex(c.userHandle) : null,
                         c.userName, c.userDisplayName,
                         pub + ":" + priv, hmac, c.keyUseCounter);
    }

    private static @Nullable PublicKeyCredentialSource toCredential(Entry e)
    {
        try {
            String[] keys = e.keyPair.split(":");
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey pub = kf.generatePublic(
                    new X509EncodedKeySpec(Base64.decode(keys[0], Base64.DEFAULT)));
            PrivateKey priv = kf.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.decode(keys[1], Base64.DEFAULT)));
            SecretKey hmac = (e.hmacSecret != null) ?
                    new SecretKeySpec(Base64.decode(e.hmacSecret, Base64.DEFAULT), "HmacSHA256") :
                    null;
            return new PublicKeyCredentialSource(
                    e.id.getBytes(StandardCharsets.UTF_8),
                    e.rpId, e.rpName,
                    (e.userHandleHex != null) ? Utilities.hexStringToByteArray(e.userHandleHex) : null,
                    e.userName, e.userDisplayName,
                    false, e.u2fRpId,
                    new KeyPair(pub, priv), e.counter, hmac);
        } catch (GeneralSecurityException | RuntimeException ex) {
            Log.w(TAG, "cached credential unreadable, skipping");
            return null;
        }
    }

    // ---- persistence ---------------------------------------------------

    /** Must hold the monitor */
    private void ensureLoaded()
    {
        if (itsEntries != null) {
            return;
        }
        itsEntries = new LinkedHashMap<>();
        if (!itsFile.exists()) {
            return;
        }
        try {
            byte[] blob = readFully(itsFile);
            if (blob.length <= GCM_IV_BYTES) {
                throw new IOException("short cache file");
            }
            SecretKey key = getKey(false);
            if (key == null) {
                throw new IOException("cache key missing");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                        new GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES));
            byte[] plain = cipher.doFinal(blob, GCM_IV_BYTES, blob.length - GCM_IV_BYTES);
            itsEntries = deserialize(plain);
            Arrays.fill(plain, (byte)0);
        } catch (IOException | GeneralSecurityException e) {
            // Unreadable (key rotated, file corrupt): drop it, the next file
            // open rebuilds it.
            Log.w(TAG, "cache unreadable, discarding: " + e.getClass().getSimpleName());
            clear();
        }
    }

    /** Must hold the monitor */
    private void persist()
    {
        if (itsEntries == null) {
            return;
        }
        byte[] plain = null;
        try {
            SecretKey key = getKey(true);
            if (key == null) {
                throw new IOException("no cache key");
            }
            plain = serialize(itsEntries);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != GCM_IV_BYTES) {
                throw new IOException("unexpected GCM IV");
            }
            byte[] enc = cipher.doFinal(plain);
            File tmp = new File(itsFile.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(iv);
                out.write(enc);
                out.getFD().sync();
            }
            if (!tmp.renameTo(itsFile)) {
                throw new IOException("rename failed");
            }
        } catch (IOException | GeneralSecurityException e) {
            Log.w(TAG, "could not persist cache: " + e.getClass().getSimpleName());
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte)0);
            }
        }
    }

    private static byte[] serialize(Map<String, Entry> entries) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(FORMAT_VERSION);
        out.writeInt(entries.size());
        for (Entry e : entries.values()) {
            writeStr(out, e.id);
            writeStr(out, e.rpId);
            writeStr(out, e.rpName);
            writeStr(out, e.u2fRpId);
            writeStr(out, e.userHandleHex);
            writeStr(out, e.userName);
            writeStr(out, e.userDisplayName);
            writeStr(out, e.keyPair);
            writeStr(out, e.hmacSecret);
            out.writeInt(e.counter);
        }
        out.flush();
        return bytes.toByteArray();
    }

    private static Map<String, Entry> deserialize(byte[] plain) throws IOException
    {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain));
        int version = in.readInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("unknown cache version " + version);
        }
        int count = in.readInt();
        if (count < 0 || count > 10000) {
            throw new IOException("bad entry count");
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (int i = 0; i < count; ++i) {
            String id = readStr(in);
            String rpId = readStr(in);
            String rpName = readStr(in);
            String u2fRpId = readStr(in);
            String userHandleHex = readStr(in);
            String userName = readStr(in);
            String userDisplayName = readStr(in);
            String keyPair = readStr(in);
            String hmacSecret = readStr(in);
            int counter = in.readInt();
            if (id == null || rpId == null || keyPair == null) {
                throw new IOException("bad entry");
            }
            entries.put(id, new Entry(id, rpId, rpName, u2fRpId, userHandleHex,
                                      userName, userDisplayName, keyPair,
                                      hmacSecret, counter));
        }
        return entries;
    }

    private static void writeStr(DataOutputStream out, @Nullable String s) throws IOException
    {
        out.writeBoolean(s != null);
        if (s != null) {
            out.writeUTF(s);
        }
    }

    private static @Nullable String readStr(DataInputStream in) throws IOException
    {
        return in.readBoolean() ? in.readUTF() : null;
    }

    private static byte[] readFully(File f) throws IOException
    {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * The keystore key that wraps the cache. Created on first use, in
     * StrongBox when the device has one. No user authentication on the key:
     * the whole point is signing with the phone in a pocket.
     */
    private static @Nullable SecretKey getKey(boolean create) throws GeneralSecurityException, IOException
    {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry)entry).getSecretKey();
        }
        if (!create) {
            return null;
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                gen.init(newKeySpec().setIsStrongBoxBacked(true).build());
                return gen.generateKey();
            } catch (StrongBoxUnavailableException e) {
                Log.i(TAG, "StrongBox unavailable, using TEE");
            }
        }
        gen.init(newKeySpec().build());
        return gen.generateKey();
    }

    private static KeyGenParameterSpec.Builder newKeySpec()
    {
        return new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false);
    }
}
