package mn.netgroup.cb.productcatalog.perf;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mn.netgroup.cb.productcatalog.api.CreateProductFamilyRequest;
import mn.netgroup.cb.productcatalog.support.PostgresBackedServerTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * 001:NFR-001 — "read latency of the family-by-identifier operation: request-duration, p95 ≤ 200 ms
 * at 50 requests per second, per test run, over GET of one family by opaque identifier."
 *
 * <p>The load shape comes from the requirement, not from convenience: 50 requests per second held
 * for 60 seconds, and the p95 of {@code http.server.requests} read off the meter the service itself
 * records — the same series a deployment would alert on, not a stopwatch around the client.
 *
 * <p><b>Scope caveat, and it is the honest half.</b> The spec scopes this to the dev deployment;
 * this test measures the build machine, over loopback, against a container on the same host. Read
 * the number as a <b>floor</b>, not as the deployed figure. Plan §10 says the same thing, and lld §7
 * records it beside this row.
 *
 * <p>What holds the number down by construction: virtual threads
 * ({@code spring.threads.virtual.enabled=true}), so a slow database call cannot starve a request
 * thread; one indexed primary-key lookup per request; and one statement per operation, so there is
 * no N+1 to find.
 */
@TestPropertySource(
        properties = {
            // The p95 has to be published for there to be a p95 to read.
            "management.metrics.distribution.percentiles.http.server.requests=0.95",
            "management.metrics.distribution.percentiles-histogram.http.server.requests=true"
        })
class GetFamilyLatencyTest extends PostgresBackedServerTest {

    private static final int REQUESTS_PER_SECOND = 50;
    private static final int SECONDS = 60;
    private static final Duration P95_THRESHOLD = Duration.ofMillis(200);

    @Autowired TestRestTemplate http;
    @Autowired MeterRegistry meters;

    @Test
    void theP95OfTheReadByIdentifierOperationStaysUnderTwoHundredMilliseconds(TestReporter report)
            throws Exception {
        String id = aPersistedFamilyId();
        String path = "/v1/product-families/" + id;

        // Warm the path once so class loading and connection-pool fill are not measured as latency.
        assertThat(http.getForEntity(path, String.class).getStatusCode().value()).isEqualTo(200);
        meters.clear();

        int total = REQUESTS_PER_SECOND * SECONDS;
        long intervalNanos = TimeUnit.SECONDS.toNanos(1) / REQUESTS_PER_SECOND;
        AtomicInteger nonOk = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(total);

        // One virtual thread per request, never pooled: a fixed pool would cap the offered rate at
        // the pool size and measure the harness instead of the service.
        try (ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor()) {
            long start = System.nanoTime();
            for (int i = 0; i < total; i++) {
                long dueAt = start + i * intervalNanos;
                long waitFor = dueAt - System.nanoTime();
                if (waitFor > 0) {
                    TimeUnit.NANOSECONDS.sleep(waitFor);
                }
                requests.submit(() -> {
                    try {
                        ResponseEntity<String> response = http.getForEntity(path, String.class);
                        if (response.getStatusCode().value() != 200) {
                            nonOk.incrementAndGet();
                        }
                    } catch (RuntimeException failed) {
                        nonOk.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(2, TimeUnit.MINUTES))
                    .as("every offered request must complete before the measurement is read")
                    .isTrue();
        }

        Timer timer = meters.get("http.server.requests")
                .tag("uri", "/v1/product-families/{id}")
                .tag("outcome", "SUCCESS")
                .timer();

        List<String> percentiles = new ArrayList<>();
        Duration p95 = null;
        for (ValueAtPercentile measured : timer.takeSnapshot().percentileValues()) {
            percentiles.add("p%.0f=%.1fms"
                    .formatted(measured.percentile() * 100, measured.value(TimeUnit.MILLISECONDS)));
            if (Math.abs(measured.percentile() - 0.95) < 0.0001) {
                p95 = Duration.ofNanos((long) measured.value(TimeUnit.NANOSECONDS));
            }
        }

        String measurement =
                "NFR-001 %d rps for %ds: count=%d, non-200=%d, max=%.1fms, %s, threshold p95<=%dms"
                                .formatted(
                                        REQUESTS_PER_SECOND,
                                        SECONDS,
                                        timer.count(),
                                        nonOk.get(),
                                        timer.max(TimeUnit.MILLISECONDS),
                                        String.join(" ", percentiles),
                                        P95_THRESHOLD.toMillis())
                        + System.lineSeparator()
                        + "Measured on the build machine over loopback, not on the dev deployment the"
                        + " spec scopes NFR-001 to. Read it as a floor." + System.lineSeparator();
        report.publishEntry("NFR-001", measurement);
        // A durable artifact, because a reporter entry the build's console format drops is not a
        // report. The evidence for this row has to survive the run.
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("target/nfr-001-latency.txt"), measurement);

        assertThat(nonOk.get()).as("a run with failed requests measures nothing").isZero();
        assertThat(timer.count())
                .as("the meter must have seen every offered request")
                .isGreaterThanOrEqualTo(total);
        assertThat(p95).as("the 0.95 percentile must be published to be measured").isNotNull();
        assertThat(p95)
                .as("NFR-001: p95 at %d requests per second for %d seconds", REQUESTS_PER_SECOND, SECONDS)
                .isLessThanOrEqualTo(P95_THRESHOLD);
    }

    private String aPersistedFamilyId() {
        ResponseEntity<java.util.Map> created = http.postForEntity(
                "/v1/product-families",
                new CreateProductFamilyRequest("PERFREAD", "the family this test reads"),
                java.util.Map.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return String.valueOf(created.getBody().get("id"));
    }
}
