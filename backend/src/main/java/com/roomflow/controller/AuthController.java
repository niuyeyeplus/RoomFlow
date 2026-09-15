package com.roomflow.controller;

import com.roomflow.common.result.Result;
import com.roomflow.domain.account.AccountVO;
import com.roomflow.domain.account.AuthTokenVO;
import com.roomflow.domain.account.LoginRequest;
import com.roomflow.domain.account.RefreshRequest;
import com.roomflow.domain.account.RegisterRequest;
import com.roomflow.security.SecurityUtils;
import com.roomflow.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AccountService accountService;

  public AuthController(AccountService accountService) {
    this.accountService = accountService;
  }

  @PostMapping("/register")
  public ResponseEntity<Result<AuthTokenVO>> register(@Valid @RequestBody RegisterRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Result.ok(accountService.register(request)));
  }

  @PostMapping("/login")
  public Result<AuthTokenVO> login(@Valid @RequestBody LoginRequest request) {
    return Result.ok(accountService.login(request));
  }

  @PostMapping("/logout")
  public Result<Void> logout() {
    accountService.logout(SecurityUtils.currentSessionId());
    return Result.ok();
  }

  /** Credential is the request-body refresh token, not the Bearer header; rotates on success. */
  @PostMapping("/refresh")
  public Result<AuthTokenVO> refresh(@Valid @RequestBody RefreshRequest request) {
    return Result.ok(accountService.refresh(request.getRefreshToken()));
  }

  @GetMapping("/me")
  public Result<AccountVO> me() {
    return Result.ok(accountService.me(SecurityUtils.currentAccount()));
  }
}
