package io.agentbridge.gateway;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "agentbridge.audit.sink", havingValue = "kafka", matchIfMissing = true)
class KafkaConfiguration {

    /**
     * One partition is right for a demo and wrong for production; it is also the only
     * way to keep global ordering without a partition key strategy, which M2 adds
     * along with the dead-letter topic.
     *
     * <p>Serialization is configured in application.yml: JSON both ways, with type
     * headers switched off and the payload type pinned on the consumer, so a rogue
     * producer cannot name the class the listener instantiates.
     */
    @Bean
    NewTopic auditTopic(@Value("${agentbridge.audit.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(1).replicas(1).build();
    }
}
