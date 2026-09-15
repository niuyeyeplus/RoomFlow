package com.roomflow.common.result;

/** Field-level validation error detail carried by Result.data.fieldErrors when code=40001. */
public record FieldError(String field, String message) {}
