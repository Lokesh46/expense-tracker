package com.lokesh_codes.expense_tracker_backend.service.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Supplies the keys that protect transaction text at rest.
 *
 * <p>Two sources, mirroring {@code JwtKeyProvider}: {@code app.crypto.key} holds
 * the key inline and takes precedence, which is what production must use because
 * a container filesystem is wiped on redeploy. {@code app.crypto.key-store} is a
 * file fallback that suits local development.
 *
 * <p>It differs from the JWT key in one way that matters. A signing key that
 * cannot be read is replaced: the cost is that everyone is signed out once. A
 * data key that cannot be read must never be replaced, because a new key does not
 * decrypt anything written under the old one — it converts every description in
 * the database into permanent noise. So a corrupt or wrongly-sized key stops the
 * application instead, which is recoverable; quietly generating a replacement is
 * not.
 *
 * <p>Two keys are derived from the one secret rather than used directly. The
 * field key encrypts; the index key produces the searchable digests. Using one
 * key for both would mean a weakness in either construction weakens the other,
 * and it removes the option of rotating them independently later.
 */
@Component
public class DataKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(DataKeyProvider.class);

    private static final int KEY_BYTES = 32;
    private static final String HMAC = "HmacSHA256";

    /**
     * Labels that separate the derived keys. Changing either of these strings
     * changes the key it derives, which makes existing data unreadable — they
     * are effectively part of the on-disk format.
     */
    private static final String FIELD_KEY_LABEL = "expense-tracker:field-encryption:v1";
    private static final String INDEX_KEY_LABEL = "expense-tracker:blind-index:v1";

    private final SecretKey fieldKey;
    private final SecretKey indexKey;

    public DataKeyProvider(
            Environment environment,
            @Value("${app.crypto.key:}") String inlineKey,
            @Value("${app.crypto.key-store:./data/data-encryption-key.b64}") String keyStore) {

        requireConfiguredKeyInProduction(environment, inlineKey);

        byte[] master = resolve(inlineKey, Paths.get(keyStore).toAbsolutePath());
        this.fieldKey = derive(master, FIELD_KEY_LABEL);
        this.indexKey = derive(master, INDEX_KEY_LABEL);
        java.util.Arrays.fill(master, (byte) 0);
    }

    /**
     * Refuses to start a production instance that has no configured key.
     *
     * <p>Without one, the file fallback takes over and generates a key onto the
     * container's filesystem. That filesystem is wiped on every redeploy, so the
     * next deploy generates a different key and cannot read anything the last one
     * wrote — every description and comment becomes permanent noise, and the
     * first sign of it is a page of errors after a deploy that looked fine.
     *
     * <p>The same shape of guard as the CORS wildcard check: the failure it
     * prevents is silent and unrecoverable, so the check is loud and at boot.
     */
    private void requireConfiguredKeyInProduction(Environment environment, String inlineKey) {
        if (!environment.matchesProfiles("prod")) {
            return;
        }
        if (inlineKey == null || inlineKey.isBlank()) {
            throw new IllegalStateException(
                    "DATA_ENCRYPTION_KEY is not set. Transaction descriptions and comments are "
                            + "encrypted with it, and a key generated on a container filesystem is "
                            + "lost on the next redeploy -- taking every description with it. "
                            + "Generate one with: "
                            + "openssl rand -base64 32");
        }
    }

    /** The key used to encrypt and decrypt stored field values. */
    public SecretKey fieldKey() {
        return fieldKey;
    }

    /** The key used to produce search digests. Never used to encrypt. */
    public SecretKey indexKey() {
        return indexKey;
    }

    private byte[] resolve(String inlineKey, Path keyStorePath) {
        if (inlineKey != null && !inlineKey.isBlank()) {
            log.info("Loaded data encryption key from configuration");
            return decode(inlineKey, "app.crypto.key");
        }

        if (Files.exists(keyStorePath)) {
            try {
                String stored = Files.readString(keyStorePath, StandardCharsets.UTF_8).trim();
                log.info("Loaded data encryption key from {}", keyStorePath);
                return decode(stored, keyStorePath.toString());
            } catch (IOException e) {
                throw new IllegalStateException(
                        "The data encryption key at " + keyStorePath + " could not be read. "
                                + "Restore it; generating a new one would make every stored "
                                + "description and comment permanently unreadable.",
                        e);
            }
        }

        return generateAndStore(keyStorePath);
    }

    /**
     * Creates the key on first run.
     *
     * <p>Only reached when no key exists at all, so there is nothing yet to lose.
     * Logged loudly because an instance that generates its own key on a wiped
     * container filesystem has silently discarded whatever came before.
     */
    private byte[] generateAndStore(Path keyStorePath) {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);

        try {
            Path parent = keyStorePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(keyStorePath, Base64.getEncoder().encodeToString(key),
                    StandardCharsets.UTF_8);
            log.warn("No data encryption key found; generated one at {}. "
                    + "Set DATA_ENCRYPTION_KEY in production — a container filesystem is wiped "
                    + "on redeploy, and a key generated per deploy cannot read the previous "
                    + "deploy's data.", keyStorePath);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not write a data encryption key to " + keyStorePath
                            + ". Set app.crypto.key instead.",
                    e);
        }
        return key;
    }

    private byte[] decode(String encoded, String source) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "The data encryption key in " + source + " is not valid base64.", e);
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalStateException(
                    "The data encryption key in " + source + " is " + key.length
                            + " bytes; it must be exactly " + KEY_BYTES + ".");
        }
        return key;
    }

    /**
     * HKDF-SHA256, in the one shape needed here: a single 32-byte output per
     * label. Written out rather than pulled in as a dependency because it is
     * eight lines and the alternative is a library on the boot path.
     */
    private SecretKey derive(byte[] master, String label) {
        try {
            // Extract. No salt: the input is already a uniformly random 256-bit
            // key rather than a passphrase, so a salt adds nothing here.
            Mac extract = Mac.getInstance(HMAC);
            extract.init(new SecretKeySpec(new byte[32], HMAC));
            byte[] prk = extract.doFinal(master);

            // Expand. One round is enough: the output is the same size as the hash.
            Mac expand = Mac.getInstance(HMAC);
            expand.init(new SecretKeySpec(prk, HMAC));
            expand.update(label.getBytes(StandardCharsets.UTF_8));
            expand.update((byte) 1);
            byte[] derived = expand.doFinal();

            java.util.Arrays.fill(prk, (byte) 0);
            return new SecretKeySpec(derived, 0, KEY_BYTES, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not derive the " + label + " key", e);
        }
    }
}
