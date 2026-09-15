package com.roomflow.common.exception;

import com.roomflow.common.result.FieldError;
import com.roomflow.common.result.Result;
import com.roomflow.common.result.ValidationErrors;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps exceptions to the contract's Result body and HTTP status codes. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BizException.class)
  public ResponseEntity<Result<Object>> handleBiz(BizException e) {
    return ResponseEntity.status(e.getErrorCode().getHttpStatus())
        .body(Result.error(e.getErrorCode().getCode(), e.getMessage(), e.getData()));
  }

  /** Bean validation failures on @RequestBody (@Valid): code=40001 with fieldErrors. */
  @ExceptionHandler(BindException.class)
  public ResponseEntity<Result<ValidationErrors>> handleBind(BindException e) {
    List<FieldError> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
            .toList();
    return ResponseEntity.badRequest()
        .body(
            Result.error(
                ErrorCode.PARAM_INVALID.getCode(),
                ErrorCode.PARAM_INVALID.getDefaultMessage(),
                new ValidationErrors(errors)));
  }

  /** Spring 6.1+ method validation on @RequestParam/@PathVariable constraints: code=40001. */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<Result<ValidationErrors>> handleMethodValidation(
      HandlerMethodValidationException e) {
    List<FieldError> errors =
        e.getParameterValidationResults().stream()
            .flatMap(
                r ->
                    r.getResolvableErrors().stream()
                        .map(err -> new FieldError(paramName(r), err.getDefaultMessage())))
            .toList();
    return ResponseEntity.badRequest()
        .body(
            Result.error(
                ErrorCode.PARAM_INVALID.getCode(),
                ErrorCode.PARAM_INVALID.getDefaultMessage(),
                new ValidationErrors(errors)));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<Result<ValidationErrors>> handleConstraintViolation(
      ConstraintViolationException e) {
    List<FieldError> errors =
        e.getConstraintViolations().stream()
            .map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage()))
            .toList();
    return ResponseEntity.badRequest()
        .body(
            Result.error(
                ErrorCode.PARAM_INVALID.getCode(),
                ErrorCode.PARAM_INVALID.getDefaultMessage(),
                new ValidationErrors(errors)));
  }

  /**
   * Malformed JSON, missing body, missing param/part, param type mismatch, bad multipart: 40002.
   */
  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    MissingServletRequestPartException.class,
    MultipartException.class,
    HttpMediaTypeNotSupportedException.class
  })
  public ResponseEntity<Result<Object>> handleBadRequest(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            Result.error(
                ErrorCode.BAD_REQUEST.getCode(), ErrorCode.BAD_REQUEST.getDefaultMessage()));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Result<Object>> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException e) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(Result.error(ErrorCode.INTERNAL.getCode(), "请求方法不支持"));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<Result<Object>> handleNoResource(NoResourceFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(Result.error(ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getDefaultMessage()));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Result<Object>> handleAccessDenied(AccessDeniedException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(Result.error(ErrorCode.FORBIDDEN.getCode(), ErrorCode.FORBIDDEN.getDefaultMessage()));
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<Result<Object>> handleAuthentication(AuthenticationException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(
            Result.error(
                ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getDefaultMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Result<Object>> handleOther(Exception e) {
    log.error("Unhandled exception", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Result.error(ErrorCode.INTERNAL.getCode(), ErrorCode.INTERNAL.getDefaultMessage()));
  }

  private static String paramName(ParameterValidationResult result) {
    return result.getMethodParameter().getParameterName() != null
        ? result.getMethodParameter().getParameterName()
        : "param";
  }
}
