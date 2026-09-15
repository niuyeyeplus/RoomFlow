package com.roomflow.common.result;

import java.util.List;

/** Error payload shape for code=40001: {"fieldErrors":[{"field":..., "message":...}]}. */
public record ValidationErrors(List<FieldError> fieldErrors) {

  public static ValidationErrors of(String field, String message) {
    return new ValidationErrors(List.of(new FieldError(field, message)));
  }
}
