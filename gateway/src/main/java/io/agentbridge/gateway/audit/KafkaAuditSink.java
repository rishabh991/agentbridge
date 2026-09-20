package io.agentbridge.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes to the audit topic, keyed by tool name so one tool's events keep their
 * order.
 *
 * <p>M1 logged publish failures and moved on. That is no longer acceptable now that
 * the gateway claims governance: if the broker will not take the event, it is written
 * straight to Postgres instead. An audit trail with a hole in it is worse than no
 * audit trail, because it is trusted.
 */
@Component
@ConditionalOnProperty(name = "agentbridge.audit.sink", havingValue = "kafka", matchIfMissing = true)
public class KafkaAuditSink implements AuditSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaAuditSink.class);

    private final KafkaTemplate<String, AuditEvent> kafkaTemplate;
    private final AuditStore fallbackStore;
    private final String topic;

    KafkaAuditSink(KafkaTemplate<String, AuditEvent> kafkaTemplate, AuditStore fallbackStore,
                   @Value("${agentbridge.audit.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.fallbackStore = fallbackStore;
        this.topic = topic;
    }

    @Override
    public void publish(AuditEvent event) {
        try {
            kafkaTemplate.send(topic, event.tool(), event).whenComplete((result, error) -> {
                if (error != null) {
                    writeDirectly(event, error);
                }
            });
        } catch (Exception e) {
            writeDirectly(event, e);
        }
    }

    private void writeDirectly(AuditEvent event, Throwable cause) {
        log.error("audit event {} for tool {} could not be published to {} ({}); writing it straight to Postgres",
                event.eventId(), event.tool(), topic, cause.getMessage());
        try {
            fallbackStore.save(event);
        } catch (Exception e) {
            // Both paths are gone. Nothing is left but the log, so make it loud and complete.
            log.error("AUDIT EVENT LOST: neither Kafka nor Postgres accepted {} — {} on tool {} by key {} at {}: {}",
                    event.eventId(), event.outcome(), event.tool(), event.apiKeyId(), event.at(), e.getMessage());
        }
    }
}
