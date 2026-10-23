package io.testhub.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.testhub.platform.shared.runtime.RunnerRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RunnerRegistryFlowTests {
    @Autowired private RunnerRegistry registry;
    private final String members = "testhub:qa:registry:" + UUID.randomUUID();
    private final String heartbeat = members + ":heartbeat:one";
    private final String otherHeartbeat = members + ":heartbeat:two";

    @AfterEach void clean() {
        registry.delete(heartbeat); registry.delete(otherHeartbeat);
        registry.remove(members, "one", "two");
    }

    @Test void staleMissingReadDoesNotRemoveANewlyPublishedHeartbeatOrItsMembership() {
        assertThat(registry.get(heartbeat)).isNull();
        registry.publish(members, heartbeat, "one", "new-signed-heartbeat", Duration.ofSeconds(60));
        registry.removeIfMissing(members, heartbeat, "one");
        assertThat(registry.get(heartbeat)).isEqualTo("new-signed-heartbeat");
        assertThat(registry.members(members)).contains("one");
    }

    @Test void expiredMemberCanBeCleanedWithoutRemovingOtherLiveMembers() throws InterruptedException {
        registry.publish(members, heartbeat, "one", "expired", Duration.ofMillis(20));
        registry.publish(members, otherHeartbeat, "two", "live", Duration.ofSeconds(60));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (registry.get(heartbeat) != null && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(registry.get(heartbeat)).isNull();
        registry.removeIfMissing(members, heartbeat, "one");
        assertThat(registry.members(members)).doesNotContain("one").contains("two");
        assertThat(registry.get(otherHeartbeat)).isEqualTo("live");
    }

    @Test void renewedExpiredHeartbeatSurvivesCleanupBasedOnTheOldMissingRead() throws InterruptedException {
        registry.publish(members, heartbeat, "one", "expired", Duration.ofMillis(20));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (registry.get(heartbeat) != null && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(registry.get(heartbeat)).isNull();
        registry.publish(members, heartbeat, "one", "renewed", Duration.ofSeconds(60));
        registry.removeIfMissing(members, heartbeat, "one");
        assertThat(registry.get(heartbeat)).isEqualTo("renewed");
        assertThat(registry.members(members)).contains("one");
    }

    @Test void explicitRemovalAndShutdownStillRemoveLiveMembers() {
        registry.publish(members, heartbeat, "one", "live", Duration.ofSeconds(60));
        registry.remove(members, "one");
        assertThat(registry.members(members)).doesNotContain("one");
        registry.delete(heartbeat);
        assertThat(registry.get(heartbeat)).isNull();
    }
}
