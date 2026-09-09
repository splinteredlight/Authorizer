package net.tjado.webauthn.util;

import android.content.Context;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyGenParameterSpec;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.Mac;
import java.security.GeneralSecurityException;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;

import net.tjado.passwdsafe.lib.Utils;
import net.tjado.webauthn.exceptions.VirgilException;

@RequiresApi(api = Build.VERSION_CODES.O)
public class ClientPinLocker {
    private static final String TAG = "ClientPINEntry";

    private static final String KEYSTORE_TYPE = "AndroidKeyStore";
    private static final String CLIENT_PIN_TYPE = "Android_ePIN_";

    private static final String CLIENT_PIN_FIELD_PIN_RETRIES = "RETRIES";
    private static final int CLIENT_PIN_FIELD_PIN_RETRIES_VALUE = 0;

    private static final String CLIENT_PIN_FIELD_PIN_TOKEN = "TOKEN";
    private static final String CLIENT_PIN_FIELD_PIN_TOKEN_VALUE = "";

    private static final String CLIENT_PIN_FIELD_PIN_SHA256 = "PIN-SHA256";
    private static final String CLIENT_PIN_FIELD_PIN_SHA256_VALUE = "";

    private static final int PIN_TOKEN_LENGTH = 16;
    /** Prefs field holding HMAC-SHA256(pinHash) under a Keystore key */
    private static final String CLIENT_PIN_FIELD_PIN_HMAC = "PIN-HMAC";
    private static final String PIN_HMAC_ALIAS_SUFFIX = "_pinhmac";

    private SharedPreferences locker;
    private String clientId;
    private String cpkAlias;
    private String lockerId;

    public ClientPinLocker(Context ctx, @NonNull byte[] clientData) throws VirgilException {
        this(ctx, clientData, true);
    }

    public ClientPinLocker(Context ctx,
                    @NonNull byte[] clientData,
                    boolean strongboxRequired) throws VirgilException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new VirgilException("Failed to hash data", e);
        }
        md.update(clientData);
        this.clientId = Utils.bytesToHexString(md.digest());
        this.cpkAlias = "cpk" + this.clientId;
        this.lockerId = CLIENT_PIN_TYPE + this.clientId;

        locker = ctx.getSharedPreferences("webauthn", 0);

        refreshToken();
    }

    public ClientPinLocker setRetries(long retries) {
        if (retries < 0 || retries > 8) return this;
        locker.edit()
                .putLong(CLIENT_PIN_FIELD_PIN_RETRIES, retries)
                .apply();
        return this;
    }

    public ClientPinLocker setToken(@NonNull byte[] token) {
        if (token.length % 16 != 0) return this;

        String tokenString = Utils.bytesToHexString(token);
        locker.edit()
                .putString(CLIENT_PIN_FIELD_PIN_TOKEN, tokenString)
                .apply();
        return this;
    }

    /**
     * Store the PIN reference. The CTAP client sends SHA-256(PIN) truncated
     * to 16 bytes, which for a short numeric PIN is trivially brute-forced
     * offline if stored as-is. It is therefore stored as HMAC-SHA256 under a
     * non-exportable AndroidKeyStore key, so the reference is useless
     * without this device's keystore.
     */
    public boolean lockPin(@NonNull byte[] pin) {
        try {
            refreshToken();
        } catch (VirgilException e) {
            Log.w(TAG, "Failed to refresh token on this occasion!");
        }
        byte[] mac;
        try {
            mac = pinHmac(pin);
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Cannot compute PIN HMAC", e);
            return false;
        }
        return locker.edit()
                .putString(CLIENT_PIN_FIELD_PIN_HMAC, Utils.bytesToHexString(mac))
                .remove(CLIENT_PIN_FIELD_PIN_SHA256)
                .commit();
    }

    public boolean isPinMatch(@NonNull byte[] pinTry) {
        String macReference = locker.getString(CLIENT_PIN_FIELD_PIN_HMAC, "");
        if (!macReference.isEmpty()) {
            try {
                byte[] expected = hexStringToBytes(macReference);
                return (expected != null) &&
                       MessageDigest.isEqual(expected, pinHmac(pinTry));
            } catch (GeneralSecurityException | IOException e) {
                Log.e(TAG, "Cannot compute PIN HMAC", e);
                return false;
            }
        }
        // Legacy plain SHA-256 reference (pre-0.6.0): verify once, then upgrade
        String pinReference = locker.getString(CLIENT_PIN_FIELD_PIN_SHA256,
                CLIENT_PIN_FIELD_PIN_SHA256_VALUE);
        if (pinReference.equals(CLIENT_PIN_FIELD_PIN_SHA256_VALUE)) return false;
        byte[] legacy = hexStringToBytes(pinReference);
        if ((legacy != null) && MessageDigest.isEqual(legacy, pinTry)) {
            lockPin(pinTry);
            return true;
        }
        return false;
    }

    public boolean isPinSet() {
        return !locker.getString(CLIENT_PIN_FIELD_PIN_HMAC, "").isEmpty() ||
               !locker.getString(CLIENT_PIN_FIELD_PIN_SHA256,
                       CLIENT_PIN_FIELD_PIN_SHA256_VALUE)
                       .equals(CLIENT_PIN_FIELD_PIN_SHA256_VALUE);
    }

    public boolean resetPinLocker() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
            ks.load(null);
            ks.deleteEntry(pinHmacAlias());
        } catch (GeneralSecurityException | IOException e) {
            Log.w(TAG, "Cannot delete PIN HMAC key", e);
        }
        return locker.edit()
                .putLong(CLIENT_PIN_FIELD_PIN_RETRIES, CLIENT_PIN_FIELD_PIN_RETRIES_VALUE)
                .putString(CLIENT_PIN_FIELD_PIN_TOKEN, CLIENT_PIN_FIELD_PIN_TOKEN_VALUE)
                .remove(CLIENT_PIN_FIELD_PIN_SHA256)
                .remove(CLIENT_PIN_FIELD_PIN_HMAC)
                .commit();
    }

    private String pinHmacAlias() {
        return cpkAlias + PIN_HMAC_ALIAS_SUFFIX;
    }

    /** HMAC-SHA256 of the PIN hash under a Keystore key, created on demand */
    private byte[] pinHmac(@NonNull byte[] pin)
            throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        ks.load(null);
        SecretKey key = (SecretKey) ks.getKey(pinHmacAlias(), null);
        if (key == null) {
            KeyGenerator kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_TYPE);
            kg.init(new KeyGenParameterSpec.Builder(
                    pinHmacAlias(),
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .build());
            key = kg.generateKey();
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        return mac.doFinal(pin);
    }

    @NotNull
    @Override
    public String toString() {
        return this.clientId;
    }

    public Long getRetries() {
        return locker.getLong(CLIENT_PIN_FIELD_PIN_RETRIES, CLIENT_PIN_FIELD_PIN_RETRIES_VALUE);
    }

    public @Nullable byte[] getToken() {
        String tokenString = locker.getString(CLIENT_PIN_FIELD_PIN_TOKEN,
                CLIENT_PIN_FIELD_PIN_TOKEN_VALUE);

        if (!tokenString.equals(CLIENT_PIN_FIELD_PIN_TOKEN_VALUE)) {
            return hexStringToBytes(tokenString);
        } else {
            return null;
        }
    }

    public void deletePinLocker() throws VirgilException, KeyStoreException {
        /*
         * This is done by clearing all the fields, committing all the changes
         * and then removing the sharedPreferences files as per
         * https://stackoverflow.com/questions/6125296/delete-sharedpreferences-file
         * or
         * https://github.com/akaita/encryptedsharedpreferences-example/blob/master/app/src/main/java/com/akaita/encryptedsharedpreferences/MainActivity.kt#L123
         *
         * Also at the end removing the key from the keyStore if possible
         */
        locker.edit().clear().commit();
        this.getClass().getPackage();

        String lockerUri = getLockerUri();

        File locker = new File(lockerUri);
        if (locker.exists()) {
            try {
                locker.delete();
            } catch (SecurityException e) {
                throw new VirgilException("Couldn't delete locker", e);
            }
        }

        KeyStore keyStore;
        try {
            keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
            keyStore.load(null);
        } catch (KeyStoreException | CertificateException |
                NoSuchAlgorithmException | IOException e) {
            throw new VirgilException("Couldn't access keystore", e);
        }

        keyStore.deleteEntry(cpkAlias);
        keyStore.deleteEntry(pinHmacAlias());
    }

    public void decrementPinRetries() throws VirgilException {
        long retries = locker.getLong(CLIENT_PIN_FIELD_PIN_RETRIES, CLIENT_PIN_FIELD_PIN_RETRIES_VALUE);
        if (retries != 0) {
            retries--;
            locker.edit().putLong(CLIENT_PIN_FIELD_PIN_RETRIES, retries).commit();
        } else {
            throw new VirgilException("Client PIN error");
        }
    }

    public void refreshToken() throws VirgilException {
        try{
            byte[] pinToken = new byte[PIN_TOKEN_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(pinToken);
            this.setToken(pinToken);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            throw new VirgilException("Cannot produce pinToken bits", e);
        }
    }

    private String getLockerUri() {
        ApplicationInfo appInfo = new ApplicationInfo();
        return appInfo.dataDir + "/shared_prefs/" + lockerId + ".xml";
    }

    private @Nullable byte[] hexStringToBytes(String str) {
        int len = str.length();
        if (len == 0) return null;

        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(str.charAt(i), 16) << 4)
                    + Character.digit(str.charAt(i+1), 16));
        }
        return bytes;
    }
}
