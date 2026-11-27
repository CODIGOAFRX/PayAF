package dev.pedrogomez.payaf.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pedrogomez.payaf.application.port.out.OutboxMessage;
import dev.pedrogomez.payaf.application.port.out.PublishedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InProcessEventBroadcasterTest {

    private static OutboxMessage message(long id) {
        return new OutboxMessage(id, new PublishedEvent("gateway", "stripe", "CircuitOpened", Map.of(), Instant.EPOCH));
    }

    @Test
    @DisplayName("un suscriptor lento pierde los más viejos y no frena a los demás ni a deliver")
    void slowSubscriberDropsOldest() throws Exception {
        InProcessEventBroadcaster broadcaster = new InProcessEventBroadcaster();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstTaken = new CountDownLatch(1);
        List<Long> slow = new CopyOnWriteArrayList<>();
        List<Long> fast = new CopyOnWriteArrayList<>();
        CountDownLatch fastDone = new CountDownLatch(10);

        InProcessEventBroadcaster.Subscription slowSub = broadcaster.subscribe(m -> {
            slow.add(m.id());
            firstTaken.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 3);
        broadcaster.subscribe(m -> {
            fast.add(m.id());
            fastDone.countDown();
        });

        broadcaster.deliver(message(1));
        assertThat(firstTaken.await(5, TimeUnit.SECONDS)).isTrue();
        for (long id = 2; id <= 10; id++) {
            broadcaster.deliver(message(id));
        }
        assertThat(fastDone.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(fast).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

        release.countDown();
        long deadline = System.currentTimeMillis() + 5000;
        while (slow.size() < 4 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(slow).containsExactly(1L, 8L, 9L, 10L);
        assertThat(slowSub.dropped()).isEqualTo(6);

        slowSub.close();
        assertThat(broadcaster.subscriberCount()).isEqualTo(1);
    }
}
