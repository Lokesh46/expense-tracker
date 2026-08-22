package com.lokesh_codes.expense_tracker_backend.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.RSAKey;

/**
 * Supplies the RSA key used to sign JWTs, persisting it to disk.
 *
 * <p>The key was previously generated fresh on every boot, which silently
 * invalidated every token in circulation each time the service restarted.
 * Loading it from a stable location keeps sessions alive across restarts and
 * lets more than one instance verify the same tokens.
 *
 * <p>In production, point {@code app.jwt.key-store} at a mounted secret rather
 * than relying on the container filesystem, which is wiped on redeploy.
 */
@Component
public class JwtKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyProvider.class);

    private final Path keyStorePath;

    public JwtKeyProvider(@Value("${app.jwt.key-store}") String keyStore) {
        this.keyStorePath = Paths.get(keyStore).toAbsolutePath();
    }

    /**
     * Returns the persisted signing key, creating and saving one on first run.
     * A key that cannot be read (corrupt or truncated) is replaced rather than
     * bringing the application down — existing tokens are lost, but only once.
     */
    public RSAKey loadOrCreate() {
        if (Files.exists(keyStorePath)) {
            try {
                RSAKey existing = RSAKey.parse(Files.readString(keyStorePath));
                log.info("Loaded JWT signing key from {}", keyStorePath);
                return existing;
            } catch (IOException | ParseException e) {
                log.warn("JWT signing key at {} is unreadable ({}); generating a replacement. "
                        + "Tokens issued before now will be rejected.", keyStorePath, e.getMessage());
            }
        }

        RSAKey generated = generate();
        persist(generated);
        return generated;
    }

    private RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate an RSA key pair", e);
        }
    }

    private void persist(RSAKey key) {
        try {
            Path parent = keyStorePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(keyStorePath, key.toJSONString());
            log.info("Generated a new JWT signing key at {}", keyStorePath);
        } catch (IOException e) {
            // Not fatal: the service still works, but tokens die on the next restart.
            log.error("Could not write the JWT signing key to {}. Tokens will not survive a "
                    + "restart until this path is writable.", keyStorePath, e);
        }
    }
}
