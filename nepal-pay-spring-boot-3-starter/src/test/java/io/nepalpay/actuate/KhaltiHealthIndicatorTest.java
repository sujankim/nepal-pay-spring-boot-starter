package io.nepalpay.actuate;

import io.nepalpay.config.NepalPayProperties;
import io.nepalpay.khalti.KhaltiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for KhaltiHealthIndicator — Boot 3.
 */
@DisplayName("KhaltiHealthIndicator")
class KhaltiHealthIndicatorTest {

    private KhaltiClient buildClient(boolean sandbox) {
        NepalPayProperties.KhaltiProperties props =
                new NepalPayProperties.KhaltiProperties(
                        "test_key",
                        "https://example.com/callback",
                        "https://example.com",
                        sandbox, 10, null);
        return new KhaltiClient(props, RestClient.builder(),
                "http://mock-khalti.test");
    }

    @Test
    @DisplayName("health() returns UP when client is configured")
    void health_returnsUp() {
        KhaltiHealthIndicator indicator =
                new KhaltiHealthIndicator(buildClient(true));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("health() details contain gateway=Khalti")
    void health_containsGatewayDetail() {
        KhaltiHealthIndicator indicator =
                new KhaltiHealthIndicator(buildClient(true));

        Health health = indicator.health();

        assertThat(health.getDetails())
                .containsEntry("gateway", "Khalti");
    }

    @Test
    @DisplayName("health() details contain mode=SANDBOX in sandbox mode")
    void health_sandboxMode_modeIsSandbox() {
        KhaltiHealthIndicator indicator =
                new KhaltiHealthIndicator(buildClient(true));

        Health health = indicator.health();

        assertThat(health.getDetails())
                .containsEntry("mode", "SANDBOX");
    }

    @Test
    @DisplayName("health() details contain mode=PRODUCTION in production mode")
    void health_productionMode_modeIsProduction() {
        KhaltiHealthIndicator indicator =
                new KhaltiHealthIndicator(buildClient(false));

        Health health = indicator.health();

        assertThat(health.getDetails())
                .containsEntry("mode", "PRODUCTION");
    }

    @Test
    @DisplayName("health() details contain url")
    void health_containsUrl() {
        KhaltiHealthIndicator indicator =
                new KhaltiHealthIndicator(buildClient(true));

        Health health = indicator.health();

        assertThat(health.getDetails()).containsKey("url");
        assertThat(health.getDetails().get("url"))
                .isNotNull();
    }

    @Test
    @DisplayName("health() details contain configured=true")
    void health_containsConfiguredTrue() {
        KhaltiHealthIndicator indicator =
                new KhaltiHealthIndicator(buildClient(true));

        Health health = indicator.health();

        assertThat(health.getDetails())
                .containsEntry("configured", true);
    }
}