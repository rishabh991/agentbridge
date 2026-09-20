package io.agentbridge.gateway;

import io.agentbridge.gateway.audit.AuditEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(name = "agentbridge.audit.sink", havingValue = "kafka", matchIfMissing = true)
class KafkaConfiguration {

    /**
     * One partition is right for a demo and wrong for production; it is also the only
     * way to keep global ordering without a partition key strategy.
     *
     * <p>Serialization is configured in application.yml: JSON both ways, with type
     * headers switched off and the payload type pinned on the consumer, so a rogue
     * producer cannot name the class the listener instantiates.
     */
    @Bean
    NewTopic auditTopic(@Value("${agentbridge.audit.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic auditDeadLetterTopic(@Value("${agentbridge.audit.topic}") String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(1).replicas(1).build();
    }

    /**
     * Three quick retries, then the dead-letter topic. Blocking the partition on a
     * poison event — M1's behaviour — stops every later audit event behind it, which
     * turns one unprojectable row into a silent gap in the whole trail.
     */
    @Bean
    DefaultErrorHandler auditErrorHandler(KafkaTemplate<String, AuditEvent> kafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
        return new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 3L));
    }
}
