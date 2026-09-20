package io.agentbridge.gateway.audit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Writes straight to Postgres, skipping the broker. The README offers this as the
 * honest fallback when the demo host cannot spare memory for Kafka.
 */
@Component
@ConditionalOnProperty(name = "agentbridge.audit.sink", havingValue = "direct")
public class DirectAuditSink implements AuditSink {

    private final AuditStore store;

    DirectAuditSink(AuditStore store) {
        this.store = store;
    }

    @Override
    public void publish(AuditEvent event) {
        store.save(event);
    }
}
