package com.roomflow.common.result;

import lombok.Data;

/** Unified API response body: {code, message, data}. code=0 means success. */
@Data
public class Result<T> {

  private int code;
  private String message;
  private T data;

  public static <T> Result<T> ok(T data) {
    Result<T> r = new Result<>();
    r.setCode(0);
    r.setMessage("success");
    r.setData(data);
    return r;
  }

  public static Result<Void> ok() {
    return ok(null);
  }

  public static <T> Result<T> error(int code, String message) {
    return error(code, message, null);
  }

  public static <T> Result<T> error(int code, String message, T data) {
    Result<T> r = new Result<>();
    r.setCode(code);
    r.setMessage(message);
    r.setData(data);
    return r;
  }
}
