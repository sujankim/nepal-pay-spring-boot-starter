package io.nepalpay.reactive.actuate;

import io.nepalpay.reactive.config.NepalPayProperties;
import io.nepalpay.reactive.khalti.KhaltiReactiveClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for KhaltiReactiveHealthIndicator.
 */
@DisplayName("KhaltiReactiveHealthIndicator")
class KhaltiReactiveHealthIndicatorTest {

    private KhaltiReactiveClient buildClient(boolean sandbox) {
        NepalPayProperties.KhaltiProperties props =
                new NepalPayProperties.KhaltiProperties(
                        "test_key", "https://example.com/cb",
                        "https://example.com", sandbox, 10, null);
        return new KhaltiReactiveClient(
                props, WebClient.builder(), "http://mock.test");
    }

    @Test
    @DisplayName("health() returns Mono of UP status")
    void health_returnsMonoUp() {
        KhaltiReactiveHealthIndicator indicator =
                new KhaltiReactiveHealthIndicator(buildClient(true));

        StepVerifier.create(indicator.health())
                .expectNextMatches(h ->
                        h.getStatus().equals(Status.UP)
                                && "Khalti".equals(h.getDetails().get("gateway"))
                                && "SANDBOX".equals(h.getDetails().get("mode"))
                                && Boolean.TRUE.equals(h.getDetails().get("configured")))
                .verifyComplete();
    }

    @Test
    @DisplayName("health() returns PRODUCTION mode when sandbox=false")
    void health_productionMode() {
        KhaltiReactiveHealthIndicator indicator =
                new KhaltiReactiveHealthIndicator(buildClient(false));

        StepVerifier.create(indicator.health())
                .expectNextMatches(h ->
                        "PRODUCTION".equals(h.getDetails().get("mode")))
                .verifyComplete();
    }

    @Test
    @DisplayName("health() completes and returns UP status via StepVerifier")
    void health_completesAndReturnsUp() {
        KhaltiReactiveHealthIndicator indicator =
                new KhaltiReactiveHealthIndicator(buildClient(true));

        StepVerifier.create(indicator.health())
                .assertNext(h ->
                        assertThat(h.getStatus()).isEqualTo(Status.UP))
                .verifyComplete();
    }
}