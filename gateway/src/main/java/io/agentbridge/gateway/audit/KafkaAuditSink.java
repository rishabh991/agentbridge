package io.agentbridge.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes to the audit topic, keyed by tool name so one tool's events keep their
 * order. A failure to publish is logged loudly and swallowed: M1 will not fail a
 * tool call because the audit broker is down. M2 replaces this with a dead-letter
 * path, which is the point at which swallowing stops being acceptable.
 */
@Component
@ConditionalOnProperty(name = "agentbridge.audit.sink", havingValue = "kafka", matchIfMissing = true)
public class KafkaAuditSink implements AuditSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaAuditSink.class);

    private final KafkaTemplate<String, AuditEvent> kafkaTemplate;
    private final String topic;

    KafkaAuditSink(KafkaTemplate<String, AuditEvent> kafkaTemplate,
                   @org.springframework.beans.factory.annotation.Value("${agentbridge.audit.topic}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(AuditEvent event) {
        kafkaTemplate.send(topic, event.tool(), event).whenComplete((result, error) -> {
            if (error != null) {
                log.error("audit event {} for tool {} was not published to {}: {}",
                        event.eventId(), event.tool(), topic, error.getMessage());
            }
        });
    }
}
