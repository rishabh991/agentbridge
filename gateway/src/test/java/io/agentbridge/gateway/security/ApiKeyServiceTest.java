package io.agentbridge.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ApiKeyServiceTest {

    @Autowired
    ApiKeyService service;

    @Autowired
    ApiKeyRepository repository;

    @Test
    void issuesAKeyWhoseSecretIsNeverStored() {
        var issued = service.issue("test-issue", Set.of("orders:read"), 30);

        assertThat(issued.secret()).startsWith("ab_");

        var stored = repository.findById(issued.keyId()).orElseThrow();
        assertThat(stored.getKeyHash()).isNotEqualTo(issued.secret());
        assertThat(stored.getKeyHash()).hasSize(64);
        assertThat(repository.findAll())
                .noneMatch(key -> key.getKeyHash().contains(issued.secret()));
    }

    @Test
    void resolvesOnlyTheExactSecret() {
        var issued = service.issue("test-resolve", Set.of("orders:read"), 30);

        assertThat(service.resolve(issued.secret())).isPresent();
        assertThat(service.resolve(issued.secret() + "x")).isEmpty();
        assertThat(service.resolve("ab_not-a-real-key")).isEmpty();
        assertThat(service.resolve(null)).isEmpty();
        assertThat(service.resolve("")).isEmpty();
    }

    @Test
    void aRevokedKeyStopsResolving() {
        var issued = service.issue("test-revoke", Set.of("orders:read"), 30);
        assertThat(service.resolve(issued.secret())).isPresent();

        assertThat(service.revoke(issued.keyId())).isTrue();

        assertThat(service.resolve(issued.secret())).isEmpty();
    }

    @Test
    void seedsAnAdminAndAReadOnlyGuestKeyOnStartup() {
        var labels = service.list().stream().map(ApiKey::getLabel).toList();

        assertThat(labels).contains("bootstrap-admin", "guest-read-only");

        var guest = service.list().stream()
                .filter(k -> k.getLabel().equals("guest-read-only")).findFirst().orElseThrow();
        assertThat(guest.getScopes()).containsExactly("*:read");
        assertThat(guest.hasScope("orders:read")).isTrue();
        assertThat(guest.hasScope("orders:write")).isFalse();
    }

    @Test
    void theConfiguredBootstrapSecretIsTheOneThatWorks() {
        // application.yml under src/test/resources pins these.
        assertThat(service.resolve("ab_test-admin-key"))
                .get()
                .satisfies(principal -> assertThat(principal.scopes()).contains("admin"));
        assertThat(service.resolve("ab_test-guest-key"))
                .get()
                .satisfies(principal -> assertThat(principal.scopes()).contains("*:read"));
    }
}
