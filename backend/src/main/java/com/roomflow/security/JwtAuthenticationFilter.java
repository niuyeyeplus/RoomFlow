package com.roomflow.security;

import com.roomflow.common.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer JWT filter. A token is trusted only when (1) the signature and expiry are valid, (2) its
 * jti session still exists in Redis (logout/disable revocations take effect immediately), and (3)
 * the account is still enabled. Failures never throw: the request continues unauthenticated and the
 * entry point renders 40101/40102.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  /** Request attribute carrying the numeric error code for the authentication entry point. */
  public static final String AUTH_ERROR_ATTR = "roomflow.authErrorCode";

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtTokenProvider tokenProvider;
  private final SessionTokenStore sessionTokenStore;
  private final UserDetailsServiceImpl userDetailsService;

  public JwtAuthenticationFilter(
      JwtTokenProvider tokenProvider,
      SessionTokenStore sessionTokenStore,
      UserDetailsServiceImpl userDetailsService) {
    this.tokenProvider = tokenProvider;
    this.sessionTokenStore = sessionTokenStore;
    this.userDetailsService = userDetailsService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      chain.doFilter(request, response);
      return;
    }
    try {
      Claims claims = tokenProvider.parse(header.substring(BEARER_PREFIX.length()));
      String sessionId = claims.getId();
      if (!JwtTokenProvider.TYPE_ACCESS.equals(
              claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))
          || !sessionTokenStore.isSessionActive(sessionId)) {
        request.setAttribute(AUTH_ERROR_ATTR, ErrorCode.UNAUTHORIZED.getCode());
      } else {
        LoginAccount account = userDetailsService.loadById(Long.valueOf(claims.getSubject()));
        if (account == null) {
          request.setAttribute(AUTH_ERROR_ATTR, ErrorCode.UNAUTHORIZED.getCode());
        } else {
          UsernamePasswordAuthenticationToken authentication =
              new UsernamePasswordAuthenticationToken(
                  account,
                  sessionId,
                  List.of(new SimpleGrantedAuthority("ROLE_" + account.role().name())));
          authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
          SecurityContextHolder.getContext().setAuthentication(authentication);
        }
      }
    } catch (ExpiredJwtException e) {
      request.setAttribute(AUTH_ERROR_ATTR, ErrorCode.TOKEN_EXPIRED.getCode());
    } catch (JwtException | IllegalArgumentException e) {
      request.setAttribute(AUTH_ERROR_ATTR, ErrorCode.UNAUTHORIZED.getCode());
    }
    chain.doFilter(request, response);
  }
}
