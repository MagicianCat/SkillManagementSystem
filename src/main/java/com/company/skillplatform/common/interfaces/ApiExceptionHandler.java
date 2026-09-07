package com.company.skillplatform.common.interfaces;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.common.logging.LogContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        log.warn("event=request.business.failed requestId={} actorId={} method={} path={} errorCode={} status={}",
                LogContext.requestId(), LogContext.actorId(), request.getMethod(), request.getRequestURI(),
                ex.getCode(), ex.getStatus().value());
        return ResponseEntity.status(ex.getStatus()).body(body(ex.getCode(), ex.getMessage(), request, Map.of()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        log.warn("event=request.validation.failed requestId={} actorId={} method={} path={} errorCode={}",
                LogContext.requestId(), LogContext.actorId(), request.getMethod(), request.getRequestURI(), "VALIDATION_FAILED");
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", "Request validation failed", request, details));
    }
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                          HttpServletRequest request) {
        log.warn("event=request.optimistic_lock.conflict requestId={} actorId={} method={} path={} errorCode={}",
                LogContext.requestId(), LogContext.actorId(), request.getMethod(), request.getRequestURI(), "OPTIMISTIC_LOCK_CONFLICT");
        return ResponseEntity.status(409).body(body("OPTIMISTIC_LOCK_CONFLICT", "Resource version is stale", request, Map.of()));
    }
    @ExceptionHandler({AuthorizationDeniedException.class, AccessDeniedException.class})
    ResponseEntity<ApiErrorResponse> handleAccessDenied(RuntimeException ex, HttpServletRequest request) {
        log.warn("event=auth.access.denied requestId={} actorId={} method={} path={} errorCode={}",
                LogContext.requestId(), LogContext.actorId(), request.getMethod(), request.getRequestURI(), "ACCESS_DENIED");
        return ResponseEntity.status(403).body(body("ACCESS_DENIED", "Access denied", request, Map.of()));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("event=request.unhandled.exception requestId={} actorId={} method={} path={} errorCode={}",
                LogContext.requestId(), LogContext.actorId(), request.getMethod(), request.getRequestURI(), "INTERNAL_ERROR", ex);
        return ResponseEntity.internalServerError().body(body("INTERNAL_ERROR", "Internal server error", request, Map.of()));
    }
    private ApiErrorResponse body(String code, String message, HttpServletRequest request, Map<String, Object> details) {
        return ApiErrorResponse.of(code, message, request.getRequestId(), details);
    }
}
