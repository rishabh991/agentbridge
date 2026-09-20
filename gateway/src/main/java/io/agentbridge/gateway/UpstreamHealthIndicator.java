package io.agentbridge.gateway;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("upstreams")
class UpstreamHealthIndicator implements HealthIndicator {

    private final UpstreamProbe probe;

    UpstreamHealthIndicator(UpstreamProbe probe) {
        this.probe = probe;
    }

    @Override
    public Health health() {
        var statuses = probe.probeAll();
        var builder = statuses.stream().allMatch(UpstreamProbe.UpstreamStatus::reachable)
                ? Health.up()
                : Health.down();
        statuses.forEach(s -> builder.withDetail(s.name(), s.reachable() ? "up" : "down: " + s.detail()));
        return builder.build();
    }
}
