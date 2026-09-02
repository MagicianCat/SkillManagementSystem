package com.company.skillplatform.common.interfaces;

import com.company.skillplatform.common.application.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus()).body(body(ex.getCode(), ex.getMessage(), request, Map.of()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", "Request validation failed", request, details));
    }
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                          HttpServletRequest request) {
        return ResponseEntity.status(409).body(body("OPTIMISTIC_LOCK_CONFLICT", "Resource version is stale", request, Map.of()));
    }
    private ApiErrorResponse body(String code, String message, HttpServletRequest request, Map<String, Object> details) {
        return ApiErrorResponse.of(code, message, request.getRequestId(), details);
    }
}
