package com.roomflow.domain.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

  @NotBlank(message = "用户名不能为空")
  @Pattern(regexp = "^[A-Za-z0-9_]{3,50}$", message = "用户名须为 3-50 位字母、数字或下划线")
  private String username;

  @NotBlank(message = "密码不能为空")
  @Size(min = 8, max = 64, message = "密码长度须为 8-64 位")
  private String password;
}
