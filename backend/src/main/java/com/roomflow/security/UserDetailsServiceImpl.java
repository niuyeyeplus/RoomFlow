package com.roomflow.security;

import com.roomflow.domain.account.Account;
import com.roomflow.mapper.AccountMapper;
import org.springframework.stereotype.Service;

/** Loads the live account for each authenticated request so disabled accounts are rejected. */
@Service
public class UserDetailsServiceImpl {

  private final AccountMapper accountMapper;

  public UserDetailsServiceImpl(AccountMapper accountMapper) {
    this.accountMapper = accountMapper;
  }

  /** Returns null when the account does not exist or is disabled (status != 1). */
  public LoginAccount loadById(Long accountId) {
    Account account = accountMapper.selectById(accountId);
    if (account == null || account.getStatus() == null || account.getStatus() != 1) {
      return null;
    }
    return new LoginAccount(account.getId(), account.getUsername(), account.getRole());
  }
}
