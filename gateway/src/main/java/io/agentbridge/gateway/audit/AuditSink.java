package io.agentbridge.gateway.audit;

/**
 * Where audit events go on their way to storage. Kafka is the default; the direct
 * sink is the documented fallback for hosts too small to run a broker, and it is
 * what the tests use. Both paths land in the same table.
 */
public interface AuditSink {
    void publish(AuditEvent event);
}
