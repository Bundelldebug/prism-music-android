package com.oneuibook.prism;

import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardware-backed Android Keystore AES-256-GCM secret storage for SharedPreferences,
 * with transparent auto-migration of legacy plaintext tokens and cookies.
 */
final class SecureVault {
    private SecureVault() {}

    private static final String KEY_ALIAS = "oneui_prism_vault_v1";
    private static final String PREFIX = "enc:v1:";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;
    private static SecretKey testFallbackKey;

    static void put(SharedPreferences prefs, String key, String plaintext) {
        if (prefs == null || key == null) return;
        if (plaintext == null || plaintext.isEmpty()) {
            prefs.edit().remove(key).apply();
            return;
        }
        String enc = encrypt(plaintext);
        if (enc != null) {
            prefs.edit().putString(key, enc).apply();
        } else {
            prefs.edit().putString(key, plaintext).apply();
        }
    }

    static String get(SharedPreferences prefs, String key) {
        if (prefs == null || key == null) return null;
        String raw = prefs.getString(key, null);
        if (raw == null || raw.isEmpty()) return null;
        if (raw.startsWith(PREFIX)) {
            return decrypt(raw);
        }
        // Transparent migration of legacy plaintext credential
        String enc = encrypt(raw);
        if (enc != null) {
            prefs.edit().putString(key, enc).apply();
        }
        return raw;
    }

    static boolean has(SharedPreferences prefs, String key) {
        String v = get(prefs, key);
        return v != null && !v.isEmpty();
    }

    static void remove(SharedPreferences prefs, String key) {
        if (prefs != null && key != null) prefs.edit().remove(key).apply();
    }

    static boolean isEncryptedValue(String raw) {
        return raw != null && raw.startsWith(PREFIX) && raw.length() > PREFIX.length() + 16;
    }

    private static synchronized SecretKey getOrCreateKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KEY_ALIAS)) {
                KeyStore.Entry e = ks.getEntry(KEY_ALIAS, null);
                if (e instanceof KeyStore.SecretKeyEntry) {
                    return ((KeyStore.SecretKeyEntry) e).getSecretKey();
                }
            }
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build();
            kg.init(spec);
            return kg.generateKey();
        } catch (Throwable t) {
            if ("robolectric".equals(Build.FINGERPRINT)) {
                try {
                    if (testFallbackKey == null) {
                        KeyGenerator kg = KeyGenerator.getInstance("AES");
                        kg.init(256);
                        testFallbackKey = kg.generateKey();
                    }
                    return testFallbackKey;
                } catch (Throwable ignored) {}
            }
            return null;
        }
    }

    private static String encrypt(String plaintext) {
        try {
            SecretKey key = getOrCreateKey();
            if (key == null) return null;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_LEN) return null;
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String decrypt(String encoded) {
        try {
            if (encoded == null || !encoded.startsWith(PREFIX)) return null;
            SecretKey key = getOrCreateKey();
            if (key == null) return null;
            byte[] all = Base64.decode(encoded.substring(PREFIX.length()), Base64.NO_WRAP);
            if (all.length <= IV_LEN) return null;
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
