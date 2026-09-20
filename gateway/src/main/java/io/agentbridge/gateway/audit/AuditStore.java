package io.agentbridge.gateway.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** The projection side: audit events as rows you can query. */
@Service
public class AuditStore {

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;

    AuditStore(AuditEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void save(AuditEvent event) {
        var arguments = event.arguments() == null ? "{}" : event.arguments().toString();
        repository.save(new AuditEventEntity(event, arguments));
    }

    @Transactional(readOnly = true)
    public List<AuditEventEntity> recent(int limit) {
        return repository.findAllByOrderByOccurredAtDesc(Limit.of(Math.clamp(limit, 1, 200)));
    }

    @Transactional(readOnly = true)
    public long count() {
        return repository.count();
    }
}
