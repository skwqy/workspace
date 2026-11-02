package com.example.obs.web;

import com.example.obs.log.Traces;
import com.example.obs.service.BusinessException;
import com.example.obs.web.dto.ErrorView;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口：把"异常"同时翻译给三支柱。
 *
 * <ul>
 *   <li>指标：5xx 会体现在 {@code http_server_requests_seconds{status="500"}}，无需额外埋点；</li>
 *   <li>链路：显式在当前 span 上 {@code setStatus(ERROR)} + {@code recordException}，
 *       让 Jaeger 里这条 span 一眼可见（默认只有 5xx 由 agent 自动标红，4xx 需要自己动手）；</li>
 *   <li>日志：ERROR 级别 + 堆栈，随 traceId 一起进 ES。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorView> handleBusiness(BusinessException e) {
        // 业务异常多为 4xx：不打堆栈（打日志的目的是查问题，纯业务拒绝不需要栈）
        markSpanError(e);
        log.warn("business exception code={} status={} message={}", e.getCode(), e.getStatus().value(), e.getMessage());
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorView(e.getCode(), e.getMessage(), Traces.traceId(), OffsetDateTime.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorView> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid request");
        Span.current().setAttribute("validation.failed", detail);
        log.warn("validation failed: {}", detail);
        return ResponseEntity.badRequest()
                .body(new ErrorView("VALIDATION_FAILED", detail, Traces.traceId(), OffsetDateTime.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorView> handleUnexpected(Exception e) {
        markSpanError(e);
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorView("INTERNAL_ERROR", e.getMessage(), Traces.traceId(), OffsetDateTime.now()));
    }

    private static void markSpanError(Throwable e) {
        Span span = Span.current();
        if (span.getSpanContext().isValid()) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
        }
    }
}
