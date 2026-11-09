/**
 * API 密钥安全存储
 *
 * 使用 Android Keystore 的 AES/GCM 加密保存 AI 接口密钥，不以明文写入 SharedPreferences。
 */
package com.hawson.timeassistant.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Legacy single-key store. Keep its alias stable during connection-profile migration.
 *
 * <p>The API key is never written in plaintext. The dedicated SharedPreferences file contains
 * only authenticated ciphertext, its non-secret IV, and a format version.</p>
 */
public final class SecureApiKeyStore {
    public static final String PREFERENCES_NAME = "time_assistant_secrets";

    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "time_assistant_api_key_aes_v1";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String PREF_CIPHERTEXT = "api_key_ciphertext";
    private static final String PREF_IV = "api_key_iv";
    private static final String PREF_VERSION = "api_key_format_version";
    private static final int FORMAT_VERSION = 1;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final int MIN_GCM_CIPHERTEXT_BYTES = GCM_TAG_BITS / 8;

    private final SharedPreferences preferences;

    public SecureApiKeyStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context 不能为空");
        Context applicationContext = context.getApplicationContext();
        preferences = (applicationContext == null ? context : applicationContext)
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** Encrypts and synchronously persists a non-empty API key. */
    public synchronized void save(String apiKey) throws SecureStorageException {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new SecureStorageException(Reason.INVALID_INPUT, "API Key 不能为空");
        }

        byte[] plaintext = apiKey.getBytes(StandardCharsets.UTF_8);
        try {
            EncryptedValue encrypted;
            try {
                encrypted = encrypt(getOrCreateKey(), plaintext);
            } catch (KeyPermanentlyInvalidatedException invalidated) {
                // The new plaintext is available, so replace an unusable key and encrypt afresh.
                CleanupResult cleanup = cleanupDamagedState(true);
                if (!cleanup.success) {
                    throw cleanupFailure("Keystore 密钥已失效，且旧加密数据清理失败", invalidated,
                            cleanup.error);
                }
                encrypted = encrypt(generateKey(), plaintext);
            }

            boolean committed = preferences.edit()
                    .putInt(PREF_VERSION, FORMAT_VERSION)
                    .putString(PREF_CIPHERTEXT,
                            Base64.encodeToString(encrypted.ciphertext, Base64.NO_WRAP))
                    .putString(PREF_IV, Base64.encodeToString(encrypted.iv, Base64.NO_WRAP))
                    .commit();
            if (!committed) {
                throw new SecureStorageException(Reason.PERSISTENCE_FAILED,
                        "API Key 已加密，但无法保存到本地存储");
            }
        } catch (SecureStorageException error) {
            throw error;
        } catch (GeneralSecurityException error) {
            throw new SecureStorageException(Reason.KEYSTORE_UNAVAILABLE,
                    "无法使用 Android Keystore 加密 API Key", error);
        } catch (RuntimeException error) {
            throw new SecureStorageException(Reason.PERSISTENCE_FAILED,
                    "无法保存加密后的 API Key", error);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * Returns the decrypted API key, or {@code null} when none is saved.
     * Corrupt or undecryptable data is removed before an explicit exception is returned.
     */
    public synchronized String load() throws SecureStorageException {
        StoredValue stored = readStoredValue();
        if (stored == null) return null;

        final byte[] iv;
        final byte[] ciphertext;
        try {
            iv = Base64.decode(stored.ivBase64, Base64.NO_WRAP);
            ciphertext = Base64.decode(stored.ciphertextBase64, Base64.NO_WRAP);
        } catch (IllegalArgumentException error) {
            throw damaged(Reason.DATA_CORRUPTED,
                    "已保存的 API Key 数据编码损坏，已安全清理", error, false);
        }
        if (iv.length != GCM_IV_BYTES || ciphertext.length < MIN_GCM_CIPHERTEXT_BYTES) {
            throw damaged(Reason.DATA_CORRUPTED,
                    "已保存的 API Key 加密数据不完整，已安全清理", null, false);
        }

        final SecretKey secretKey;
        try {
            secretKey = getExistingKey();
        } catch (GeneralSecurityException | RuntimeException error) {
            throw new SecureStorageException(Reason.KEYSTORE_UNAVAILABLE,
                    "暂时无法访问 Android Keystore", error);
        }
        if (secretKey == null) {
            throw damaged(Reason.KEY_INVALIDATED,
                    "用于解密 API Key 的设备密钥已丢失，旧数据已安全清理", null, false);
        }

        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            plaintext = cipher.doFinal(ciphertext);
            if (plaintext.length == 0) {
                throw damaged(Reason.DATA_CORRUPTED,
                        "解密后的 API Key 为空，旧数据已安全清理", null, false);
            }
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (KeyPermanentlyInvalidatedException error) {
            throw damaged(Reason.KEY_INVALIDATED,
                    "用于解密 API Key 的设备密钥已失效，旧数据已安全清理", error, true);
        } catch (BadPaddingException error) {
            throw damaged(Reason.DATA_CORRUPTED,
                    "API Key 密文认证失败，损坏数据已安全清理", error, true);
        } catch (SecureStorageException error) {
            throw error;
        } catch (GeneralSecurityException | RuntimeException error) {
            throw damaged(Reason.DATA_CORRUPTED,
                    "无法解密已保存的 API Key，损坏数据已安全清理", error, true);
        } finally {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** Deletes both encrypted preferences and the corresponding Keystore entry. */
    public synchronized void clear() throws SecureStorageException {
        CleanupResult cleanup = cleanupDamagedState(true);
        if (!cleanup.success) {
            throw cleanupFailure("无法完全清除已保存的 API Key", null, cleanup.error);
        }
    }

    /**
     * Returns whether a complete encrypted value is present. Partial/corrupt metadata is cleared
     * and reported instead of being treated as a usable value.
     */
    public synchronized boolean hasValue() throws SecureStorageException {
        return readStoredValue() != null;
    }

    private StoredValue readStoredValue() throws SecureStorageException {
        boolean hasCiphertext = preferences.contains(PREF_CIPHERTEXT);
        boolean hasIv = preferences.contains(PREF_IV);
        boolean hasVersion = preferences.contains(PREF_VERSION);
        if (!hasCiphertext && !hasIv && !hasVersion) return null;
        if (!hasCiphertext || !hasIv || !hasVersion) {
            throw damaged(Reason.DATA_CORRUPTED,
                    "已保存的 API Key 数据不完整，已安全清理", null, false);
        }

        int version = preferences.getInt(PREF_VERSION, -1);
        if (version != FORMAT_VERSION) {
            throw damaged(Reason.DATA_CORRUPTED,
                    "已保存的 API Key 格式不受支持，旧数据已安全清理", null, false);
        }
        String ciphertext = preferences.getString(PREF_CIPHERTEXT, null);
        String iv = preferences.getString(PREF_IV, null);
        if (ciphertext == null || ciphertext.isEmpty() || iv == null || iv.isEmpty()) {
            throw damaged(Reason.DATA_CORRUPTED,
                    "已保存的 API Key 数据为空，已安全清理", null, false);
        }
        return new StoredValue(ciphertext, iv);
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        SecretKey existing = getExistingKey();
        return existing == null ? generateKey() : existing;
    }

    private SecretKey getExistingKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        try {
            keyStore.load(null);
        } catch (IOException error) {
            throw new GeneralSecurityException("无法加载 Android Keystore", error);
        }
        Key key = keyStore.getKey(KEY_ALIAS, null);
        if (key == null) return null;
        if (!(key instanceof SecretKey)) {
            throw new GeneralSecurityException("Keystore alias 类型不正确");
        }
        return (SecretKey) key;
    }

    private SecretKey generateKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static EncryptedValue encrypt(SecretKey secretKey, byte[] plaintext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] ciphertext = cipher.doFinal(plaintext);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != GCM_IV_BYTES) {
            Arrays.fill(ciphertext, (byte) 0);
            throw new GeneralSecurityException("Keystore 返回了无效 GCM IV");
        }
        return new EncryptedValue(ciphertext, iv);
    }

    private SecureStorageException damaged(Reason reason, String message, Throwable cause,
                                           boolean deleteKey) {
        CleanupResult cleanup = cleanupDamagedState(deleteKey);
        SecureStorageException error = new SecureStorageException(reason,
                cleanup.success ? message : message + "，但本地清理未完全成功", cause);
        if (cleanup.error != null) error.addSuppressed(cleanup.error);
        return error;
    }

    private CleanupResult cleanupDamagedState(boolean deleteKey) {
        Throwable failure = null;
        if (deleteKey) {
            try {
                KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
                keyStore.load(null);
                if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
            } catch (GeneralSecurityException | IOException | RuntimeException error) {
                failure = error;
            }
        }

        try {
            if (!preferences.edit().clear().commit()) {
                // Also clear the in-memory view and make one asynchronous disk retry.
                preferences.edit().clear().apply();
                if (failure == null) failure = new IllegalStateException("SharedPreferences 清理失败");
            }
        } catch (RuntimeException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        return new CleanupResult(failure == null, failure);
    }

    private static SecureStorageException cleanupFailure(String message, Throwable cause,
                                                         Throwable cleanupError) {
        SecureStorageException error = new SecureStorageException(
                Reason.PERSISTENCE_FAILED, message, cause);
        if (cleanupError != null) error.addSuppressed(cleanupError);
        return error;
    }

    public enum Reason {
        INVALID_INPUT,
        KEYSTORE_UNAVAILABLE,
        KEY_INVALIDATED,
        DATA_CORRUPTED,
        PERSISTENCE_FAILED
    }

    /** Checked exception with a stable reason code and a user-displayable, key-free message. */
    public static final class SecureStorageException extends Exception {
        public final Reason reason;

        SecureStorageException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        SecureStorageException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
    }

    private static final class StoredValue {
        final String ciphertextBase64;
        final String ivBase64;

        StoredValue(String ciphertextBase64, String ivBase64) {
            this.ciphertextBase64 = ciphertextBase64;
            this.ivBase64 = ivBase64;
        }
    }

    private static final class EncryptedValue {
        final byte[] ciphertext;
        final byte[] iv;

        EncryptedValue(byte[] ciphertext, byte[] iv) {
            this.ciphertext = ciphertext;
            this.iv = iv;
        }
    }

    private static final class CleanupResult {
        final boolean success;
        final Throwable error;

        CleanupResult(boolean success, Throwable error) {
            this.success = success;
            this.error = error;
        }
    }
}
