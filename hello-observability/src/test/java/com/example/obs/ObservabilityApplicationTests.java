package com.example.obs;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 冒烟测试：不挂 agent 也要能跑通全部业务，且指标端点有内容。
 *
 * <p>"无 agent 也能跑"本身就是可观测性设计的验收点：链路能力缺失时，业务代码不能因此失败。
 *
 * <p>这里用 JDK 自带 HttpClient 而不是 TestRestTemplate —— Boot 4 把测试支撑类拆了模块，
 * 少引一个依赖，测试代码也更稳定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityApplicationTests {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${local.server.port}")
    private int port;

    @Test
    void healthIsUp() throws Exception {
        HttpResponse<String> res = get("/actuator/health");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("UP");
    }

    @Test
    void placeOrderThenMetricsExposeBusinessMeter() throws Exception {
        HttpResponse<String> created = post("/api/orders", "{\"sku\":\"A-100\",\"qty\":2}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("orderId").contains("amount");

        HttpResponse<String> prometheus = get("/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body())
                .contains("order_placed_total")
                .contains("http_server_requests_seconds")
                .contains("application=\"hello-observability\"");
    }

    @Test
    void rejectedOrderReturns422() throws Exception {
        HttpResponse<String> rejected = post("/api/orders", "{\"sku\":\"A-100\",\"qty\":60}");
        assertThat(rejected.statusCode()).isEqualTo(422);
        assertThat(rejected.body()).contains("RISK_REJECTED");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return CLIENT.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
