package com.example.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * 用 Boot 自动装配的 RestClient.Builder 构建：Builder 已挂上 Observation 定制，
     * 每次调用自动创建 client span，并把 W3C traceparent 写进请求头，
     * stock-svc 才能把这次调用接在同一个 trace 下。
     */
    @Bean
    RestClient stockRestClient(RestClient.Builder builder,
                               @Value("${stock.base-url:http://localhost:8082}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
