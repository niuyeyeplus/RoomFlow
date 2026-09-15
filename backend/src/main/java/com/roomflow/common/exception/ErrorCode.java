package com.roomflow.common.exception;

/** Business error codes aligned with docs/api/README.md error-code table. */
public enum ErrorCode {
  PARAM_INVALID(40001, 400, "参数校验失败"),
  BAD_REQUEST(40002, 400, "请求格式错误"),
  UNAUTHORIZED(40101, 401, "未认证或 Token 无效"),
  TOKEN_EXPIRED(40102, 401, "Access Token 已过期"),
  BAD_CREDENTIALS(40103, 401, "用户名或密码错误"),
  REFRESH_INVALID(40104, 401, "Refresh Token 无效、已过期或已撤销"),
  FORBIDDEN(40301, 403, "无权限执行该操作"),
  ACCOUNT_DISABLED(40302, 403, "账号已禁用"),
  NOT_FOUND(40401, 404, "资源不存在"),
  USERNAME_EXISTS(40901, 409, "用户名已存在"),
  TIME_CONFLICT(40902, 409, "会议时间与该房间已有会议冲突"),
  CAPACITY_FULL(40903, 409, "报名人数已达房间容量上限"),
  JOIN_BANNED(40904, 409, "你已被禁止报名该会议"),
  TIME_RULE(40905, 409, "会议时间不符合规则"),
  ROOM_DISABLED(40906, 409, "房间已停用"),
  ROOM_HAS_ACTIVE_MEETING(40907, 409, "房间存在进行中的会议，不可停用"),
  DUPLICATE_JOIN(40908, 409, "你已报名该会议"),
  STATE_NOT_ALLOWED(40909, 409, "会议当前状态不允许该操作"),
  INTERNAL(50000, 500, "服务器内部错误");

  private final int code;
  private final int httpStatus;
  private final String defaultMessage;

  ErrorCode(int code, int httpStatus, String defaultMessage) {
    this.code = code;
    this.httpStatus = httpStatus;
    this.defaultMessage = defaultMessage;
  }

  public int getCode() {
    return code;
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  public String getDefaultMessage() {
    return defaultMessage;
  }
}
