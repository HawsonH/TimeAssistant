package com.hawson.timeassistant.data.connections;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.hawson.timeassistant.connections.AiCredentialsPort;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Independent from the legacy alias: clearing one profile never destroys another profile's key. */
public final class EncryptedCredentialStore implements AiCredentialsPort {
    public static final String FILE = "ai_connection_credentials";
    private static final String ALIAS = "time_assistant_ai_credentials_aes_v1";
    private final SharedPreferences prefs;
    public EncryptedCredentialStore(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
    @Override public synchronized String read(String reference) throws Exception {
        String saved = prefs.getString(reference, null);
        if (saved == null) return null;
        byte[] bytes = Base64.decode(saved, Base64.NO_WRAP);
        if (bytes.length < 28) throw new IllegalStateException("凭据密文不完整");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, bytes, 0, 12));
        cipher.updateAAD(reference.getBytes(StandardCharsets.UTF_8));
        byte[] plain = cipher.doFinal(bytes, 12, bytes.length - 12);
        try { return new String(plain, StandardCharsets.UTF_8); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    @Override public synchronized void write(String reference, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(reference.getBytes(StandardCharsets.UTF_8));
        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted;
        try { encrypted = cipher.doFinal(plain); }
        finally { Arrays.fill(plain, (byte) 0); }
        byte[] combined = new byte[12 + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, combined, 0, 12);
        System.arraycopy(encrypted, 0, combined, 12, encrypted.length);
        if (!prefs.edit().putString(reference, Base64.encodeToString(combined, Base64.NO_WRAP))
                .commit()) throw new IllegalStateException("无法保存加密凭据");
    }
    @Override public synchronized void remove(String reference) {
        if (!prefs.edit().remove(reference).commit()) throw new IllegalStateException("无法清除凭据");
    }
    @Override public synchronized Set<String> references() {
        return Collections.unmodifiableSet(new HashSet<>(prefs.getAll().keySet()));
    }
}
