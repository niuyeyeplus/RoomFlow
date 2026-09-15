package com.roomflow.domain.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RefreshRequest {

  @NotBlank(message = "refreshToken 不能为空")
  @Size(max = 128, message = "refreshToken 长度不得超过 128")
  private String refreshToken;
}
