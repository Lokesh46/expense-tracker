package com.lokesh_codes.expense_tracker_backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nimbusds.jose.jwk.RSAKey;

/**
 * The signing key used to be generated on every boot, which silently
 * invalidated every token in circulation each time the service restarted.
 */
class JwtKeyProviderTest {

    /** File-backed provider, as used in local development. */
    private static JwtKeyProvider fileBacked(Path keyStore) {
        return new JwtKeyProvider(keyStore.toString(), "");
    }

    @Nested
    @DisplayName("file-backed key")
    class FileBacked {

        @Test
        @DisplayName("the same key is returned across restarts")
        void keySurvivesARestart(@TempDir Path dir) throws Exception {
            Path keyStore = dir.resolve("jwt-signing-key.json");

            // Two providers stand in for two runs of the application.
            RSAKey first = fileBacked(keyStore).loadOrCreate();
            RSAKey second = fileBacked(keyStore).loadOrCreate();

            assertThat(second.getKeyID()).isEqualTo(first.getKeyID());
            assertThat(second.toRSAPublicKey()).isEqualTo(first.toRSAPublicKey());
        }

        @Test
        @DisplayName("a key is created on first run and written to disk")
        void createsAKeyOnFirstRun(@TempDir Path dir) throws Exception {
            Path keyStore = dir.resolve("nested").resolve("jwt-signing-key.json");

            RSAKey key = fileBacked(keyStore).loadOrCreate();

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

            RSAKey key = fileBacked(keyStore).loadOrCreate();

            // Losing every existing token is bad; refusing to start is worse.
            assertThat(key).isNotNull();
            assertThat(Files.readString(keyStore)).contains("\"kty\"");
        }

        @Test
        @DisplayName("the stored key is a usable RSA pair")
        void storedKeyIsComplete(@TempDir Path dir) throws Exception {
            Path keyStore = dir.resolve("jwt-signing-key.json");
            fileBacked(keyStore).loadOrCreate();

            RSAKey reloaded = RSAKey.parse(Files.readString(keyStore));

            // Both halves must persist: the private key signs, the public verifies.
            assertThat(reloaded.toRSAPrivateKey()).isNotNull();
            assertThat(reloaded.toRSAPublicKey()).isNotNull();
            assertThat(reloaded.size()).isEqualTo(2048);
        }
    }

    /**
     * What production uses. A container filesystem is wiped on redeploy, so a
     * file-based key there would sign every user out on each deploy.
     */
    @Nested
    @DisplayName("key supplied by configuration")
    class Configured {

        @Test
        @DisplayName("the configured key is used in preference to any file")
        void configuredKeyWins(@TempDir Path dir) throws Exception {
            Path keyStore = dir.resolve("jwt-signing-key.json");

            // A file key exists and must be ignored.
            RSAKey fileKey = fileBacked(keyStore).loadOrCreate();

            // A genuinely different key, generated in its own location, so that
            // "the configured one won" is distinguishable from "both are equal".
            RSAKey configuredKey = fileBacked(dir.resolve("other.json")).loadOrCreate();
            String configuredJwk = configuredKey.toJSONString();

            assertThat(configuredKey.getKeyID()).isNotEqualTo(fileKey.getKeyID());

            RSAKey resolved = new JwtKeyProvider(keyStore.toString(), configuredJwk).loadOrCreate();

            assertThat(resolved.getKeyID()).isEqualTo(configuredKey.getKeyID());
            assertThat(resolved.getKeyID()).isNotEqualTo(fileKey.getKeyID());
        }

        @Test
        @DisplayName("the same configured key survives a restart with no filesystem at all")
        void configuredKeySurvivesWithoutAFile(@TempDir Path dir) throws Exception {
            Path missing = dir.resolve("does-not-exist").resolve("key.json");
            String jwk = fileBacked(dir.resolve("seed.json")).loadOrCreate().toJSONString();

            RSAKey first = new JwtKeyProvider(missing.toString(), jwk).loadOrCreate();
            RSAKey second = new JwtKeyProvider(missing.toString(), jwk).loadOrCreate();

            assertThat(first.getKeyID()).isEqualTo(second.getKeyID());
            assertThat(Files.exists(missing)).isFalse();
        }

        @Test
        @DisplayName("a malformed configured key stops startup instead of silently regenerating")
        void malformedConfiguredKeyFailsLoudly(@TempDir Path dir) {
            Path keyStore = dir.resolve("jwt-signing-key.json");

            // Falling back to a fresh key would log everyone out on every deploy
            // and give no clue why, which is far harder to diagnose.
            assertThatThrownBy(
                    () -> new JwtKeyProvider(keyStore.toString(), "not-a-jwk").loadOrCreate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.jwt.key");
        }

        @Test
        @DisplayName("a blank setting falls back to the file")
        void blankConfiguredKeyFallsBack(@TempDir Path dir) throws Exception {
            Path keyStore = dir.resolve("jwt-signing-key.json");

            // Unset variables commonly arrive as an empty string, which must not
            // be mistaken for a malformed key.
            RSAKey key = new JwtKeyProvider(keyStore.toString(), "   ").loadOrCreate();

            assertThat(key).isNotNull();
            assertThat(Files.exists(keyStore)).isTrue();
        }
    }
}
