package com.company.skillplatform.common.application;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public BusinessException(String code, String message, HttpStatus status) {
        this(code, message, status, null);
    }
    public BusinessException(String code, String message, HttpStatus status, Throwable cause) {
        super(message, cause); this.code = code; this.status = status;
    }
    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
}
