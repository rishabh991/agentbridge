package io.agentbridge.gateway.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Key administration. Requires the {@code admin} scope — see SecurityConfiguration.
 *
 * <p>A created key's secret appears in exactly one response and is never retrievable
 * again, which is why the list endpoint returns metadata only.
 */
@RestController
@RequestMapping("/api/v1/keys")
class ApiKeyController {

    private final ApiKeyService service;

    ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    record CreateKeyRequest(
            @NotBlank String label,
            @NotEmpty Set<String> scopes,
            @Min(1) int requestsPerMinute) {
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@jakarta.validation.Valid @RequestBody CreateKeyRequest request) {
        var issued = service.issue(request.label(), request.scopes(), request.requestsPerMinute());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "keyId", issued.keyId(),
                "label", issued.label(),
                "scopes", issued.scopes(),
                "requestsPerMinute", issued.requestsPerMinute(),
                "secret", issued.secret(),
                "notice", "This secret is shown once. Store it now; it cannot be recovered."));
    }

    @GetMapping
    List<Map<String, Object>> list() {
        return service.list().stream().map(key -> Map.<String, Object>of(
                "keyId", key.getKeyId(),
                "label", key.getLabel(),
                "scopes", key.getScopes(),
                "requestsPerMinute", key.getRequestsPerMinute(),
                "enabled", key.isEnabled(),
                "createdAt", key.getCreatedAt())).toList();
    }

    @DeleteMapping("/{keyId}")
    ResponseEntity<Void> revoke(@PathVariable String keyId) {
        return service.revoke(keyId) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
