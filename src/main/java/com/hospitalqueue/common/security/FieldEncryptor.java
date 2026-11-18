package com.hospitalqueue.common.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts clinical text (consultation notes, prescriptions) before it is written to the database, and decrypts it
 * when it is read, so a copy of the database alone reveals nothing.
 *
 * <p>Algorithm: AES-256 in GCM mode, which also detects any tampering. Every value gets its own random 12-byte IV, so
 * the same text never produces the same stored value. A stored value looks like {@code v1:<Base64 of IV + ciphertext>};
 * the {@code v1} prefix lets a future key rotation tell old and new values apart.
 *
 * <p>The key comes from {@code app.security.field-encryption-key} (Base64, exactly 32 bytes). In production it must come
 * from a secret manager. Nothing in this class ever puts plaintext or key material in an exception message or a log line.
 */
@Component
public class FieldEncryptor {

    /** The development key shipped in application.yaml. Using it in production is a security hole. */
    static final String DEVELOPMENT_KEY = "aG9zcGl0YWwtcXVldWUtREVWLU9OTFkta2V5LTAwMDA=";

    private static final Logger log = LoggerFactory.getLogger(FieldEncryptor.class);

    private static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param base64Key the AES-256 key, Base64-encoded, exactly 32 bytes
     * @throws IllegalStateException when the key is not valid Base64 or not 32 bytes long
     */
    public FieldEncryptor(@Value("${app.security.field-encryption-key}") String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("app.security.field-encryption-key is not valid Base64");
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "app.security.field-encryption-key must be exactly 32 bytes (256 bits) encoded in Base64");
        }
        this.key = new SecretKeySpec(raw, "AES");
        if (DEVELOPMENT_KEY.equals(base64Key.trim())) {
            log.warn("The DEVELOPMENT field-encryption key is in use. Set FIELD_ENCRYPTION_KEY in every real deployment.");
        }
    }

    /**
     * Encrypts a value for storage.
     *
     * @param plaintext the text, or null
     * @return the stored form, or null when the input is null
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_BYTES + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, IV_BYTES);
            System.arraycopy(encrypted, 0, combined, IV_BYTES, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Could not encrypt a value");
        }
    }

    /**
     * Decrypts a stored value.
     *
     * @param stored the stored form, or null
     * @return the original text, or null when the input is null
     * @throws IllegalStateException when the value is not in the expected format, was tampered with, or was encrypted
     *                               with a different key
     */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            throw new IllegalStateException("A stored value is not in the expected encrypted format");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (combined.length <= IV_BYTES) {
                throw new IllegalStateException("A stored value is too short to be valid");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, combined, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IllegalStateException("Could not decrypt a stored value (wrong key or corrupted data)");
        }
    }
}
