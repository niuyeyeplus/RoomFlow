package com.roomflow.security;

import com.roomflow.common.enums.Role;
import java.security.Principal;

/** Authenticated principal stored in the SecurityContext. */
public record LoginAccount(Long id, String username, Role role) implements Principal {

  @Override
  public String getName() {
    return username;
  }
}
