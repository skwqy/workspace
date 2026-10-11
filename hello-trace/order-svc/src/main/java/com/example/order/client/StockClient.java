package com.example.order.client;

import com.example.common.api.QuoteView;
import com.example.common.api.StockView;
import com.example.common.log.Traces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * stock-svc 的 HTTP 客户端：RestClient 由 Boot 自动装配（含 Observation 定制）。
 */
@Component
public class StockClient {

    private static final Logger log = LoggerFactory.getLogger(StockClient.class);

    private final RestClient rest;

    public StockClient(RestClient stockRestClient) {
        this.rest = stockRestClient;
    }

    public StockView getStock(String sku, long delayMs) {
        StockView view = rest.get().uri("/stock/{sku}?delayMs={d}", sku, delayMs)
                .retrieve()
                .body(StockView.class);
        log.info("stock call returned: sku={} available={} thread={} trace={}",
                sku, view == null ? "-" : view.available(),
                Thread.currentThread().getName(), Traces.currentTrace());
        return view;
    }

    public QuoteView getQuote(String sku, long delayMs) {
        QuoteView quote = rest.get().uri("/stock/{sku}/quote?delayMs={d}", sku, delayMs)
                .retrieve()
                .body(QuoteView.class);
        log.info("quote call returned: sku={} unitPrice={} thread={} trace={}",
                sku, quote == null ? "-" : quote.unitPrice(),
                Thread.currentThread().getName(), Traces.currentTrace());
        return quote;
    }
}
