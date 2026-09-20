package io.agentbridge.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes the audit topic and projects it into Postgres. */
@Component
@ConditionalOnProperty(name = "agentbridge.audit.sink", havingValue = "kafka", matchIfMissing = true)
public class AuditProjector {

    private static final Logger log = LoggerFactory.getLogger(AuditProjector.class);

    private final AuditStore store;

    AuditProjector(AuditStore store) {
        this.store = store;
    }

    @KafkaListener(topics = "${agentbridge.audit.topic}", groupId = "${agentbridge.audit.consumer-group}")
    public void project(AuditEvent event) {
        try {
            store.save(event);
        } catch (Exception e) {
            // M2 adds the dead-letter topic this deserves.
            log.error("could not project audit event {}: {}", event.eventId(), e.getMessage());
        }
    }
}
