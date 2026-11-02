package com.example.obs.service;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：带机器可读的 {@code code}（进指标 tag / 日志字段）与 HTTP 状态码。
 */
public class BusinessException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
