package com.lokesh_codes.expense_tracker_backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nimbusds.jose.jwk.RSAKey;

/**
 * The signing key used to be generated on every boot, which silently
 * invalidated every token in circulation each time the service restarted.
 */
class JwtKeyProviderTest {

    @Test
    @DisplayName("the same key is returned across restarts")
    void keySurvivesARestart(@TempDir Path dir) throws Exception {
        Path keyStore = dir.resolve("jwt-signing-key.json");

        // Two providers stand in for two runs of the application.
        RSAKey first = new JwtKeyProvider(keyStore.toString()).loadOrCreate();
        RSAKey second = new JwtKeyProvider(keyStore.toString()).loadOrCreate();

        assertThat(second.getKeyID()).isEqualTo(first.getKeyID());
        assertThat(second.toRSAPublicKey()).isEqualTo(first.toRSAPublicKey());
    }

    @Test
    @DisplayName("a key is created on first run and written to disk")
    void createsAKeyOnFirstRun(@TempDir Path dir) throws Exception {
        Path keyStore = dir.resolve("nested").resolve("jwt-signing-key.json");

        RSAKey key = new JwtKeyProvider(keyStore.toString()).loadOrCreate();

        assertThat(key).isNotNull();
        assertThat(key.toRSAPrivateKey()).isNotNull();
        // The parent directory did not exist beforehand.
        assertThat(Files.exists(keyStore)).isTrue();
    }

    @Test
    @DisplayName("an unreadable key file is replaced rather than crashing the app")
    void corruptKeyIsReplaced(@TempDir Path dir) throws Exception {
        Path keyStore = dir.resolve("jwt-signing-key.json");
        Files.writeString(keyStore, "this is not a JWK");

        RSAKey key = new JwtKeyProvider(keyStore.toString()).loadOrCreate();

        // Losing every existing token is bad; refusing to start is worse.
        assertThat(key).isNotNull();
        assertThat(Files.readString(keyStore)).contains("\"kty\"");
    }

    @Test
    @DisplayName("the stored key is a usable RSA pair")
    void storedKeyIsComplete(@TempDir Path dir) throws Exception {
        Path keyStore = dir.resolve("jwt-signing-key.json");
        new JwtKeyProvider(keyStore.toString()).loadOrCreate();

        RSAKey reloaded = RSAKey.parse(Files.readString(keyStore));

        // Both halves must persist: the private key signs, the public verifies.
        assertThat(reloaded.toRSAPrivateKey()).isNotNull();
        assertThat(reloaded.toRSAPublicKey()).isNotNull();
        assertThat(reloaded.size()).isEqualTo(2048);
    }
}
