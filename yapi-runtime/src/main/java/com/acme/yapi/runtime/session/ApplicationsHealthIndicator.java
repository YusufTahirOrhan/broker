package com.acme.yapi.runtime.session;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * {@code applications} in the readiness group: out of service until the application list was read once, so no
 * request is refused only because the list has not arrived yet.
 */
@Component
public class ApplicationsHealthIndicator implements HealthIndicator {

    private final ApplicationList applications;

    public ApplicationsHealthIndicator(ApplicationList applications) {
        this.applications = applications;
    }

    @Override
    public Health health() {
        return applications.loaded() ? Health.up().build()
                : Health.outOfService().withDetail("reason", "Application listesi henüz okunamadı").build();
    }
}
