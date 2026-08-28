package com.lokesh_codes.expense_tracker_backend.service.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.stereotype.Component;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Encrypts a field on its way into the database and decrypts it on the way out.
 *
 * <p>AES-256-GCM with a fresh random IV per value. GCM rather than CBC because it
 * authenticates as well as encrypts: a row edited directly in the database fails
 * to decrypt rather than returning altered text as though it were genuine. A new
 * IV every time because reusing one under the same key in GCM is not a weakness
 * so much as a total break.
 *
 * <p>Stored as {@code v1:<iv>:<ciphertext>}, both base64. The version prefix is
 * what makes two things possible: telling an encrypted value from a plaintext one
 * written before this existed, and introducing a second scheme later without
 * having to guess which rows are in which format.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    static final String PREFIX = "v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final DataKeyProvider keys;
    private final SecureRandom random = new SecureRandom();

    public EncryptedStringConverter(DataKeyProvider keys) {
        this.keys = keys;
    }

    @Override
    public String convertToDatabaseColumn(String value) {
        if (value == null) {
            return null;
        }
        // An empty string carries nothing worth hiding, and leaving it alone
        // keeps "is this blank" answerable without a round trip through a cipher.
        if (value.isEmpty()) {
            return value;
        }

        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.fieldKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            Base64.Encoder encoder = Base64.getEncoder();
            return PREFIX + encoder.encodeToString(iv) + ':' + encoder.encodeToString(ciphertext);
        } catch (GeneralSecurityException e) {
            // Storing the plaintext instead would be the worst possible recovery.
            throw new IllegalStateException("Could not encrypt a field for storage", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        // Written before this converter existed. Returned as-is so the
        // application keeps working while the backfill catches up; the write
        // path always encrypts, so a row only passes through here once.
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }

        String[] parts = stored.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalStateException(
                    "A stored value carries the encrypted marker but is not in the expected format.");
        }

        try {
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] iv = decoder.decode(parts[1]);
            byte[] ciphertext = decoder.decode(parts[2]);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, keys.fieldKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Almost always the wrong key. Saying so is the difference between a
            // five-minute fix and an afternoon spent suspecting the database.
            throw new IllegalStateException(
                    "A stored value could not be decrypted. This usually means the data "
                            + "encryption key differs from the one it was written with.",
                    e);
        }
    }
}
