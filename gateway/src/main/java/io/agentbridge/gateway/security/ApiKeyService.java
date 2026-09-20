package io.agentbridge.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Issues and resolves API keys.
 *
 * <p>A key is shown exactly once, at creation. Only its hash is stored, so a key
 * cannot be recovered — it can only be replaced.
 */
@Service
public class ApiKeyService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final String PREFIX = "ab_";

    private final ApiKeyRepository repository;
    private final String bootstrapAdminKey;
    private final String bootstrapGuestKey;

    ApiKeyService(ApiKeyRepository repository,
                  @Value("${agentbridge.security.bootstrap-admin-key:}") String bootstrapAdminKey,
                  @Value("${agentbridge.security.bootstrap-guest-key:}") String bootstrapGuestKey) {
        this.repository = repository;
        this.bootstrapAdminKey = bootstrapAdminKey;
        this.bootstrapGuestKey = bootstrapGuestKey;
    }

    /** The issued secret, returned once and never stored in this form. */
    public record IssuedKey(String keyId, String label, String secret, Set<String> scopes, int requestsPerMinute) {
    }

    @Transactional
    public IssuedKey issue(String label, Set<String> scopes, int requestsPerMinute) {
        var secret = generateSecret();
        var keyId = "key_" + UUID.randomUUID().toString().substring(0, 8);
        repository.save(new ApiKey(keyId, hash(secret), label, scopes, requestsPerMinute));
        log.info("issued API key {} ({}) with scopes {}", keyId, label, scopes);
        return new IssuedKey(keyId, label, secret, scopes, requestsPerMinute);
    }

    @Transactional(readOnly = true)
    public Optional<ApiKeyPrincipal> resolve(String presentedSecret) {
        if (presentedSecret == null || presentedSecret.isBlank()) {
            return Optional.empty();
        }
        return repository.findByKeyHashAndEnabledTrue(hash(presentedSecret)).map(ApiKeyPrincipal::of);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list() {
        return repository.findAll();
    }

    @Transactional
    public boolean revoke(String keyId) {
        return repository.findById(keyId).map(key -> {
            key.setEnabled(false);
            repository.save(key);
            log.info("revoked API key {} ({})", keyId, key.getLabel());
            return true;
        }).orElse(false);
    }

    /**
     * Seeds the keys named in configuration so a fresh stack is usable without a
     * chicken-and-egg problem: you need a key to create a key.
     *
     * <p>Seeding an <em>explicit</em> secret from the environment is the only way a
     * deployment can know its own admin key. If none is configured, one is generated
     * and printed once to the log — which is acceptable for a demo and is called out
     * in the README as something a real deployment should not rely on.
     */
    @Override
    public void run(ApplicationArguments args) {
        seed("bootstrap-admin", bootstrapAdminKey, Set.of("admin"), 600, true);
        seed("guest-read-only", bootstrapGuestKey, Set.of("*:read"), 60, false);
    }

    private void seed(String label, String configuredSecret, Set<String> scopes, int rpm, boolean announce) {
        var existing = repository.findByLabel(label);
        if (existing.isPresent()) {
            if (configuredSecret != null && !configuredSecret.isBlank()) {
                var key = existing.get();
                key.setKeyHash(hash(configuredSecret));
                key.setScopes(scopes);
                key.setRequestsPerMinute(rpm);
                key.setEnabled(true);
                repository.save(key);
            }
            return;
        }

        if (configuredSecret != null && !configuredSecret.isBlank()) {
            var keyId = "key_" + label.replace("-", "_");
            repository.save(new ApiKey(keyId, hash(configuredSecret), label, scopes, rpm));
            log.info("seeded API key {} ({}) from configuration", keyId, label);
            return;
        }

        var issued = issue(label, scopes, rpm);
        if (announce) {
            log.warn("""
                    No {} was configured, so one was generated for this boot:

                        {}

                    Set agentbridge.security.bootstrap-admin-key (env AGENTBRIDGE_ADMIN_KEY) to pin it.
                    It will not be shown again.""",
                    label, issued.secret());
        }
    }

    private String generateSecret() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String secret) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
