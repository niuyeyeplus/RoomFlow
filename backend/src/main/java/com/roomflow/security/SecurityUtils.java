package com.roomflow.security;

import com.roomflow.common.exception.BizException;
import com.roomflow.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Accessors for the current authenticated principal/session inside the service layer. */
public final class SecurityUtils {

  private SecurityUtils() {}

  public static LoginAccount currentAccount() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !(authentication.getPrincipal() instanceof LoginAccount account)) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return account;
  }

  /** The session id (access-token jti) stored as the authentication credentials. */
  public static String currentSessionId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication.getCredentials() == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return authentication.getCredentials().toString();
  }
}
