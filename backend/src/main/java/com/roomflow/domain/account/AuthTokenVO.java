package com.roomflow.domain.account;

import lombok.Data;

@Data
public class AuthTokenVO {

  private String tokenType = "Bearer";
  private String accessToken;
  private long accessTokenExpiresIn;
  private String refreshToken;
  private long refreshTokenExpiresIn;
}
