package io.testhub.platform.desktop;

import io.testhub.platform.notification.NotificationDeliveryRepository;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The desktop owns a single backend. Do this before scheduled dispatchers can claim new work. */
@Component
@Profile("desktop")
public class DesktopNotificationRecovery implements SmartInitializingSingleton {
    private final NotificationDeliveryRepository deliveries;
    public DesktopNotificationRecovery(NotificationDeliveryRepository deliveries) { this.deliveries = deliveries; }
    @Override public void afterSingletonsInstantiated() { deliveries.failInterrupted(); }
}
