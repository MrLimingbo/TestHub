package io.testhub.platform.desktop;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("desktopBroker")
@Profile("desktop")
public class DesktopBrokerHealthIndicator implements HealthIndicator {
    private final DesktopRuntimeBroker broker;
    public DesktopBrokerHealthIndicator(DesktopRuntimeBroker broker) { this.broker = broker; }
    @Override public Health health() {
        try { broker.check(); return Health.up().build(); }
        catch (RuntimeException exception) { return Health.down().build(); }
    }
}
