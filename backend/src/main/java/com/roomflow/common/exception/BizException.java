package com.roomflow.common.exception;

/** Domain business exception; mapped to HTTP status + Result body by GlobalExceptionHandler. */
public class BizException extends RuntimeException {

  private final ErrorCode errorCode;
  private final transient Object data;

  public BizException(ErrorCode errorCode) {
    super(errorCode.getDefaultMessage());
    this.errorCode = errorCode;
    this.data = null;
  }

  public BizException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
    this.data = null;
  }

  public BizException(ErrorCode errorCode, String message, Object data) {
    super(message);
    this.errorCode = errorCode;
    this.data = data;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }

  public Object getData() {
    return data;
  }
}
