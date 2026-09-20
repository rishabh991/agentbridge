package io.agentbridge.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the audit topic and projects it into Postgres.
 *
 * <p>Retries and the dead-letter topic are configured centrally in
 * {@link io.agentbridge.gateway.KafkaConfiguration}: an event that cannot be
 * projected after a few attempts goes to {@code <topic>.DLT} rather than blocking
 * the partition behind it forever, which is what M1 did.
 */
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
        store.save(event);
    }

    /** Anything that lands here has already failed its retries; record that it exists. */
    @KafkaListener(topics = "${agentbridge.audit.topic}.DLT", groupId = "${agentbridge.audit.consumer-group}-dlt")
    public void deadLettered(AuditEvent event) {
        log.error("audit event {} ({} on tool {}) could not be projected and is on the dead-letter topic",
                event.eventId(), event.outcome(), event.tool());
    }
}
