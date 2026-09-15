package com.roomflow.domain.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {

  @NotBlank(message = "用户名不能为空")
  @Size(max = 50, message = "用户名长度不得超过 50 位")
  private String username;

  @NotBlank(message = "密码不能为空")
  @Size(max = 64, message = "密码长度不得超过 64 位")
  private String password;
}
