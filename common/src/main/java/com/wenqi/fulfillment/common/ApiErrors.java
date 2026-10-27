package com.wenqi.fulfillment.common;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

  private ResponseEntity<Map<String, Object>> response(
      HttpStatus status, String code, String detail) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "status",
                status.value(),
                "code",
                code,
                "detail",
                detail,
                "requestId",
                MDC.get("requestId") == null ? "unknown" : MDC.get("requestId")));
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> api(ApiException e) {
    return response(e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  public ResponseEntity<Map<String, Object>> invalid(Exception e) {
    return response(
        HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request does not satisfy the API contract");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException e) {
    log.warn("Database constraint rejected request: {}", e.getClass().getSimpleName());
    return response(
        HttpStatus.CONFLICT, "CONFLICT", "The operation conflicts with persisted state");
  }

  @ExceptionHandler(NoHandlerFoundException.class)
  public ResponseEntity<Map<String, Object>> missing(NoHandlerFoundException e) {
    return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "No such resource");
  }

  @ExceptionHandler({
    org.springframework.web.ErrorResponseException.class,
    org.springframework.web.HttpMediaTypeNotSupportedException.class,
    org.springframework.web.HttpRequestMethodNotSupportedException.class,
    org.springframework.web.servlet.resource.NoResourceFoundException.class
  })
  public ResponseEntity<Map<String, Object>> http(Exception exception) {
    var error = (org.springframework.web.ErrorResponse) exception;
    var status = HttpStatus.valueOf(error.getStatusCode().value());
    return response(status, "HTTP_" + status.value(), status.getReasonPhrase());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
    log.error("Unhandled request failure cause={}", e.getClass().getSimpleName());
    return response(
        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Request failed; refer to requestId");
  }
}
